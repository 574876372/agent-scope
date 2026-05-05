package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.dao.ChatMessageMapper;
import com.cl.agent.dao.ConversationMapper;
import com.cl.agent.model.ChatMessage;
import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
                if (msg.getId() != null && chatMessageMapper.selectById(msg.getId()) != null) {
                    chatMessageMapper.updateById(msg);
                } else {
                    chatMessageMapper.insert(msg);
                }
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
                            .orderByAsc(ChatMessage::getTimestamp)
            );
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
                        .orderByDesc(Conversation::getUpdateTime)
        );
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
}
