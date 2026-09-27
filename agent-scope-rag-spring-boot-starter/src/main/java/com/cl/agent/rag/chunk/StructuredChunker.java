package com.cl.agent.rag.chunk;

import com.cl.agent.enums.ChunkStrategyEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构化切片器：按章节、问答对或表格行切片，并为每片记录章节路径与切片类型。
 * <p>使用说明：输入 {@link DocumentStructureReader} 产出的结构块与 {@link ChunkOptions}，输出切片列表。
 * 与固定长度切片的区别：</p>
 * <ul>
 *   <li>切片不跨章节，章节标题写入切片首行并记录为章节路径；</li>
 *   <li>表格不从行中间切断，超长时按行拆分且每片重复表头；</li>
 *   <li>只有单个段落本身超过切片上限时，才按句拆分并保留重叠。</li>
 * </ul>
 * <p>本类无状态、线程安全。</p>
 */
public class StructuredChunker {

    /** 章节路径分隔符，与前端「文档 › 章节」展示一致 */
    public static final String PATH_SEPARATOR = " › ";

    /** 表格前的说明文字不超过该长度时并入表格切片首行（如「请求参数：」），避免产生只有一句话的切片 */
    private static final int TABLE_CAPTION_MAX = 200;

    /** 问答切片的长度上限倍数：一问一答尽量保持完整，超过切片上限的该倍数才按段落拆分 */
    private static final int QA_MAX_FACTOR = 3;

    /** 识别问题行：Q: / Q1. / 问： / 问题1： / 【问题】 / 以问号结尾的短句 */
    private static final Pattern QUESTION_LINE = Pattern.compile(
            "^(?:Q\\s*\\d*\\s*[:：.、]|问\\s*[:：]|问题\\s*\\d*\\s*[:：]|【问题?】|\\d{1,3}\\s*[.、．]\\s*.{2,80}[?？]$|.{2,80}[?？]$)",
            Pattern.CASE_INSENSITIVE);

    /** 按句拆分超长段落时的句末标点 */
    private static final String SENTENCE_BREAKS = "。！？!?；;\n";

    /**
     * 对结构块序列切片。
     *
     * @param blocks  结构块，可为空列表
     * @param options 切片参数，非空；{@code chunkSize} 须为正数
     * @return 切片列表，按原文顺序排列；无内容时返回空列表
     */
    public List<ChunkPiece> chunk(List<DocBlock> blocks, ChunkOptions options) {
        if (blocks == null || blocks.isEmpty()) {
            return new ArrayList<>();
        }
        ChunkStrategyEnum strategy = options.getStrategy() == null ? ChunkStrategyEnum.SECTION : options.getStrategy();
        if (strategy == ChunkStrategyEnum.QA) {
            List<ChunkPiece> qa = chunkQa(blocks, options);
            // 未识别出至少两组问答时说明不是问答格式，退化为按章节切分
            if (qa != null) {
                return qa;
            }
        }
        return chunkBySection(blocks, options);
    }

    // ========================================================
    // 按章节（SECTION / TABLE_ROW）
    // ========================================================

    /**
     * 按章节切分：标题切换时结束当前切片；段落累积至上限；表格单独成片，超长按行拆分并重复表头。
     * TABLE_ROW 策略与此共用，差别只在表格上限不放宽。
     */
    private List<ChunkPiece> chunkBySection(List<DocBlock> blocks, ChunkOptions options) {
        SectionState state = new SectionState(options);
        for (DocBlock block : blocks) {
            switch (block.getType()) {
                case HEADING:
                    state.flushText();
                    state.pushHeading(block.getLevel(), block.getText());
                    break;
                case TABLE:
                    state.appendTable(block);
                    break;
                case PARAGRAPH:
                default:
                    state.appendParagraph(block.getText());
                    break;
            }
        }
        state.flushText();
        return state.pieces;
    }

    /**
     * 按章节切分过程中的可变状态：标题栈、正文缓冲与已产出切片。
     * <p>仅在单次 {@link #chunkBySection} 调用内使用，不跨线程共享。</p>
     */
    private final class SectionState {

        /** 切片参数 */
        private final ChunkOptions options;

        /** 已产出的切片 */
        private final List<ChunkPiece> pieces = new ArrayList<>();

        /** 标题栈中各标题的层级，与 {@link #titles} 一一对应 */
        private final List<Integer> levels = new ArrayList<>();

        /** 标题栈中的标题文本，从最高级到当前级 */
        private final List<String> titles = new ArrayList<>();

        /** 当前章节尚未输出的正文缓冲 */
        private final StringBuilder buffer = new StringBuilder();

        /** 当前章节标题是否已写入过切片；写入后后续切片不再重复标题行 */
        private boolean headingEmitted = true;

        SectionState(ChunkOptions options) {
            this.options = options;
        }

        /** 入栈标题：弹出所有层级不低于它的旧标题，再压入 */
        void pushHeading(int level, String title) {
            while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                levels.remove(levels.size() - 1);
                titles.remove(titles.size() - 1);
            }
            levels.add(level);
            titles.add(title);
            headingEmitted = false;
        }

        /** 当前章节路径 */
        String sectionPath() {
            return String.join(PATH_SEPARATOR, titles);
        }

        /** 当前章节标题（标题栈顶），无标题时为 null */
        String currentTitle() {
            return titles.isEmpty() ? null : titles.get(titles.size() - 1);
        }

        /** 追加段落：放不下时先输出缓冲；段落本身超长时按句拆分 */
        void appendParagraph(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            ensureHeadingLine();
            int limit = options.getChunkSize();
            if (text.length() > limit) {
                // 缓冲中只有标题或短文字时与超长段落一起拆分，避免标题行单独丢失
                if (buffer.length() > 0) {
                    text = buffer + "\n" + text;
                    buffer.setLength(0);
                }
                List<String> parts = splitLongText(text, limit, options.getChunkOverlap());
                for (int i = 0; i < parts.size() - 1; i++) {
                    emit(parts.get(i), ChunkPiece.TYPE_TEXT);
                }
                // 最后一段留在缓冲，允许与后续短段落合并
                buffer.append(parts.get(parts.size() - 1));
                return;
            }
            if (buffer.length() > 0 && buffer.length() + text.length() + 1 > limit) {
                flushText();
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(text);
        }

        /**
         * 追加表格：短说明文字并入表格首片；表格在上限内整体成片，否则按行分组并在每组重复表头。
         */
        void appendTable(DocBlock table) {
            ensureHeadingLine();
            // 缓冲中的短说明（如标题行 +「请求参数：」）作为表格切片的前导文字
            String caption = "";
            if (buffer.length() > 0 && buffer.length() <= TABLE_CAPTION_MAX) {
                caption = buffer.toString();
                buffer.setLength(0);
            } else {
                flushText();
            }
            String header = String.join("\n", table.getTableHeader());
            int limit = options.getChunkSize();
            int wholeLimit = options.isKeepTableWhole() ? limit * 2 : limit;
            if (caption.length() + table.textLength() <= wholeLimit) {
                emit(joinLines(caption, header, String.join("\n", table.getTableRows())), ChunkPiece.TYPE_TABLE);
                return;
            }
            // 按行分组：每组 = 表头 + 若干完整行，不超过上限；单行超长时独占一片
            StringBuilder group = new StringBuilder();
            String prefix = caption;
            for (String row : table.getTableRows()) {
                int base = prefix.length() + header.length() + 2;
                if (group.length() > 0 && base + group.length() + row.length() + 1 > limit) {
                    emit(joinLines(prefix, header, group.toString()), ChunkPiece.TYPE_TABLE);
                    group.setLength(0);
                    // 说明文字只出现在第一片，后续片靠章节路径与表头保持语义
                    prefix = "";
                }
                if (group.length() > 0) {
                    group.append('\n');
                }
                group.append(row);
            }
            if (group.length() > 0) {
                emit(joinLines(prefix, header, group.toString()), ChunkPiece.TYPE_TABLE);
            }
        }

        /** 章节标题尚未写入时，把标题作为缓冲首行，使切片正文自带标题便于阅读与关键词命中 */
        private void ensureHeadingLine() {
            if (!headingEmitted) {
                headingEmitted = true;
                String title = currentTitle();
                if (title != null && buffer.length() == 0) {
                    buffer.append(title);
                }
            }
        }

        /** 输出缓冲为正文切片；缓冲只有标题行时丢弃（该标题下紧跟子标题，无正文） */
        void flushText() {
            if (buffer.length() == 0) {
                return;
            }
            String text = buffer.toString().strip();
            buffer.setLength(0);
            if (text.isEmpty() || text.equals(currentTitle())) {
                return;
            }
            emit(text, ChunkPiece.TYPE_TEXT);
        }

        private void emit(String content, String type) {
            String text = content.strip();
            if (!text.isEmpty()) {
                pieces.add(new ChunkPiece(text, sectionPath(), type));
            }
        }
    }

    // ========================================================
    // 问答对（QA）
    // ========================================================

    /**
     * 一问一答切分：问题行开启新切片，其后的行作为答案，直到下一个问题。
     *
     * @return 切片列表；识别出的问题少于两个时返回 null，由调用方退化为按章节切分
     */
    private List<ChunkPiece> chunkQa(List<DocBlock> blocks, ChunkOptions options) {
        List<ChunkPiece> pieces = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder current = null;
        // 第一个问题之前的说明文字，单独作为正文切片保留
        StringBuilder preamble = new StringBuilder();
        int questions = 0;
        String currentPath = "";
        for (DocBlock block : blocks) {
            List<String> lines = new ArrayList<>();
            if (block.getType() == DocBlockType.HEADING) {
                String title = block.getText().strip();
                if (QUESTION_LINE.matcher(title).find()) {
                    lines.add(title);
                } else {
                    // 非问题标题视为分类，维护为章节路径
                    while (headings.size() >= block.getLevel() && !headings.isEmpty()) {
                        headings.remove(headings.size() - 1);
                    }
                    headings.add(title);
                    continue;
                }
            } else if (block.getType() == DocBlockType.TABLE) {
                lines.addAll(block.getTableHeader());
                lines.addAll(block.getTableRows());
            } else {
                for (String l : block.getText().split("\n")) {
                    if (!l.isBlank()) {
                        lines.add(l.strip());
                    }
                }
            }
            for (String line : lines) {
                if (QUESTION_LINE.matcher(line).find()) {
                    questions++;
                    addQa(pieces, current, currentPath, options);
                    current = new StringBuilder(line);
                    currentPath = String.join(PATH_SEPARATOR, headings);
                } else if (current != null) {
                    current.append('\n').append(line);
                } else {
                    preamble.append(preamble.length() > 0 ? "\n" : "").append(line);
                }
            }
        }
        addQa(pieces, current, currentPath, options);
        if (questions < 2) {
            return null;
        }
        if (preamble.length() > 0) {
            List<ChunkPiece> intro = new ArrayList<>();
            for (String part : splitLongText(preamble.toString(), options.getChunkSize(), options.getChunkOverlap())) {
                intro.add(new ChunkPiece(part.strip(), "", ChunkPiece.TYPE_TEXT));
            }
            pieces.addAll(0, intro);
        }
        return pieces;
    }

    /**
     * 输出一个问答切片；超过上限的 {@link #QA_MAX_FACTOR} 倍时按句拆分，每片重复问题行。
     */
    private void addQa(List<ChunkPiece> pieces, StringBuilder qa, String path, ChunkOptions options) {
        if (qa == null || qa.toString().isBlank()) {
            return;
        }
        String text = qa.toString().strip();
        int max = options.getChunkSize() * QA_MAX_FACTOR;
        if (text.length() <= max) {
            pieces.add(new ChunkPiece(text, path, ChunkPiece.TYPE_QA));
            return;
        }
        int nl = text.indexOf('\n');
        String question = nl > 0 ? text.substring(0, nl) : "";
        String answer = nl > 0 ? text.substring(nl + 1) : text;
        for (String part : splitLongText(answer, Math.max(options.getChunkSize(), max - question.length() - 1), 0)) {
            pieces.add(new ChunkPiece(joinLines("", question, part), path, ChunkPiece.TYPE_QA));
        }
    }

    // ========================================================
    // 工具方法
    // ========================================================

    /**
     * 按句拆分超长文本，每段不超过上限；单句超长时硬切。相邻段落保留 overlap 个字符的重叠。
     *
     * @param text    待拆分文本，非空
     * @param limit   每段上限，正数
     * @param overlap 重叠字符数，0 表示不重叠；会被限制在上限的一半以内
     * @return 拆分结果，至少一段
     */
    static List<String> splitLongText(String text, int limit, int overlap) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (SENTENCE_BREAKS.indexOf(c) >= 0) {
                sentences.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            sentences.add(sb.toString());
        }
        int safeOverlap = Math.max(0, Math.min(overlap, limit / 2));
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() > 0 && cur.length() + s.length() > limit) {
                parts.add(cur.toString());
                String tail = safeOverlap > 0 ? cur.substring(Math.max(0, cur.length() - safeOverlap)) : "";
                cur.setLength(0);
                cur.append(tail);
            }
            // 单句超过上限：硬切成多段
            while (cur.length() + s.length() > limit) {
                int take = Math.max(1, limit - cur.length());
                cur.append(s, 0, Math.min(take, s.length()));
                parts.add(cur.toString());
                s = s.substring(Math.min(take, s.length()));
                cur.setLength(0);
            }
            cur.append(s);
        }
        if (cur.toString().strip().length() > 0) {
            parts.add(cur.toString());
        }
        if (parts.isEmpty()) {
            parts.add(text);
        }
        return parts;
    }

    private static String joinLines(String a, String b, String c) {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{a, b, c}) {
            if (s != null && !s.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
