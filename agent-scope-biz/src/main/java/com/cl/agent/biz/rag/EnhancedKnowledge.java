package com.cl.agent.biz.rag;

import com.cl.agent.dto.rag.RetrievalOptions;
import com.cl.agent.dto.rag.RetrievalSegment;
import com.cl.agent.dto.rag.RetrievalTrace;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * 增强检索器：实现 AgentScope 的 {@link Knowledge} 接口，内部调用 {@link RetrievalPipeline}。
 * <p>使用说明：每个智能体构建一个实例，覆盖其绑定的全部知识库，仅传入一次 {@code ReActAgent.Builder#knowledges}；
 * 多知识库检索与融合在流水线内部完成。当前用于 AGENTIC 模式——框架把它包装为 {@code retrieve_knowledge} 工具，
 * 由模型自行决定检索词，因此这里不再做查询改写。返回的每个 {@link Document} 对应一段来源，
 * 正文首行带「来源：文档名 › 章节 › 切片 #a-#b」，工具结果据此即可标注出处。</p>
 * <p>只读：入库由宿主的入库流水线完成，{@link #addDocuments} 不支持。</p>
 */
@Slf4j
public class EnhancedKnowledge implements Knowledge {

    /** 工具调用未指定或指定过大段数时的上限，避免一次注入过多内容 */
    private static final int MAX_TOOL_TOP_K = 10;

    /** 检索流水线，Spring 单例 */
    private final RetrievalPipeline pipeline;

    /** 智能体级检索参数模板（知识库、段数、上下文总长、阈值、重排模型）；每次检索复制后填入检索词 */
    private final RetrievalOptions template;

    /**
     * 构造增强检索器。
     *
     * @param pipeline 检索流水线，非空
     * @param template 智能体级检索参数模板，{@code kbIds} 非空；{@code query} 与 {@code history} 会被忽略
     */
    public EnhancedKnowledge(RetrievalPipeline pipeline, RetrievalOptions template) {
        this.pipeline = pipeline;
        this.template = template;
    }

    /**
     * 不支持写入：知识库文档由宿主入库流水线（解析、结构化切片、批量向量化）写入。
     *
     * @param documents 忽略
     * @return 以 {@link UnsupportedOperationException} 结束的 Mono
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(new UnsupportedOperationException("EnhancedKnowledge 为只读检索器，请通过知识库上传文档"));
    }

    /**
     * 执行增强检索，每段来源转为一个 Document。
     * <p>检索在 boundedElastic 线程池中执行（内部有阻塞的模型与数据库调用），不占用响应式事件线程。</p>
     *
     * @param query  检索词，由模型通过工具参数给出
     * @param config 框架传入的检索配置；{@code limit} 作为本次最终段数（不超过 10），其余参数使用智能体配置
     * @return 来源段列表，按引用编号排列；无结果时为空列表
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        return Mono.fromCallable(() -> {
            RetrievalOptions options = RetrievalOptions.builder()
                    .kbIds(template.getKbIds())
                    .query(query)
                    .finalTopK(resolveTopK(config))
                    .contextMaxChars(template.getContextMaxChars())
                    .scoreThreshold(template.getScoreThreshold())
                    .queryRewrite(false)
                    .rerankModelId(template.getRerankModelId())
                    .build();
            RetrievalTrace trace = pipeline.run(options);
            if (!trace.getWarnings().isEmpty()) {
                log.warn("[RAG-Agentic] 检索告警: {}", trace.getWarnings());
            }
            return toDocuments(trace.getSegments());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 智能体配置了最终段数时以其为准（框架工具在模型未传 limit 时固定传 5，无法区分是否为模型指定）；
     * 未配置时使用工具传入的 limit，不超过上限；两者都没有时交给流水线取全局默认。
     */
    private Integer resolveTopK(RetrieveConfig config) {
        if (template.getFinalTopK() != null) {
            return template.getFinalTopK();
        }
        if (config != null && config.getLimit() > 0) {
            return Math.min(config.getLimit(), MAX_TOOL_TOP_K);
        }
        return null;
    }

    /**
     * 把来源段转为框架的 Document：正文首行为来源标注，得分为段内最高得分。
     */
    static List<Document> toDocuments(List<RetrievalSegment> segments) {
        List<Document> docs = new ArrayList<>(segments.size());
        for (RetrievalSegment s : segments) {
            String text = "[" + s.getCitation() + "] 来源：" + s.sourceLabel() + "\n" + s.getContent();
            DocumentMetadata meta = DocumentMetadata.builder()
                    .docId(s.getDocId())
                    .chunkId(s.getStartIndex() + "-" + s.getEndIndex())
                    .content(TextBlock.builder().text(text).build())
                    .build();
            Document doc = new Document(meta);
            doc.setScore(s.getScore());
            docs.add(doc);
        }
        return docs;
    }
}
