package com.cl.agent.service.impl;

import com.cl.agent.model.User;
import com.cl.agent.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户基础数据服务实现类
 */
@Service
@Slf4j
public class UserServiceImpl implements IUserService {
    
    /** 用户数据模拟缓存 */
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
