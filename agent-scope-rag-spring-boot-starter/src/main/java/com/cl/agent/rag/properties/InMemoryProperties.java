package com.cl.agent.rag.properties;

import lombok.Data;

/**
 * 本地内存型向量库配置子类。
 */
@Data
public class InMemoryProperties {
    /**
     * 本地持久化缓存文件夹路径。
     * <p>为空时不进行持久化落盘，重启后内存数据丢失。</p>
     */
    private String persistPath = "./data/vdb/local";
}
