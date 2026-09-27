package com.cl.agent.rag.core;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 批量向量化客户端（OpenAI 兼容协议）。
 * <p>使用说明：入库流水线调用 {@link #embedAll}，每批一次 HTTP 请求（{@code input} 为字符串数组），
 * 取代逐片调用 Embedding 接口。某一批请求失败（如厂商不支持数组输入）时，该批自动退化为逐条请求，
 * 逐条仍失败才抛出异常。</p>
 * <p>本类无状态、线程安全；每次调用新建并关闭 HTTP 客户端，适合入库这类低频批处理场景。</p>
 */
@Slf4j
public class BatchEmbeddingClient {

    /** 单次请求超时时间；批量请求比单条慢，放宽到 60 秒 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** 单批最大条数的兜底值，配置非法时使用 */
    private static final int FALLBACK_BATCH_SIZE = 10;

    /**
     * 批量计算文本向量。
     *
     * @param spec      向量模型参数，baseUrl / apiKey / modelName / dimensions 必填
     * @param texts     待向量化文本，非空元素；可为空列表
     * @param batchSize 每批条数，非正数时使用 10；通义 text-embedding-v3 上限为 10，v2 为 25
     * @return 与入参顺序一一对应的向量；入参为空时返回空列表
     * @throws IllegalStateException 调用失败，或返回的向量数量、维度与预期不一致时抛出
     */
    public List<double[]> embedAll(EmbeddingModelSpec spec, List<String> texts, int batchSize) {
        List<double[]> result = new ArrayList<>(texts.size());
        if (texts.isEmpty()) {
            return result;
        }
        int size = batchSize > 0 ? batchSize : FALLBACK_BATCH_SIZE;
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(spec.getApiKey())
                .baseUrl(spec.getBaseUrl())
                .timeout(REQUEST_TIMEOUT)
                .maxRetries(2)
                .build();
        try {
            for (int from = 0; from < texts.size(); from += size) {
                List<String> batch = texts.subList(from, Math.min(from + size, texts.size()));
                result.addAll(embedBatchWithFallback(client, spec, batch));
            }
        } finally {
            client.close();
        }
        log.info("[RAG-Embedding] 批量向量化完成: model={}, 条数={}, 批大小={}, 请求批次={}",
                spec.getModelName(), texts.size(), size, (texts.size() + size - 1) / size);
        return result;
    }

    /**
     * 请求一批；失败且批大小大于 1 时退化为逐条请求。
     */
    private List<double[]> embedBatchWithFallback(OpenAIClient client, EmbeddingModelSpec spec, List<String> batch) {
        try {
            return request(client, spec, batch);
        } catch (RuntimeException e) {
            if (batch.size() == 1) {
                throw e;
            }
            log.warn("[RAG-Embedding] 批量请求失败，改为逐条请求: model={}, 批大小={}, reason={}",
                    spec.getModelName(), batch.size(), e.getMessage());
            List<double[]> vectors = new ArrayList<>(batch.size());
            for (String text : batch) {
                vectors.addAll(request(client, spec, List.of(text)));
            }
            return vectors;
        }
    }

    /**
     * 发起一次 /embeddings 请求，并按返回的 index 还原顺序、校验维度。
     */
    private List<double[]> request(OpenAIClient client, EmbeddingModelSpec spec, List<String> batch) {
        EmbeddingCreateParams.Builder builder = EmbeddingCreateParams.builder()
                .model(spec.getModelName())
                .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT)
                .inputOfArrayOfStrings(batch);
        if (spec.isSendDimensions()) {
            builder.dimensions(spec.getDimensions());
        }
        CreateEmbeddingResponse response = client.embeddings().create(builder.build());
        List<Embedding> data = new ArrayList<>(response.data());
        if (data.size() != batch.size()) {
            throw new IllegalStateException("Embedding 返回数量与请求不一致: 请求 " + batch.size() + " 条，返回 " + data.size() + " 条");
        }
        data.sort(Comparator.comparingLong(Embedding::index));
        List<double[]> vectors = new ArrayList<>(data.size());
        for (Embedding e : data) {
            List<Float> values = e.embedding();
            if (values.size() != spec.getDimensions()) {
                throw new IllegalStateException("Embedding 维度与模型配置不一致: 配置 " + spec.getDimensions()
                        + "，实际 " + values.size() + "，请在模型管理中修正维度");
            }
            double[] vector = new double[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = values.get(i);
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
