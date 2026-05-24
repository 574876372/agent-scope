package com.cl.agent.tool.builtin;

/**
 * 网页搜索工具契约，供 Agent 通过 {@code web_search} 调用。
 */
public interface IWebSearchToolService {

    /**
     * 搜索网页并返回相关摘要。
     *
     * @param query 搜索关键词或自然语言问题
     * @return 搜索结果摘要；参数非法或调用失败时返回可读错误说明
     */
    String webSearch(String query);
}
