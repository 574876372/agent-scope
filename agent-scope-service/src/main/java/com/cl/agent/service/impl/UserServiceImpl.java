package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.dao.UserMapper;
import com.cl.agent.model.User;
import com.cl.agent.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用户基础数据服务实现类
 */
@Service
@Slf4j
public class UserServiceImpl implements IUserService {
    
    @Autowired
    private UserMapper userMapper;

    @Override
    public User findByUsername(String username) {
        log.debug("Finding user by username: {}", username);
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    @Override
    public void save(User user) {
        log.debug("Saving user: {}", user.getUsername());
        if (userMapper.selectById(user.getId()) != null) {
            userMapper.updateById(user);
        } else {
            userMapper.insert(user);
        }
    }
}
