package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.RagEvalCase;
import org.apache.ibatis.annotations.Mapper;

/**
 * 检索评估用例表 {@code t_rag_eval_case} 数据访问接口，仅使用 MyBatis-Plus 通用 CRUD。
 */
@Mapper
public interface RagEvalCaseMapper extends BaseMapper<RagEvalCase> {
}
