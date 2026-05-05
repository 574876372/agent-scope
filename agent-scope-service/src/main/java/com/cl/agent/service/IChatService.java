package com.cl.agent.service;

import com.cl.agent.model.Conversation;
import java.util.List;

public interface IChatService {
    void save(Conversation conversation);
    Conversation getById(String id);
    List<Conversation> listAll();
    void deleteById(String id);
}
