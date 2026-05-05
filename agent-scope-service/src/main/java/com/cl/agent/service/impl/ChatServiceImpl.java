package com.cl.agent.service.impl;

import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话基础数据服务实现类
 */
@Service
@Slf4j
public class ChatServiceImpl implements IChatService {
    
    /** 内存缓存：会话 ID -> 会话实体 */
    private final ConcurrentHashMap<String, Conversation> conversationCache = new ConcurrentHashMap<>();

    @Override
    public void save(Conversation conversation) {
        conversationCache.put(conversation.getId(), conversation);
    }

    @Override
    public Conversation getById(String id) {
        return conversationCache.get(id);
    }

    @Override
    public List<Conversation> listAll() {
        return new ArrayList<>(conversationCache.values());
    }

    @Override
    public void deleteById(String id) {
        conversationCache.remove(id);
    }
}
