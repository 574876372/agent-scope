package com.cl.agent.rag.core;

import lombok.Builder;
import lombok.Value;

/**
 * 构建 Embedding 客户端所需的完整参数。
 * <p>由宿主业务层从自身配置来源（如数据库）组装后传入 {@link EmbeddingStoreFactory}，starter 不关心参数从何而来。
 * {@link #version} 为参数的内容指纹：同一 {@link #modelId} 的 version 变化时，工厂会重建客户端。</p>
 */
@Value
@Builder
public class EmbeddingModelSpec {

    /** 模型唯一标识，作为客户端缓存键 */
    String modelId;

    /** 接口协议，当前仅支持 {@code OPENAI}（OpenAI 兼容协议） */
    String protocol;

    /** 接口基础地址 */
    String baseUrl;

    /** API Key 明文 */
    String apiKey;

    /** 模型名称 */
    String modelName;

    /** 向量维度，须与模型实际输出一致，同时作为向量库索引维度 */
    int dimensions;

    /** 是否将维度作为请求参数发给接口（OpenAI text-embedding-3 系列为 true，通义 v2 为 false） */
    boolean sendDimensions;

    /** 参数内容指纹，用于判断缓存的客户端是否过期 */
    String version;

    @Override
    public String toString() {
        // 显式覆盖，避免 API Key 随对象被打印到日志
        return "EmbeddingModelSpec(modelId=" + modelId + ", protocol=" + protocol + ", baseUrl=" + baseUrl
                + ", modelName=" + modelName + ", dimensions=" + dimensions + ", sendDimensions=" + sendDimensions + ")";
    }
}
