package com.cl.agent.service;

import com.cl.agent.model.ConversationSummary;

/**
 * 对话历史摘要服务接口
 */
public interface IConversationSummaryService {

    /**
     * 查询指定会话的最新摘要记录。
     *
     * @param conversationId 会话 ID
     * @return 最新摘要记录，不存在时返回 null
     */
    ConversationSummary getLatest(String conversationId);

    /**
     * 持久化一条新的摘要记录。
     *
     * @param conversationId 会话 ID
     * @param summary        LLM 生成的摘要文本
     * @param coveredUpTo    本次摘要覆盖到的最后一条消息 ID
     */
    void save(String conversationId, String summary, Long coveredUpTo);
}
