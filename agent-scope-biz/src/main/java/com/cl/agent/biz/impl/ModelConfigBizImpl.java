package com.cl.agent.biz.impl;

import com.cl.agent.biz.IModelConfigBiz;
import com.cl.agent.biz.event.ModelConfigChangedEvent;
import com.cl.agent.biz.rag.Reranker;
import com.cl.agent.dto.model.*;
import com.cl.agent.enums.ModelTypeEnum;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.ModelInfo;
import com.cl.agent.model.ModelProvider;
import com.cl.agent.rag.core.EmbeddingModelSpec;
import com.cl.agent.rag.core.EmbeddingStoreFactory;
import com.cl.agent.service.IModelConfigService;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.agentscope.core.message.TextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型配置管理业务编排实现。
 * <p>厂商或模型发生修改、删除后发布 {@link ModelConfigChangedEvent}，由智能体运行时缓存等订阅方清理旧客户端。</p>
 */
@Slf4j
@Service
public class ModelConfigBizImpl implements IModelConfigBiz {

    /** 连通性测试的请求超时 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    /** 连通性测试发送的文本 */
    private static final String TEST_TEXT = "连通性测试";

    @Autowired
    private IModelConfigService modelConfigService;

    /** RAG 未启用时为 null，此时无法测试向量模型 */
    @Autowired(required = false)
    private EmbeddingStoreFactory embeddingStoreFactory;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 重排实现，用于测试重排模型连通性 */
    @Autowired
    private Reranker reranker;

    // ======================== 厂商 ========================

    @Override
    public List<ModelProviderResponse> listProviders() {
        return modelConfigService.listProviders().stream()
                .map(this::toProviderResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ModelProviderResponse createProvider(ModelProviderRequest request) {
        return toProviderResponse(modelConfigService.createProvider(request));
    }

    @Override
    public ModelProviderResponse updateProvider(String id, ModelProviderRequest request) {
        request.setId(id);
        ModelProvider updated = modelConfigService.updateProvider(request);
        publishChanged();
        return toProviderResponse(updated);
    }

    @Override
    public void deleteProvider(String id) {
        modelConfigService.deleteProvider(id);
        publishChanged();
    }

    // ======================== 模型 ========================

    @Override
    public List<ModelInfoResponse> listModels(String modelType) {
        ModelTypeEnum type = (modelType == null || modelType.isBlank()) ? null : ModelTypeEnum.of(modelType);
        Map<String, ModelProvider> providers = providerMap();
        return modelConfigService.listModels(type).stream()
                .map(m -> toModelResponse(m, providers.get(m.getProviderId())))
                .collect(Collectors.toList());
    }

    @Override
    public ModelInfoResponse createModel(ModelInfoRequest request) {
        ModelInfo created = modelConfigService.createModel(request);
        return toModelResponse(created, modelConfigService.getProvider(created.getProviderId()));
    }

    @Override
    public ModelInfoResponse updateModel(String id, ModelInfoRequest request) {
        request.setId(id);
        ModelInfo updated = modelConfigService.updateModel(request);
        publishChanged();
        return toModelResponse(updated, modelConfigService.getProvider(updated.getProviderId()));
    }

    @Override
    public void deleteModel(String id) {
        modelConfigService.deleteModel(id);
        publishChanged();
    }

    @Override
    public Map<String, Object> testModel(String id) {
        ModelInfo model = modelConfigService.getModel(id);
        if (model == null) {
            throw new BizException(404, "模型不存在: " + id);
        }
        // 缺少密钥等配置问题直接以 BizException 返回给前端
        ModelConnection conn = modelConfigService.resolveConnection(id);
        try {
            if (ModelTypeEnum.EMBEDDING.name().equals(model.getModelType())) {
                return testEmbedding(conn);
            }
            if (ModelTypeEnum.RERANK.name().equals(model.getModelType())) {
                return testRerank(conn);
            }
            return testChat(conn);
        } catch (Exception e) {
            // 客户端的重试包装异常（如 "Retries exhausted"）信息量很少，取最底层原因展示给用户
            String reason = rootCauseMessage(e);
            log.info("[ModelConfig] 模型连通性测试失败: id={}, name={}, reason={}", id, model.getModelName(), reason);
            return result(false, "调用失败: " + reason);
        }
    }

    @Override
    public List<Map<String, Object>> listChatModelOptions() {
        Map<String, List<String>> modelsByProvider = modelConfigService.listModels(ModelTypeEnum.CHAT).stream()
                .filter(m -> Objects.equals(m.getEnabled(), 1))
                .collect(Collectors.groupingBy(ModelInfo::getProviderId, LinkedHashMap::new,
                        Collectors.mapping(ModelInfo::getModelName, Collectors.toList())));

        List<Map<String, Object>> options = new ArrayList<>();
        for (ModelProvider p : modelConfigService.listProviders()) {
            List<String> models = modelsByProvider.get(p.getId());
            if (!Objects.equals(p.getEnabled(), 1) || models == null || models.isEmpty()) {
                continue;
            }
            Map<String, Object> option = new HashMap<>();
            option.put("type", p.getCode());
            option.put("name", p.getName());
            option.put("models", models);
            options.add(option);
        }
        return options;
    }

    // ======================== 私有方法 ========================

    /**
     * 重排模型测试：对两段候选文本打分，校验接口返回了与候选数一致的相关度。
     *
     * @param conn 重排模型连接参数
     * @return 测试结果，{@code success} 与 {@code message}
     */
    private Map<String, Object> testRerank(ModelConnection conn) {
        List<Double> scores = reranker.score(conn, TEST_TEXT, List.of("这是一段关于连通性测试的说明", "今天天气晴朗"));
        return result(true, String.format("连接成功，测试相关度: %.3f / %.3f", scores.get(0), scores.get(1)));
    }

    /**
     * 向量模型测试：实际向量化一段文本，并校验返回维度与配置一致。
     */
    private Map<String, Object> testEmbedding(ModelConnection conn) {
        if (embeddingStoreFactory == null) {
            throw new BizException(400, "RAG 模块未启用，无法测试向量模型");
        }
        EmbeddingModelSpec spec = EmbeddingModelSpec.builder()
                .modelId(conn.getModelId())
                .protocol(conn.getProtocol())
                .baseUrl(conn.getBaseUrl())
                .apiKey(conn.getApiKey())
                .modelName(conn.getModelName())
                .dimensions(conn.getDimensions())
                .sendDimensions(conn.isSendDimensions())
                .version(conn.getVersion())
                .build();
        double[] vector = embeddingStoreFactory.createEmbeddingModel(spec)
                .embed(TextBlock.builder().text(TEST_TEXT).build())
                .block(TEST_TIMEOUT);
        int actual = vector == null ? 0 : vector.length;
        if (actual != conn.getDimensions()) {
            return result(false, "接口调用成功，但返回向量维度为 " + actual + "，与配置的 " + conn.getDimensions()
                    + " 不一致，请修正维度配置");
        }
        return result(true, "连接成功，返回向量维度 " + actual + " 与配置一致");
    }

    /**
     * 对话模型测试：发送一次最小请求（最多生成 1 个 token）。
     * <p>有意使用已废弃的 {@code max_tokens}：通义、DeepSeek 等 OpenAI 兼容接口普遍支持该参数，
     * 而新参数 {@code max_completion_tokens} 未必被兼容实现识别。</p>
     */
    @SuppressWarnings("deprecation")
    private Map<String, Object> testChat(ModelConnection conn) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(conn.getApiKey())
                .baseUrl(conn.getBaseUrl())
                .timeout(TEST_TIMEOUT)
                .maxRetries(0)
                .build();
        try {
            client.chat().completions().create(ChatCompletionCreateParams.builder()
                    .model(conn.getModelName())
                    .addUserMessage(TEST_TEXT)
                    .maxTokens(1)
                    .build());
            return result(true, "连接成功");
        } finally {
            client.close();
        }
    }

    private void publishChanged() {
        eventPublisher.publishEvent(new ModelConfigChangedEvent(this));
    }

    private Map<String, ModelProvider> providerMap() {
        return modelConfigService.listProviders().stream()
                .collect(Collectors.toMap(ModelProvider::getId, Function.identity()));
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> result(boolean success, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", success);
        map.put("message", message);
        return map;
    }

    private ModelProviderResponse toProviderResponse(ModelProvider p) {
        return ModelProviderResponse.builder()
                .id(p.getId())
                .code(p.getCode())
                .name(p.getName())
                .protocol(p.getProtocol())
                .baseUrl(p.getBaseUrl())
                .apiKeyConfigured(p.getApiKeyCipher() != null && !p.getApiKeyCipher().isBlank())
                .enabled(p.getEnabled())
                .createTime(p.getCreateTime())
                .updateTime(p.getUpdateTime())
                .build();
    }

    private ModelInfoResponse toModelResponse(ModelInfo m, ModelProvider provider) {
        boolean embedding = ModelTypeEnum.EMBEDDING.name().equals(m.getModelType());
        return ModelInfoResponse.builder()
                .id(m.getId())
                .providerId(m.getProviderId())
                .providerName(provider == null ? null : provider.getName())
                .modelType(m.getModelType())
                .modelName(m.getModelName())
                .dimensions(m.getDimensions())
                .sendDimensions(m.getSendDimensions())
                .isDefault(m.getIsDefault())
                .enabled(m.getEnabled())
                .boundKbCount(embedding ? modelConfigService.countBoundKnowledgeBases(m.getId()) : 0L)
                .createTime(m.getCreateTime())
                .updateTime(m.getUpdateTime())
                .build();
    }
}
