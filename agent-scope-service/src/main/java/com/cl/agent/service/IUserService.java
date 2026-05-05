package com.cl.agent.service;

import com.cl.agent.model.User;

public interface IUserService {
    User findByUsername(String username);
    void save(User user);
}
