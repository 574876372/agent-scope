package com.cl.agent.biz.rag;

/**
 * 切片向量化文本构建器。
 * <p>方案 5.2「章节路径增强」：向量化时使用「文档名 › 章节路径 + 换行 + 正文」计算向量，
 * 让表格中间行这类自身没有标题的切片也能被主题相关的问题命中；数据库与展示仍保存原始正文。
 * 入库与内存库预热必须使用同一规则，否则同一切片在不同存储中的向量不一致。</p>
 */
public final class EmbeddingTextBuilder {

    /** 路径分隔符，与切片章节路径一致 */
    private static final String SEPARATOR = " › ";

    private EmbeddingTextBuilder() {
    }

    /**
     * 构建用于计算向量的文本。
     *
     * @param docName     文档文件名，可为空
     * @param sectionPath 章节路径，可为空（存量切片）
     * @param content     切片原文，非空
     * @return 带路径前缀的文本；文档名与章节路径都为空时返回原文
     */
    public static String build(String docName, String sectionPath, String content) {
        StringBuilder prefix = new StringBuilder();
        if (docName != null && !docName.isBlank()) {
            prefix.append(stripExtension(docName.strip()));
        }
        if (sectionPath != null && !sectionPath.isBlank()) {
            if (prefix.length() > 0) {
                prefix.append(SEPARATOR);
            }
            prefix.append(sectionPath.strip());
        }
        return prefix.length() == 0 ? content : prefix + "\n" + content;
    }

    /**
     * 去掉文件扩展名，扩展名对语义没有帮助。
     */
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
