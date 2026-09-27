package com.cl.agent.rag;

import com.cl.agent.enums.ChunkStrategyEnum;
import com.cl.agent.rag.chunk.ChunkOptions;
import com.cl.agent.rag.chunk.ChunkPiece;
import com.cl.agent.rag.chunk.DocBlock;
import com.cl.agent.rag.chunk.DocBlockType;
import com.cl.agent.rag.chunk.DocumentStructureReader;
import com.cl.agent.rag.chunk.StructuredChunker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化切片与文档结构还原单元测试。
 * <p>重点覆盖方案中的触发问题：长参数表不能从行中间切断，拆分后每片都要带表头与章节路径。</p>
 */
public class StructuredChunkerTest {

    /** 被测结构还原器 */
    private final DocumentStructureReader reader = new DocumentStructureReader();

    /** 被测切片器 */
    private final StructuredChunker chunker = new StructuredChunker();

    /**
     * 构造一份类似「独立代发接口文档」的 Markdown：章节 + 70 行请求参数表 + 响应参数表。
     */
    private static String interfaceDoc() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 支付平台接口文档\n\n");
        sb.append("## 三、独立代发\n\n独立代发接口用于向个人或企业账户付款。\n\n");
        sb.append("### 请求参数\n\n请求参数如下：\n\n");
        sb.append("| 序号 | 字段名 | 类型 | 说明 |\n|---|---|---|---|\n");
        for (int i = 1; i <= 70; i++) {
            sb.append("| ").append(i).append(" | field").append(i).append(" | String(32) | 第").append(i).append("个参数的说明文字 |\n");
        }
        sb.append("\n### 响应参数\n\n| 字段名 | 说明 |\n|---|---|\n| partnerOutBizNo | 商户订单号 |\n| resultCode | 错误码，如 BBLMAP00000 |\n");
        return sb.toString();
    }

    /**
     * 长表格按行拆分：不切断任何一行、每片重复表头、章节路径完整、全部 70 行都在。
     */
    @Test
    public void longTableSplitsOnRowsWithRepeatedHeader() {
        List<DocBlock> blocks = reader.parseMarkdown(interfaceDoc());
        List<ChunkPiece> pieces = chunker.chunk(blocks, ChunkOptions.builder()
                .strategy(ChunkStrategyEnum.SECTION).chunkSize(512).chunkOverlap(80).keepTableWhole(false).build());

        List<ChunkPiece> requestTable = pieces.stream()
                .filter(p -> ChunkPiece.TYPE_TABLE.equals(p.getChunkType()) && p.getSectionPath().endsWith("请求参数"))
                .collect(Collectors.toList());
        assertTrue(requestTable.size() > 1, "70 行参数表应被拆成多片");
        for (ChunkPiece p : requestTable) {
            assertEquals("支付平台接口文档 › 三、独立代发 › 请求参数", p.getSectionPath());
            assertTrue(p.getContent().contains("| 序号 | 字段名 | 类型 | 说明 |"), "每片都应重复表头");
            for (String line : p.getContent().split("\n")) {
                if (line.startsWith("|")) {
                    assertTrue(line.endsWith("|"), "表格行不应被切断: " + line);
                }
            }
        }
        String all = requestTable.stream().map(ChunkPiece::getContent).collect(Collectors.joining("\n"));
        for (int i = 1; i <= 70; i++) {
            assertTrue(all.contains("| field" + i + " |"), "缺少第 " + i + " 行");
        }
        // 表格前的短说明并入首片，不单独成片
        assertTrue(requestTable.get(0).getContent().startsWith("请求参数"));
    }

    /**
     * 技术文档预设：表格上限放宽到 2 倍，短表整体保留；响应参数单独一片且章节路径正确。
     */
    @Test
    public void sectionsAreNotMixedAndShortTableKeptWhole() {
        List<ChunkPiece> pieces = chunker.chunk(reader.parseMarkdown(interfaceDoc()), ChunkOptions.builder()
                .strategy(ChunkStrategyEnum.SECTION).chunkSize(1000).chunkOverlap(0).keepTableWhole(true).build());
        ChunkPiece response = pieces.stream().filter(p -> p.getSectionPath().endsWith("响应参数")).findFirst().orElseThrow();
        assertEquals(ChunkPiece.TYPE_TABLE, response.getChunkType());
        assertTrue(response.getContent().contains("partnerOutBizNo"));
        assertFalse(response.getContent().contains("field70"), "不同章节的内容不应混在同一片");
        ChunkPiece intro = pieces.get(0);
        assertEquals(ChunkPiece.TYPE_TEXT, intro.getChunkType());
        assertTrue(intro.getContent().startsWith("三、独立代发"), "章节首片应以章节标题开头");
    }

    /**
     * 超长段落按句拆分并保留重叠，每片不超过上限。
     */
    @Test
    public void longParagraphIsSplitBySentence() {
        String sentence = "这是一句用于测试按句拆分的中文句子。";
        String text = "# 标题\n\n" + sentence.repeat(40);
        List<ChunkPiece> pieces = chunker.chunk(reader.parseMarkdown(text), ChunkOptions.builder()
                .strategy(ChunkStrategyEnum.SECTION).chunkSize(200).chunkOverlap(20).build());
        assertTrue(pieces.size() > 1);
        pieces.forEach(p -> assertTrue(p.getContent().length() <= 200, "切片超过上限: " + p.getContent().length()));
        assertTrue(pieces.get(0).getContent().startsWith("标题"), "标题行应保留在首片");
    }

    /**
     * FAQ：一问一答为一片，问题前的说明单独成片；问题少于两个时退化为按章节切分。
     */
    @Test
    public void faqSplitsIntoQuestionAnswerPairs() {
        String faq = "常见问题说明\n\nQ: 如何开通代发？\nA: 联系客户经理提交申请。\n\nQ: 代发失败怎么办？\nA: 查看错误码并重试。\n补充说明第二行。";
        List<ChunkPiece> pieces = chunker.chunk(reader.parsePlainText(faq), ChunkOptions.builder()
                .strategy(ChunkStrategyEnum.QA).chunkSize(300).build());
        List<ChunkPiece> qa = pieces.stream().filter(p -> ChunkPiece.TYPE_QA.equals(p.getChunkType())).collect(Collectors.toList());
        assertEquals(2, qa.size());
        assertTrue(qa.get(1).getContent().startsWith("Q: 代发失败怎么办？"));
        assertTrue(qa.get(1).getContent().contains("补充说明第二行"));
        assertEquals("常见问题说明", pieces.get(0).getContent());

        List<ChunkPiece> fallback = chunker.chunk(reader.parsePlainText("只有一段普通文字，没有问答。"),
                ChunkOptions.builder().strategy(ChunkStrategyEnum.QA).chunkSize(300).build());
        assertEquals(ChunkPiece.TYPE_TEXT, fallback.get(0).getChunkType());
    }

    /**
     * 纯文本的中文编号标题推断，单级数字列表项不应被当作标题。
     */
    @Test
    public void plainTextHeadingInference() {
        List<DocBlock> blocks = reader.parsePlainText("第一章 总则\n\n一、适用范围\n\n本办法适用于全部门。\n\n1.1 名词解释\n\n1. 这是列表项\n2. 这也是列表项");
        List<DocBlock> headings = blocks.stream().filter(b -> b.getType() == DocBlockType.HEADING).collect(Collectors.toList());
        assertEquals(List.of("第一章 总则", "一、适用范围", "1.1 名词解释"),
                headings.stream().map(DocBlock::getText).collect(Collectors.toList()));
        assertTrue(headings.get(0).getLevel() < headings.get(1).getLevel());
        assertTrue(headings.get(1).getLevel() < headings.get(2).getLevel());
    }

    /**
     * Markdown 围栏代码块内的 # 与 | 不做结构识别；缺空格的「##标题」也能识别。
     */
    @Test
    public void markdownFenceAndHeadingWithoutSpace() {
        List<DocBlock> blocks = reader.parseMarkdown("##四、示例\n\n```bash\n# 注释\n| 不是表格 |\n```\n");
        assertEquals(DocBlockType.HEADING, blocks.get(0).getType());
        assertEquals("四、示例", blocks.get(0).getText());
        assertEquals(2, blocks.size());
        assertEquals(DocBlockType.PARAGRAPH, blocks.get(1).getType());
    }

    /**
     * 通过文件入口读取 GBK 编码的 TXT。
     */
    @Test
    public void readsGbkTextFile() throws Exception {
        Path file = Files.createTempFile("rag_gbk", ".txt");
        try {
            Files.write(file, "一、概述\n\n这是 GBK 编码的内容。".getBytes("GBK"));
            List<DocBlock> blocks = reader.read(file, "txt");
            assertEquals("一、概述", blocks.get(0).getText());
            assertEquals("这是 GBK 编码的内容。", blocks.get(1).getText());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
