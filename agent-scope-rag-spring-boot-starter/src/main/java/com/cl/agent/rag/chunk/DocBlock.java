package com.cl.agent.rag.chunk;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档结构块：标题、段落或表格。
 * <p>由 {@link DocumentStructureReader} 产出，是切片前的中间表示；表格统一转换为 Markdown 管道格式的行，
 * 以便切片后仍能被模型识别为表格。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocBlock {

    /** 块类型 */
    private DocBlockType type;

    /** 标题层级，仅 HEADING 有效，1 为最高级；其余类型为 0 */
    private int level;

    /** 标题或段落的文本；TABLE 时为空 */
    private String text;

    /** 表头行（Markdown 管道格式，含分隔行），仅 TABLE 有效；拆分表格时每片都会重复这些行 */
    private List<String> tableHeader = new ArrayList<>();

    /** 表格数据行（Markdown 管道格式），仅 TABLE 有效 */
    private List<String> tableRows = new ArrayList<>();

    /**
     * 构造标题块。
     *
     * @param level 标题层级，1 为最高级
     * @param text  标题文本，非空
     * @return 标题块
     */
    public static DocBlock heading(int level, String text) {
        return new DocBlock(DocBlockType.HEADING, level, text, new ArrayList<>(), new ArrayList<>());
    }

    /**
     * 构造段落块。
     *
     * @param text 段落文本，非空
     * @return 段落块
     */
    public static DocBlock paragraph(String text) {
        return new DocBlock(DocBlockType.PARAGRAPH, 0, text, new ArrayList<>(), new ArrayList<>());
    }

    /**
     * 构造表格块。
     *
     * @param header 表头行（Markdown 管道格式，含分隔行），可为空列表
     * @param rows   数据行（Markdown 管道格式），非空
     * @return 表格块
     */
    public static DocBlock table(List<String> header, List<String> rows) {
        return new DocBlock(DocBlockType.TABLE, 0, null, new ArrayList<>(header), new ArrayList<>(rows));
    }

    /**
     * 表格按 Markdown 文本输出时的总字符数（表头 + 全部行，含换行）。
     *
     * @return 字符数；非表格块返回文本长度
     */
    public int textLength() {
        if (type != DocBlockType.TABLE) {
            return text == null ? 0 : text.length();
        }
        int len = 0;
        for (String h : tableHeader) {
            len += h.length() + 1;
        }
        for (String r : tableRows) {
            len += r.length() + 1;
        }
        return len;
    }
}
