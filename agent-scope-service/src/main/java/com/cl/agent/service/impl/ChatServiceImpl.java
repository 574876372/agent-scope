package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.dao.ChatMessageMapper;
import com.cl.agent.dao.ConversationMapper;
import com.cl.agent.dao.ConversationSummaryMapper;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.model.ConversationSummary;
import com.cl.agent.service.IChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话基础数据服务实现类
 */
@Service
@Slf4j
public class ChatServiceImpl implements IChatService {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    /** 会话摘要表 Mapper，删除会话时一并清理其摘要记录 */
    @Autowired
    private ConversationSummaryMapper conversationSummaryMapper;

    @Override
    @Transactional
    public void save(Conversation conversation) {
        log.debug("Saving conversation and messages: {}", conversation.getTitle());
        // 1. 保存会话基础信息
        if (conversationMapper.selectById(conversation.getId()) != null) {
            conversationMapper.updateById(conversation);
        } else {
            conversationMapper.insert(conversation);
        }

        // 2. 手动保存消息列表 (如果有)
        if (conversation.getMessages() != null && !conversation.getMessages().isEmpty()) {
            for (ChatMessage msg : conversation.getMessages()) {
                msg.setConversationId(conversation.getId());
                if (msg.getId() != null) {
                    continue;
                }
                chatMessageMapper.insert(msg);
            }
        }
    }

    @Override
    public Conversation getById(String id) {
        log.debug("Getting conversation by id: {}", id);
        Conversation conv = conversationMapper.selectById(id);

        // 手动组装消息列表
        if (conv != null) {
            List<ChatMessage> messages = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getConversationId, id)
                            .orderByAsc(ChatMessage::getTimestamp));
            conv.setMessages(messages);
        }
        return conv;
    }

    @Override
    public List<Conversation> listAll() {
        log.debug("Listing all conversations");
        return conversationMapper.selectList(null);
    }

    @Override
    public List<Conversation> listByUserId(String userId) {
        log.debug("Listing conversations for user: {}", userId);
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getUpdateTime));
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        log.debug("Deleting conversation and its messages: {}", id);
        // 1. 先删除消息
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, id));
        // 2. 再删除会话
        conversationMapper.deleteById(id);
    }

    /**
     * 删除指定 Agent 关联的全部会话及其消息、摘要。
     * <p>使用说明：删除 Agent 时由业务层级联调用，避免留下无法继续对话的残留会话；逻辑删除，事务内完成。</p>
     *
     * @param agentId 智能体 ID，非空
     * @return 被删除的会话数量；无关联会话时为 0
     */
    @Override
    @Transactional
    public int deleteByAgentId(String agentId) {
        List<Conversation> convs = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getAgentId, agentId));
        if (convs == null || convs.isEmpty()) {
            return 0;
        }
        // 会话 ID 集合，用于批量删除其下的消息与摘要
        List<String> convIds = convs.stream().map(Conversation::getId).collect(Collectors.toList());
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>().in(ChatMessage::getConversationId, convIds));
        conversationSummaryMapper.delete(
                new LambdaQueryWrapper<ConversationSummary>().in(ConversationSummary::getConversationId, convIds));
        conversationMapper.deleteByIds(convIds);
        log.info("[Chat] 已级联删除 Agent 的会话: agentId={}, count={}", agentId, convIds.size());
        return convIds.size();
    }
}
