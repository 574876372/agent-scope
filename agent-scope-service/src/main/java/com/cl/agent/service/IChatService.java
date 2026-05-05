package com.cl.agent.service;

import com.cl.agent.model.Conversation;
import java.util.List;

/**
 * 会话基础数据服务接口
 * 负责对话历史元数据的持久化操作
 */
public interface IChatService {
    
    /**
     * 保存或更新会话信息
     * 
     * @param conversation 会话实体对象
     */
    void save(Conversation conversation);

    /**
     * 根据 ID 获取会话信息
     * 
     * @param id 会话 ID
     * @return 会话实体对象
     */
    Conversation getById(String id);

    /**
     * 获取所有会话列表
     * 
     * @return 会话列表
     */
    List<Conversation> listAll();

    /**
     * 根据用户 ID 获取会话列表
     * 
     * @param userId 用户 ID
     * @return 该用户的会话列表
     */
    List<Conversation> listByUserId(String userId);

    /**
     * 根据 ID 删除会话记录
     * 
     * @param id 会话 ID
     */
    void deleteById(String id);
}
