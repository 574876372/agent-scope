package com.cl.agent.biz.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.cl.agent.dto.model.ModelConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cohere / Jina 兼容格式的重排实现，调用 {@code POST {baseUrl}/rerank}。
 * <p>适用于硅基流动 bge-reranker、本地 Xinference / TEI 等部署；请求体为
 * {@code {model, query, documents, top_n}}，响应体为 {@code {results: [{index, relevance_score}]}}。
 * 通义 gte-rerank 为私有格式，暂不支持。</p>
 */
@Slf4j
@Component
public class CohereCompatibleReranker implements Reranker {

    /** 请求超时时间；超时即放弃重排，沿用融合排序 */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** 共享的 HTTP 客户端，线程安全 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** {@inheritDoc} */
    @Override
    public List<Double> score(ModelConnection conn, String query, List<String> documents) {
        if (documents.isEmpty()) {
            return new ArrayList<>();
        }
        JSONObject body = new JSONObject();
        body.put("model", conn.getModelName());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint(conn.getBaseUrl())))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + conn.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重排请求被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("重排请求失败: " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("重排接口返回 HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return parseScores(response.body(), documents.size());
    }

    /**
     * 解析响应中的相关度，按 index 还原到入参顺序；未返回的候选得分为 0。
     *
     * @param json  响应体
     * @param count 候选数量
     * @return 与候选一一对应的得分
     * @throws IllegalStateException 响应中没有 results 数组时抛出
     */
    static List<Double> parseScores(String json, int count) {
        JSONObject obj = JSON.parseObject(json);
        JSONArray results = obj == null ? null : obj.getJSONArray("results");
        if (results == null) {
            // 部分实现（如早期 TEI）直接返回数组
            results = JSON.isValidArray(json) ? JSON.parseArray(json) : null;
        }
        if (results == null) {
            throw new IllegalStateException("重排接口返回格式不符合预期: " + abbreviate(json));
        }
        List<Double> scores = new ArrayList<>(Collections.nCopies(count, 0.0));
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            Integer index = item.getInteger("index");
            Double score = item.containsKey("relevance_score") ? item.getDouble("relevance_score") : item.getDouble("score");
            if (index != null && index >= 0 && index < count && score != null) {
                scores.set(index, score);
            }
        }
        return scores;
    }

    /**
     * 拼接重排接口地址：baseUrl 已以 /rerank 结尾时直接使用，否则追加 /rerank。
     */
    static String endpoint(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base.endsWith("/rerank") ? base : base + "/rerank";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
