package com.cl.agent.service.impl;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.cl.agent.dto.LoginRequest;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.User;
import com.cl.agent.service.IUserService;

@Service
public class UserServiceImpl implements IUserService {

    private final ConcurrentHashMap<String, User> userCache = new ConcurrentHashMap<>();

    public UserServiceImpl() {
        // 预置一个测试用户
        User testUser = new User("user", "admin", "123456");
        userCache.put(testUser.getId(), testUser);
        userCache.put("admin", testUser); // 方便通过用户名查找
    }

    @Override
    public User login(LoginRequest request) {
        User user = userCache.get(request.getUsername());
        if (user == null) {
            // 如果不存在则模拟注册
            user = new User(UUID.randomUUID().toString(), request.getUsername(), request.getPassword());
            userCache.put(user.getId(), user);
            userCache.put(request.getUsername(), user);
        } else if (!user.getPassword().equals(request.getPassword())) {
            throw new BizException(401, "密码错误");
        }
        return user;
    }

    @Override
    public User getUserById(String id) {
        return userCache.get(id);
    }
}
