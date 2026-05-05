package com.cl.agent.service.impl;

import com.cl.agent.model.User;
import com.cl.agent.service.IUserService;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserServiceImpl implements IUserService {
    
    private final ConcurrentHashMap<String, User> userCache = new ConcurrentHashMap<>();

    @Override
    public User findByUsername(String username) {
        return userCache.get(username);
    }

    @Override
    public void save(User user) {
        userCache.put(user.getUsername(), user);
    }
}
