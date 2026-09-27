package com.cl.agent.biz.rag;

import com.cl.agent.dto.CreateKbRequest;
import com.cl.agent.dto.KbResponse;
import com.cl.agent.dto.rag.KbTypeOptionResponse;
import com.cl.agent.enums.ChunkStrategyEnum;
import com.cl.agent.enums.KbTypePreset;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.rag.chunk.ChunkOptions;
import com.cl.agent.rag.properties.AgentRagProperties;
import com.cl.agent.rag.properties.RetrievalProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库切片与检索参数解析器。
 * <p>统一实现方案第八节的配置层级：知识库级配置 → 类型预设（{@link KbTypePreset}）→ yml 全局默认（{@code agent.rag.*}），
 * 供入库切片、检索上下文扩展、知识库详情展示三处共用，保证各处看到的生效值一致。</p>
 */
@Component
public class KbConfigResolver {

    /** 切片大小允许的最小值（字符） */
    private static final int MIN_CHUNK_SIZE = 100;

    /** 切片大小允许的最大值（字符） */
    private static final int MAX_CHUNK_SIZE = 4000;

    /** 上下文扩展窗口允许的最大值（前后各 N 片） */
    private static final int MAX_CONTEXT_WINDOW = 5;

    /** 配置缺失时的切片大小兜底值 */
    private static final int FALLBACK_CHUNK_SIZE = 512;

    /** 配置缺失时的重叠字符数兜底值 */
    private static final int FALLBACK_CHUNK_OVERLAP = 80;

    /** RAG 配置；RAG 模块关闭时为 null，此时使用兜底值 */
    @Autowired(required = false)
    private AgentRagProperties ragProperties;

    /**
     * 解析知识库的切片参数。
     *
     * @param kb 知识库实体，非空
     * @return 切片参数，各项均已解析为生效值
     */
    public ChunkOptions chunkOptions(KnowledgeBase kb) {
        KbTypePreset preset = KbTypePreset.of(kb.getKbType());
        return ChunkOptions.builder()
                .strategy(chunkStrategy(kb))
                .chunkSize(chunkSize(kb))
                .chunkOverlap(chunkOverlap(kb))
                .keepTableWhole(preset.isKeepTableWhole())
                .build();
    }

    /**
     * 生效的切片策略：知识库级 → 类型预设。
     *
     * @param kb 知识库实体，非空
     * @return 切片策略，非空
     */
    public ChunkStrategyEnum chunkStrategy(KnowledgeBase kb) {
        ChunkStrategyEnum s = ChunkStrategyEnum.ofNullable(kb.getChunkStrategy());
        return s != null ? s : KbTypePreset.of(kb.getKbType()).getChunkStrategy();
    }

    /**
     * 生效的切片大小：知识库级 → 类型预设 → {@code agent.rag.chunk-size}。
     *
     * @param kb 知识库实体，非空
     * @return 切片大小（字符），正数
     */
    public int chunkSize(KnowledgeBase kb) {
        if (kb.getChunkSize() != null && kb.getChunkSize() > 0) {
            return kb.getChunkSize();
        }
        Integer preset = KbTypePreset.of(kb.getKbType()).getChunkSize();
        return preset != null ? preset : globalChunkSize();
    }

    /**
     * 生效的重叠字符数：知识库级 → {@code agent.rag.chunk-overlap}；不超过切片大小的一半。
     *
     * @param kb 知识库实体，非空
     * @return 重叠字符数，非负
     */
    public int chunkOverlap(KnowledgeBase kb) {
        int overlap = kb.getChunkOverlap() != null && kb.getChunkOverlap() >= 0
                ? kb.getChunkOverlap()
                : (ragProperties != null ? ragProperties.getChunkOverlap() : FALLBACK_CHUNK_OVERLAP);
        return Math.min(overlap, chunkSize(kb) / 2);
    }

    /**
     * 生效的上下文扩展窗口：知识库级 → 类型预设。
     *
     * @param kb 知识库实体；为 null 时（知识库已删除）按通用文档处理
     * @return 命中后前后各补充的切片数，0 表示不扩展
     */
    public int contextWindow(KnowledgeBase kb) {
        if (kb != null && kb.getContextWindow() != null && kb.getContextWindow() >= 0) {
            return kb.getContextWindow();
        }
        return KbTypePreset.of(kb == null ? null : kb.getKbType()).getContextWindow();
    }

    /**
     * 该知识库是否在章节不超上限时整章带入（技术 / 接口文档类型）。
     *
     * @param kb 知识库实体，可为 null
     * @return true 表示优先整章带入
     */
    public boolean wholeSection(KnowledgeBase kb) {
        return kb != null && KbTypePreset.of(kb.getKbType()).isWholeSection();
    }

    /**
     * 检索流水线全局默认参数。
     *
     * @return {@code agent.rag.retrieval.*}；RAG 模块关闭时返回默认值实例
     */
    public RetrievalProperties retrieval() {
        return ragProperties != null ? ragProperties.getRetrieval() : new RetrievalProperties();
    }

    /**
     * 全局向量预过滤阈值。
     *
     * @return {@code agent.rag.default-score-threshold}
     */
    public double defaultScoreThreshold() {
        return ragProperties != null ? ragProperties.getDefaultScoreThreshold() : 0.3;
    }

    /**
     * 列出全部知识库类型预设及其生效默认参数，供前端选择。
     *
     * @return 类型选项列表，顺序与枚举定义一致
     */
    public List<KbTypeOptionResponse> listTypeOptions() {
        List<KbTypeOptionResponse> list = new ArrayList<>();
        for (KbTypePreset preset : KbTypePreset.values()) {
            KnowledgeBase probe = KnowledgeBase.builder().kbType(preset.name()).build();
            KbTypeOptionResponse opt = new KbTypeOptionResponse();
            opt.setCode(preset.name());
            opt.setLabel(preset.getLabel());
            opt.setChunkStrategy(preset.getChunkStrategy().name());
            opt.setChunkStrategyLabel(preset.getChunkStrategy().getLabel());
            opt.setChunkSize(chunkSize(probe));
            opt.setChunkOverlap(chunkOverlap(probe));
            opt.setContextWindow(preset.getContextWindow());
            opt.setWholeSection(preset.isWholeSection());
            opt.setDescription(describe(preset));
            list.add(opt);
        }
        return list;
    }

    /**
     * 在知识库详情响应中填充配置原值与生效值。
     *
     * @param resp 待填充的响应，非空
     * @param kb   知识库实体，非空
     * @return 无返回值；直接修改 resp
     */
    public void fillConfig(KbResponse resp, KnowledgeBase kb) {
        KbTypePreset preset = KbTypePreset.of(kb.getKbType());
        resp.setKbType(preset.name());
        resp.setKbTypeLabel(preset.getLabel());
        resp.setChunkStrategy(kb.getChunkStrategy());
        resp.setChunkSize(kb.getChunkSize());
        resp.setChunkOverlap(kb.getChunkOverlap());
        resp.setContextWindow(kb.getContextWindow());
        resp.setEffectiveChunkStrategy(chunkStrategy(kb).name());
        resp.setEffectiveChunkSize(chunkSize(kb));
        resp.setEffectiveChunkOverlap(chunkOverlap(kb));
        resp.setEffectiveContextWindow(contextWindow(kb));
    }

    /**
     * 校验并把请求中的类型与切片参数写入实体；空值表示「使用类型预设」。
     *
     * @param kb      目标实体，非空
     * @param request 创建 / 更新请求，非空
     * @return 无返回值；直接修改 kb
     * @throws BizException 类型、策略或数值超出允许范围时抛出，code=400
     */
    public void applyConfig(KnowledgeBase kb, CreateKbRequest request) {
        String type = request.getKbType();
        if (type != null && !type.isBlank()) {
            if (!KbTypePreset.of(type).name().equalsIgnoreCase(type.trim())) {
                throw new BizException(400, "不支持的知识库类型: " + type);
            }
            kb.setKbType(KbTypePreset.of(type).name());
        } else if (kb.getKbType() == null) {
            kb.setKbType(KbTypePreset.GENERAL.name());
        }
        String strategy = request.getChunkStrategy();
        if (strategy != null && !strategy.isBlank() && ChunkStrategyEnum.ofNullable(strategy) == null) {
            throw new BizException(400, "不支持的切片策略: " + strategy);
        }
        kb.setChunkStrategy(strategy == null || strategy.isBlank() ? null : ChunkStrategyEnum.ofNullable(strategy).name());

        Integer size = request.getChunkSize();
        if (size != null && (size < MIN_CHUNK_SIZE || size > MAX_CHUNK_SIZE)) {
            throw new BizException(400, "切片大小须在 " + MIN_CHUNK_SIZE + " ~ " + MAX_CHUNK_SIZE + " 之间");
        }
        kb.setChunkSize(size);

        Integer overlap = request.getChunkOverlap();
        if (overlap != null && (overlap < 0 || overlap > chunkSize(kb) / 2)) {
            throw new BizException(400, "重叠字符数须在 0 ~ 切片大小的一半之间");
        }
        kb.setChunkOverlap(overlap);

        Integer window = request.getContextWindow();
        if (window != null && (window < 0 || window > MAX_CONTEXT_WINDOW)) {
            throw new BizException(400, "上下文扩展窗口须在 0 ~ " + MAX_CONTEXT_WINDOW + " 之间");
        }
        kb.setContextWindow(window);
    }

    /**
     * 判断两份配置在切片层面是否不同（不同时已入库文档需重新解析才能生效）。
     *
     * @param before 修改前实体，非空
     * @param after  修改后实体，非空
     * @return true 表示切片参数发生了变化
     */
    public boolean chunkingChanged(KnowledgeBase before, KnowledgeBase after) {
        return chunkStrategy(before) != chunkStrategy(after)
                || chunkSize(before) != chunkSize(after)
                || chunkOverlap(before) != chunkOverlap(after)
                || KbTypePreset.of(before.getKbType()).isKeepTableWhole() != KbTypePreset.of(after.getKbType()).isKeepTableWhole();
    }

    private int globalChunkSize() {
        return ragProperties != null && ragProperties.getChunkSize() > 0 ? ragProperties.getChunkSize() : FALLBACK_CHUNK_SIZE;
    }

    private static String describe(KbTypePreset preset) {
        switch (preset) {
            case TECH_DOC:
                return "按章节切分，表格整体保留（超长按行拆分并重复表头）；命中后所在章节不超上限时整章带入，否则前后各 2 片";
            case FAQ:
                return "一问一答为一片，不做上下文扩展";
            case TABLE:
                return "按行分组，每组重复表头；命中后补充前后各 1 片";
            case GENERAL:
            default:
                return "按标题章节切分，章节内按段落累积；命中后补充前后各 1 片";
        }
    }
}
