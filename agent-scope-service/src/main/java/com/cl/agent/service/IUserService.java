package com.cl.agent.service;

import com.cl.agent.model.User;

/**
 * 用户基础数据服务接口
 */
public interface IUserService {
    
    /**
     * 根据用户名查询用户
     * 
     * @param username 用户名
     * @return 用户对象
     */
    User findByUsername(String username);

    /**
     * 保存用户信息
     * 
     * @param user 用户对象
     */
    void save(User user);
}
