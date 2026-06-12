package com.cl.agent.rag.properties;

import lombok.Data;

/**
 * Milvus 向量库配置类。
 */
@Data
public class MilvusProperties {
    /**
     * Milvus 服务器连接地址。
     * <p>格式：http://ip:port</p>
     */
    private String uri = "http://localhost:19530";

    /**
     * 主集合名称前缀。
     * <p>实际的 Collection Name 会加上知识库 ID 进行隔离。</p>
     */
    private String collectionName = "agent_knowledge";

    /**
     * 连接验证 Token。
     */
    private String token = "";
}
