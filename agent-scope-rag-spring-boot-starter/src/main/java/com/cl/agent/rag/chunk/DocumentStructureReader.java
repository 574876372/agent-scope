package com.cl.agent.rag.chunk;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.Tika;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多格式文档结构还原器：把文件解析为「标题 / 段落 / 表格」结构块序列。
 * <p>使用说明：由宿主入库流水线调用，产出交给 {@link StructuredChunker} 切片。各格式规则：</p>
 * <ul>
 *   <li>Markdown：按 {@code #} 识别标题层级，识别管道表格与围栏代码块；</li>
 *   <li>TXT：按空行分段，并用中文公文常见编号（第一章、一、（一）、1.1 等）推断标题；</li>
 *   <li>Word（.docx）：读取段落样式（Heading N / 标题 N / 大纲级别）识别标题，表格按行还原；</li>
 *   <li>Excel（.xlsx / .xls）：每个工作表一个标题 + 一张表格，首个非空行作为表头；</li>
 *   <li>PDF、PPT、旧版 Word 等：Tika 提取纯文本后按 TXT 规则处理。</li>
 * </ul>
 * <p>本类无状态、线程安全。</p>
 */
@Slf4j
public class DocumentStructureReader {

    /** Markdown ATX 标题：1~6 个井号，井号后空格可缺省（兼容「##标题」写法） */
    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s*(.+?)\\s*#*\\s*$");

    /** Markdown 表格分隔行，如 |---|:---:| */
    private static final Pattern MD_TABLE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    /** 围栏代码块起止行 */
    private static final Pattern MD_FENCE = Pattern.compile("^\\s{0,3}(```|~~~)");

    /** 推断标题：第 X 章 / 篇 / 部分，层级 1 */
    private static final Pattern H_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+[章篇部分]\\s*.*");

    /** 推断标题：第 X 节，层级 2 */
    private static final Pattern H_SECTION = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+节\\s*.*");

    /** 推断标题：一、，层级 3 */
    private static final Pattern H_CN_NUM = Pattern.compile("^[一二三四五六七八九十]{1,3}[、.．]\\s*\\S.*");

    /** 推断标题：（一），层级 4 */
    private static final Pattern H_CN_PAREN = Pattern.compile("^[（(][一二三四五六七八九十]{1,3}[)）]\\s*\\S.*");

    /**
     * 推断标题：1.2 / 1.2.3 这类多级编号，层级 = 4 + 小数点层数。
     * <p>单级的「1 xxx」「1. xxx」在正文中多为列表项，不作为标题，避免把列表拆成大量碎片切片。</p>
     */
    private static final Pattern H_DECIMAL = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2}){1,3})[.、．]?\\s*[^\\d\\s.].*");

    /** 推断标题的最大长度，超过视为正文 */
    private static final int HEADING_MAX_LENGTH = 40;

    /** 句末标点：以这些字符结尾的行视为正文而非标题 */
    private static final String SENTENCE_END = "。；;，,：:!！?？";

    /** Word 样式名中的标题层级，如 heading 1 / Heading1 / 标题 1 */
    private static final Pattern WORD_HEADING_STYLE = Pattern.compile("(?:heading|标题)\\s*(\\d)", Pattern.CASE_INSENSITIVE);

    /**
     * 解析文件为结构块序列。
     *
     * @param file 文件路径，必须可读
     * @param ext  文件扩展名（不含点），不区分大小写，如 md / txt / docx / xlsx / pdf
     * @return 结构块列表；文件无有效内容时返回空列表
     * @throws IOException 文件读取或解析失败时抛出
     */
    public List<DocBlock> read(Path file, String ext) throws IOException {
        String type = ext == null ? "" : ext.trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "md":
            case "markdown":
                return parseMarkdown(readText(file));
            case "txt":
                return parsePlainText(readText(file));
            case "docx":
                return readDocx(file);
            case "xlsx":
            case "xls":
                return readSpreadsheet(file);
            default:
                return parsePlainText(readWithTika(file));
        }
    }

    // ========================================================
    // Markdown / 纯文本
    // ========================================================

    /**
     * 解析 Markdown 文本：标题、管道表格、围栏代码块与段落。
     *
     * @param text Markdown 原文，可为空
     * @return 结构块列表；空文本返回空列表
     */
    public List<DocBlock> parseMarkdown(String text) {
        List<DocBlock> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        String[] lines = normalizeNewlines(text).split("\n", -1);
        // 当前段落的累积行
        List<String> para = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            // 围栏代码块整体作为一个段落，内部的 # 与 | 不做结构识别
            if (MD_FENCE.matcher(line).find()) {
                flushParagraph(para, blocks);
                StringBuilder code = new StringBuilder(line);
                i++;
                while (i < lines.length) {
                    code.append('\n').append(lines[i]);
                    if (MD_FENCE.matcher(lines[i]).find()) {
                        i++;
                        break;
                    }
                    i++;
                }
                blocks.add(DocBlock.paragraph(code.toString()));
                continue;
            }
            Matcher hm = MD_HEADING.matcher(line);
            if (hm.matches()) {
                flushParagraph(para, blocks);
                blocks.add(DocBlock.heading(hm.group(1).length(), hm.group(2).trim()));
                i++;
                continue;
            }
            if (isTableLine(line)) {
                flushParagraph(para, blocks);
                i = readMarkdownTable(lines, i, blocks);
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(para, blocks);
            } else {
                para.add(line);
            }
            i++;
        }
        flushParagraph(para, blocks);
        return blocks;
    }

    /**
     * 解析纯文本：按空行分段，单行短文本若符合常见编号格式则推断为标题。
     *
     * @param text 纯文本，可为空
     * @return 结构块列表；空文本返回空列表
     */
    public List<DocBlock> parsePlainText(String text) {
        List<DocBlock> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        List<String> para = new ArrayList<>();
        for (String raw : normalizeNewlines(text).split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flushParagraph(para, blocks);
                continue;
            }
            int level = inferHeadingLevel(line);
            if (level > 0) {
                flushParagraph(para, blocks);
                blocks.add(DocBlock.heading(level, line));
            } else {
                para.add(line);
            }
        }
        flushParagraph(para, blocks);
        return blocks;
    }

    /**
     * 按中文文档常见编号格式推断单行文本是否为标题。
     *
     * @param line 已去除首尾空白的单行文本
     * @return 标题层级（1 为最高级）；不是标题时返回 0
     */
    int inferHeadingLevel(String line) {
        if (line.length() > HEADING_MAX_LENGTH || SENTENCE_END.indexOf(line.charAt(line.length() - 1)) >= 0) {
            return 0;
        }
        if (H_CHAPTER.matcher(line).matches()) {
            return 1;
        }
        if (H_SECTION.matcher(line).matches()) {
            return 2;
        }
        if (H_CN_NUM.matcher(line).matches()) {
            return 3;
        }
        if (H_CN_PAREN.matcher(line).matches()) {
            return 4;
        }
        Matcher m = H_DECIMAL.matcher(line);
        if (m.matches()) {
            return 4 + (int) m.group(1).chars().filter(c -> c == '.').count();
        }
        return 0;
    }

    // ========================================================
    // Word / Excel / Tika
    // ========================================================

    /**
     * 读取 .docx：按段落样式识别标题，表格按行还原为 Markdown 管道格式。
     * <p>文档完全没有标题样式时（常见于手工加粗的文档），退化为按编号格式推断标题。</p>
     */
    private List<DocBlock> readDocx(Path file) throws IOException {
        List<DocBlock> blocks = new ArrayList<>();
        // 是否识别到至少一个样式标题；为 false 时对段落启用编号推断
        boolean hasStyledHeading = false;
        try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph p = (XWPFParagraph) element;
                    String text = p.getText() == null ? "" : p.getText().strip();
                    if (text.isEmpty()) {
                        continue;
                    }
                    int level = wordHeadingLevel(doc, p);
                    if (level > 0) {
                        hasStyledHeading = true;
                        blocks.add(DocBlock.heading(level, text));
                    } else {
                        blocks.add(DocBlock.paragraph(text));
                    }
                } else if (element instanceof XWPFTable) {
                    DocBlock table = wordTable((XWPFTable) element);
                    if (table != null) {
                        blocks.add(table);
                    }
                }
            }
        }
        if (!hasStyledHeading) {
            blocks = promoteInferredHeadings(blocks);
        }
        return blocks;
    }

    /**
     * 解析 Word 段落的标题层级：优先段落自身大纲级别，其次样式名（含样式继承链）与样式大纲级别。
     *
     * @return 标题层级 1~9；非标题返回 0
     */
    private int wordHeadingLevel(XWPFDocument doc, XWPFParagraph p) {
        try {
            if (p.getCTP().getPPr() != null && p.getCTP().getPPr().getOutlineLvl() != null) {
                int lvl = p.getCTP().getPPr().getOutlineLvl().getVal().intValue();
                // 大纲级别 9 表示「正文文本」
                if (lvl < 9) {
                    return lvl + 1;
                }
            }
            String styleId = p.getStyleID();
            // 沿样式继承链最多向上查找 5 层，兼容「自定义标题」基于「标题 1」派生的样式
            for (int depth = 0; styleId != null && doc.getStyles() != null && depth < 5; depth++) {
                XWPFStyle style = doc.getStyles().getStyle(styleId);
                if (style == null) {
                    break;
                }
                String name = style.getName() == null ? "" : style.getName();
                Matcher m = WORD_HEADING_STYLE.matcher(name);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
                if ("title".equalsIgnoreCase(name) || "标题".equals(name)) {
                    return 1;
                }
                if (style.getCTStyle().getPPr() != null && style.getCTStyle().getPPr().getOutlineLvl() != null) {
                    int lvl = style.getCTStyle().getPPr().getOutlineLvl().getVal().intValue();
                    if (lvl < 9) {
                        return lvl + 1;
                    }
                }
                styleId = style.getBasisStyleID();
            }
            // 样式 ID 本身即为 Heading1 这类内置标识的情况
            if (p.getStyleID() != null) {
                Matcher m = WORD_HEADING_STYLE.matcher(p.getStyleID());
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (Exception e) {
            log.debug("[RAG-Structure] 读取 Word 段落样式失败，按正文处理: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 把 Word 表格转为表格块：首行作为表头，单元格内换行替换为空格，避免破坏管道格式。
     *
     * @return 表格块；空表返回 null
     */
    private DocBlock wordTable(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText());
            }
            if (cells.stream().anyMatch(c -> c != null && !c.isBlank())) {
                rows.add(cells);
            }
        }
        return toTableBlock(rows);
    }

    /**
     * 读取 Excel：每个工作表输出一个一级标题（工作表名）与一张表格，首个非空行作为表头。
     */
    private List<DocBlock> readSpreadsheet(Path file) throws IOException {
        List<DocBlock> blocks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(file.toFile(), null, true)) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                List<List<String>> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    short lastCell = row.getLastCellNum();
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : formatCell(formatter, evaluator, cell));
                    }
                    if (cells.stream().anyMatch(v -> !v.isBlank())) {
                        rows.add(cells);
                    }
                }
                DocBlock table = toTableBlock(rows);
                if (table != null) {
                    blocks.add(DocBlock.heading(1, sheet.getSheetName()));
                    blocks.add(table);
                }
            }
        }
        return blocks;
    }

    /**
     * 格式化单元格；公式求值失败时退回公式原文。
     */
    private static String formatCell(DataFormatter formatter, FormulaEvaluator evaluator, Cell cell) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (Exception e) {
            return formatter.formatCellValue(cell);
        }
    }

    /**
     * 使用 Tika 提取纯文本，不限制提取长度。
     */
    private String readWithTika(Path file) throws IOException {
        Tika tika = new Tika();
        // 默认只提取前 10 万字符，长文档会被截断，这里取消限制
        tika.setMaxStringLength(-1);
        try {
            return tika.parseToString(file);
        } catch (Exception e) {
            throw new IOException("文档文本提取失败: " + e.getMessage(), e);
        }
    }

    // ========================================================
    // 公共辅助
    // ========================================================

    /**
     * 读取文本文件：依次尝试 UTF-8、GBK，都失败时按 ISO-8859-1 读取，保证不因编码中断入库。
     */
    private String readText(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readString(file, Charset.forName("GBK"));
            } catch (MalformedInputException ex) {
                log.warn("[RAG-Structure] UTF-8 与 GBK 解码均失败，按 ISO-8859-1 读取: {}", file);
                return Files.readString(file, StandardCharsets.ISO_8859_1);
            }
        }
    }

    /**
     * 读取从 start 行开始的连续 Markdown 表格，写入 blocks。
     *
     * @return 表格之后的下一行下标
     */
    private int readMarkdownTable(String[] lines, int start, List<DocBlock> blocks) {
        List<String> header = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        int i = start;
        // 第二行是分隔行时，第一行为表头；否则整张表都是数据行
        if (i + 1 < lines.length && MD_TABLE_SEPARATOR.matcher(lines[i + 1].trim()).matches()) {
            header.add(lines[i].trim());
            header.add(lines[i + 1].trim());
            i += 2;
        }
        while (i < lines.length && isTableLine(lines[i])) {
            rows.add(lines[i].trim());
            i++;
        }
        if (rows.isEmpty() && !header.isEmpty()) {
            // 只有表头没有数据行，按普通段落保留
            blocks.add(DocBlock.paragraph(String.join("\n", header)));
        } else {
            blocks.add(DocBlock.table(header, rows));
        }
        return i;
    }

    /**
     * 判断是否为 Markdown 管道表格行：以 | 开头，且至少包含两个 |。
     */
    private static boolean isTableLine(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.indexOf('|', 1) > 0;
    }

    /**
     * 把二维单元格转为表格块，首行作为表头；列数以最宽的一行为准。
     *
     * @return 表格块；无数据时返回 null
     */
    static DocBlock toTableBlock(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) {
            return null;
        }
        List<String> header = new ArrayList<>();
        header.add(toPipeRow(rows.get(0), columns));
        header.add(toPipeRow(Collections.nCopies(columns, "---"), columns));
        List<String> body = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            body.add(toPipeRow(rows.get(r), columns));
        }
        if (body.isEmpty()) {
            // 单行表格没有数据行，直接作为数据行保留，避免只剩表头
            return DocBlock.table(List.of(), List.of(header.get(0)));
        }
        return DocBlock.table(header, body);
    }

    /**
     * 把单元格拼为 Markdown 管道行，转义单元格中的 | 并把换行替换为空格。
     */
    private static String toPipeRow(List<String> cells, int columns) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < columns; c++) {
            String v = c < cells.size() && cells.get(c) != null ? cells.get(c) : "";
            v = v.replace("\r", " ").replace("\n", " ").replace("|", "\\|").strip();
            sb.append(' ').append(v).append(" |");
        }
        return sb.toString();
    }

    /**
     * 对没有样式标题的文档，把符合编号格式的单行段落提升为标题。
     */
    private List<DocBlock> promoteInferredHeadings(List<DocBlock> blocks) {
        List<DocBlock> result = new ArrayList<>(blocks.size());
        for (DocBlock b : blocks) {
            if (b.getType() == DocBlockType.PARAGRAPH && b.getText() != null && !b.getText().contains("\n")) {
                int level = inferHeadingLevel(b.getText().strip());
                if (level > 0) {
                    result.add(DocBlock.heading(level, b.getText().strip()));
                    continue;
                }
            }
            result.add(b);
        }
        return result;
    }

    private static void flushParagraph(List<String> para, List<DocBlock> blocks) {
        if (!para.isEmpty()) {
            String text = String.join("\n", para).strip();
            if (!text.isEmpty()) {
                blocks.add(DocBlock.paragraph(text));
            }
            para.clear();
        }
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
