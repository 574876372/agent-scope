package com.cl.agent.biz.impl;

import com.cl.agent.biz.IUserBiz;
import com.cl.agent.dto.LoginRequest;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.User;
import com.cl.agent.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserBizImpl implements IUserBiz {

    @Autowired
    private IUserService userService;

    @Override
    public User login(LoginRequest request) {
        User user = userService.findByUsername(request.getUsername());
        
        // 简单的模拟登录逻辑：如果用户不存在则注册，如果存在则校验密码
        if (user == null) {
            user = new User();
            user.setId(UUID.randomUUID().toString());
            user.setUsername(request.getUsername());
            user.setPassword(request.getPassword()); // 实际应加密
            userService.save(user);
        } else {
            if (!user.getPassword().equals(request.getPassword())) {
                throw new BizException(401, "密码错误");
            }
        }
        return user;
    }
}
