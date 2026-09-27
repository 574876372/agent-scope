package com.cl.agent.biz.rag;

import com.cl.agent.dto.rag.RetrievalHit;
import com.cl.agent.dto.rag.RetrievalSegment;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.service.IKnowledgeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 检索流水线纯逻辑单元测试：RRF 融合、上下文扩展与合并、相邻切片去重、查询改写输出清洗、重排响应解析。
 * <p>不依赖数据库与模型：切片查询用 Mockito 模拟。</p>
 */
public class RetrievalPipelineLogicTest {

    private static RetrievalHit hit(String kbId, String docId, int idx, Integer vectorRank, Integer keywordRank) {
        RetrievalHit h = new RetrievalHit();
        h.setKbId(kbId);
        h.setDocId(docId);
        h.setDocName(docId + ".md");
        h.setChunkIndex(idx);
        h.setContent("chunk-" + idx);
        h.setVectorRank(vectorRank);
        h.setKeywordRank(keywordRank);
        return h;
    }

    private static KnowledgeChunk chunk(String docId, int idx, String content, String section) {
        KnowledgeChunk c = new KnowledgeChunk();
        c.setId(docId + "-" + idx);
        c.setDocId(docId);
        c.setChunkIndex(idx);
        c.setContent(content);
        c.setSectionPath(section);
        return c;
    }

    /**
     * 两路都命中的切片融合得分最高；融合只看排名，不看原始分数。
     */
    @Test
    public void rrfPrefersChunksHitByBothRoutes() {
        Map<String, List<RetrievalHit>> vector = new LinkedHashMap<>();
        vector.put("kb1", List.of(hit("kb1", "d1", 0, 1, null), hit("kb1", "d1", 5, 2, null)));
        List<RetrievalHit> keyword = List.of(hit("kb1", "d1", 5, null, 1), hit("kb1", "d2", 3, null, 2));

        List<RetrievalHit> fused = RetrievalPipeline.fuse(vector, keyword, 60);

        assertEquals(3, fused.size());
        assertEquals("d1#5", fused.get(0).key(), "向量第 2 + 关键词第 1 应排第一");
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get(0).getFusedScore(), 1e-12);
        assertEquals(1, fused.get(0).getKeywordRank());
        assertEquals(2, fused.get(0).getVectorRank());
    }

    /**
     * 相邻切片合并时去掉旧版固定长度切片的重叠文字，以及拆分表格重复的表头。
     */
    @Test
    public void joinChunksRemovesOverlapAndRepeatedHeader() {
        String a = "第一段正文，这里是结尾的重叠部分ABCDEFGHIJ";
        String b = "结尾的重叠部分ABCDEFGHIJ之后的新内容";
        assertEquals("第一段正文，这里是结尾的重叠部分ABCDEFGHIJ之后的新内容", ContextExpander.joinChunks(List.of(a, b)));

        String t1 = "请求参数：\n| 序号 | 字段 |\n|---|---|\n| 1 | a |";
        String t2 = "| 序号 | 字段 |\n|---|---|\n| 2 | b |";
        assertEquals("请求参数：\n| 序号 | 字段 |\n|---|---|\n| 1 | a |\n| 2 | b |", ContextExpander.joinChunks(List.of(t1, t2)));
    }

    /**
     * 上下文扩展：通用文档补前后各 1 片，相邻命中合并为一段；FAQ 不扩展；超出 Top-K 的低排名命中被舍弃。
     */
    @Test
    public void expandMergesNeighboursAndRespectsTopK() {
        IKnowledgeService ks = Mockito.mock(IKnowledgeService.class);
        Mockito.when(ks.listChunksInRange(anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            String docId = inv.getArgument(0);
            int from = Math.max(0, inv.getArgument(1));
            int to = Math.min(9, (int) inv.getArgument(2));
            List<KnowledgeChunk> list = new ArrayList<>();
            for (int i = from; i <= to; i++) {
                list.add(chunk(docId, i, docId + "-段落" + i, "第一章"));
            }
            return list;
        });
        ContextExpander expander = newExpander(ks);

        Map<String, KnowledgeBase> kbMap = Map.of(
                "general", KnowledgeBase.builder().id("general").kbType("GENERAL").build(),
                "faq", KnowledgeBase.builder().id("faq").kbType("FAQ").build());
        List<RetrievalHit> ranked = List.of(
                hit("general", "doc", 4, 1, null),
                hit("faq", "qa", 7, 2, null),
                hit("general", "doc", 6, 3, null),
                hit("general", "other", 1, 4, null));
        ranked.forEach(h -> h.setFusedScore(1.0 / (60 + h.getVectorRank())));

        List<RetrievalSegment> segs = expander.expand(ranked, kbMap, 2, 8000, 4000);

        assertEquals(2, segs.size(), "Top-K=2，排名第 4 的 other 文档应被舍弃");
        RetrievalSegment doc = segs.get(0);
        assertEquals("doc", doc.getDocId());
        assertEquals(3, doc.getStartIndex());
        assertEquals(7, doc.getEndIndex(), "4±1 与 6±1 相连，合并为 3~7");
        assertEquals(List.of(4, 6), doc.getHitIndexes());
        assertEquals(1, doc.getCitation());
        RetrievalSegment faq = segs.get(1);
        assertEquals(7, faq.getStartIndex());
        assertEquals(7, faq.getEndIndex(), "FAQ 不扩展");
        assertEquals("none", faq.getExpandMode());
        assertTrue(doc.sourceLabel().endsWith("第一章 › 切片 #3-#7"));
    }

    /**
     * 技术文档：章节不超上限时整章带入；总长超出上限时先收缩到命中切片，排名第一的段仍过长时截断。
     */
    @Test
    public void techDocWholeSectionAndBudget() {
        IKnowledgeService ks = Mockito.mock(IKnowledgeService.class);
        List<KnowledgeChunk> section = List.of(
                chunk("api", 10, "请求参数表第一部分", "三、独立代发 › 请求参数"),
                chunk("api", 11, "请求参数表第二部分", "三、独立代发 › 请求参数"),
                chunk("api", 12, "请求参数表第三部分", "三、独立代发 › 请求参数"));
        Mockito.when(ks.listChunksBySection(eq("api"), eq("三、独立代发 › 请求参数"))).thenReturn(section);
        Mockito.when(ks.listChunksInRange(eq("api"), anyInt(), anyInt())).thenAnswer(inv -> {
            int from = inv.getArgument(1);
            int to = inv.getArgument(2);
            return section.stream().filter(c -> c.getChunkIndex() >= from && c.getChunkIndex() <= to).toList();
        });
        ContextExpander expander = newExpander(ks);
        Map<String, KnowledgeBase> kbMap = Map.of("tech", KnowledgeBase.builder().id("tech").kbType("TECH_DOC").build());
        RetrievalHit h = hit("tech", "api", 11, 1, null);
        h.setSectionPath("三、独立代发 › 请求参数");
        h.setFusedScore(0.1);

        RetrievalSegment whole = expander.expand(List.of(h), kbMap, 5, 8000, 4000).get(0);
        assertEquals("section", whole.getExpandMode());
        assertEquals(10, whole.getStartIndex());
        assertEquals(12, whole.getEndIndex());
        assertEquals("请求参数表第一部分\n请求参数表第二部分\n请求参数表第三部分", whole.getContent());

        RetrievalSegment trimmed = expander.expand(List.of(h), kbMap, 5, 12, 4000).get(0);
        assertEquals("trimmed", trimmed.getExpandMode());
        assertTrue(trimmed.getCharCount() <= 12 + "\n……（内容过长，已截断）".length());
    }

    /**
     * 查询改写输出清洗：去前缀与引号、只取首行、过长视为无效。
     */
    @Test
    public void rewriteOutputSanitizing() {
        assertEquals("独立代发接口的响应参数有哪些", QueryRewriter.sanitize("改写后的问题：「独立代发接口的响应参数有哪些」\n解释……", "那响应参数呢？"));
        assertNull(QueryRewriter.sanitize("   ", "q"));
        assertNull(QueryRewriter.sanitize("很长".repeat(200), "短问题"));
        String prompt = QueryRewriter.buildPrompt(List.<String[]>of(
                new String[]{"user", "代发接口的请求参数"},
                new String[]{"assistant", "<retrieval>{\"a\":1}</retrieval><think>想一想</think>Action: x\n请求参数如下"}), "那响应参数呢？");
        assertTrue(prompt.contains("用户：代发接口的请求参数"));
        assertTrue(prompt.contains("助手：请求参数如下"));
        assertFalse(prompt.contains("retrieval") || prompt.contains("想一想") || prompt.contains("Action"));
        assertTrue(prompt.endsWith("最新问题：那响应参数呢？"));
    }

    /**
     * 重排响应解析：按 index 还原顺序；地址补全 /rerank。
     */
    @Test
    public void rerankResponseParsing() {
        List<Double> scores = CohereCompatibleReranker.parseScores(
                "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.3}]}", 3);
        assertEquals(List.of(0.3, 0.0, 0.9), scores);
        assertEquals("https://api.siliconflow.cn/v1/rerank", CohereCompatibleReranker.endpoint("https://api.siliconflow.cn/v1/"));
        assertEquals("http://h/rerank", CohereCompatibleReranker.endpoint("http://h/rerank"));
    }

    /**
     * 向量化文本：文档名去扩展名 + 章节路径 + 正文；存量切片无路径时只拼文档名。
     */
    @Test
    public void embeddingTextPrefix() {
        assertEquals("接口文档 › 三、独立代发 › 请求参数\n| 1 | a |",
                EmbeddingTextBuilder.build("接口文档.docx", "三、独立代发 › 请求参数", "| 1 | a |"));
        assertEquals("接口文档\n正文", EmbeddingTextBuilder.build("接口文档.docx", null, "正文"));
        assertEquals("正文", EmbeddingTextBuilder.build(null, "", "正文"));
    }

    private static ContextExpander newExpander(IKnowledgeService ks) {
        ContextExpander expander = new ContextExpander();
        ReflectionTestUtils.setField(expander, "knowledgeService", ks);
        ReflectionTestUtils.setField(expander, "kbConfigResolver", new KbConfigResolver());
        return expander;
    }
}
