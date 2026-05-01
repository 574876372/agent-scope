package com.cl.agent.service;

import com.cl.agent.dto.LoginRequest;
import com.cl.agent.model.User;

public interface IUserService {
    /**
     * 用户登录
     * @return 登录成功的用户信息，含 Token (此处简化，Token 为用户 ID)
     */
    User login(LoginRequest request);

    /**
     * 根据 ID 获取用户
     */
    User getUserById(String id);
}
