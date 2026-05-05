package com.cl.agent.service.impl;

import com.cl.agent.model.Conversation;
import com.cl.agent.service.IChatService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatServiceImpl implements IChatService {
    
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
