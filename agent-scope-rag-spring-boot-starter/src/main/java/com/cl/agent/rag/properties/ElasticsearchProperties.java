package com.cl.agent.rag.properties;

import lombok.Data;
import java.util.List;

/**
 * Elasticsearch 配置类。
 */
@Data
public class ElasticsearchProperties {
    /**
     * ES 服务器集群节点地址。
     */
    private List<String> uris = List.of("http://localhost:9200");

    /**
     * 主索引名称前缀。
     * <p>实际的 Index Name 会加上知识库 ID 进行隔离。</p>
     */
    private String indexName = "agent_knowledge";

    /**
     * 连接用户名。
     */
    private String username;

    /**
     * 连接密码。
     */
    private String password;
}
