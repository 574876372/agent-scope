package com.cl.agent.biz.rag;

import com.cl.agent.dto.model.ModelConnection;

import java.util.List;

/**
 * 检索结果重排序接口。
 * <p>使用说明：检索流水线第 ④ 步在 RRF 融合之后调用，按问题与切片原文的相关度重新打分；
 * 仅在智能体或全局配置了重排模型时启用。不同厂商的重排接口格式不同，每种格式一个实现。</p>
 */
public interface Reranker {

    /**
     * 对候选文本按与问题的相关度打分。
     *
     * @param conn      重排模型连接参数，非空
     * @param query     检索问题，非空
     * @param documents 候选文本，非空列表
     * @return 与 documents 下标一一对应的相关度（0 ~ 1，越大越相关），长度与 documents 相同
     * @throws RuntimeException 接口调用失败或返回格式不符合预期时抛出，由调用方降级为不重排
     */
    List<Double> score(ModelConnection conn, String query, List<String> documents);
}
