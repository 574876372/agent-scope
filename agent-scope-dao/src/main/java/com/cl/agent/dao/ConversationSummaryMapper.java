package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 对话历史摘要 Mapper
 */
@Mapper
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummary> {

    /**
     * 查询指定会话的最新一条摘要记录（按创建时间降序取第一条）。
     *
     * @param conversationId 会话 ID
     * @return 最新摘要，不存在时返回 null
     */
    @Select("SELECT * FROM t_conversation_summary " +
            "WHERE conversation_id = #{conversationId} AND del_flag = 0 " +
            "ORDER BY create_time DESC LIMIT 1")
    ConversationSummary selectLatestByConversationId(@Param("conversationId") String conversationId);
}
