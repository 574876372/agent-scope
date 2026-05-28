package com.cl.agent.service.impl;

import com.cl.agent.dao.ConversationSummaryMapper;
import com.cl.agent.model.ConversationSummary;
import com.cl.agent.service.IConversationSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 对话历史摘要服务实现
 */
@Service
@Slf4j
public class ConversationSummaryServiceImpl implements IConversationSummaryService {

    @Autowired
    private ConversationSummaryMapper summaryMapper;

    @Override
    public ConversationSummary getLatest(String conversationId) {
        return summaryMapper.selectLatestByConversationId(conversationId);
    }

    @Override
    public void save(String conversationId, String summary, Long coveredUpTo) {
        ConversationSummary record = ConversationSummary.builder()
                .conversationId(conversationId)
                .summary(summary)
                .coveredUpTo(coveredUpTo)
                .build();
        summaryMapper.insert(record);
        log.info("[Memory] 摘要持久化完成, conversationId={}, coveredUpTo={}, summaryLength={}",
                conversationId, coveredUpTo, summary.length());
    }
}
