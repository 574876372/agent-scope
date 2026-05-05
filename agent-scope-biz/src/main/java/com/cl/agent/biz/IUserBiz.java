package com.cl.agent.biz;

import com.cl.agent.dto.LoginRequest;
import com.cl.agent.model.User;

public interface IUserBiz {
    User login(LoginRequest request);
}
