package com.cl.agent.biz;

import com.cl.agent.dto.LoginRequest;
import com.cl.agent.model.User;

/**
 * 用户业务逻辑接口
 * 负责用户认证、权限校验等业务
 */
public interface IUserBiz {
    
    /**
     * 用户登录/注册统一接口
     * 
     * @param request 登录请求参数 (用户名、密码)
     * @return 登录成功后的用户信息
     */
    User login(LoginRequest request);
}
