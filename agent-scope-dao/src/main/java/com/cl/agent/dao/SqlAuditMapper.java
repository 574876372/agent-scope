package com.cl.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cl.agent.model.SqlAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * SQL 审计日志 Mapper。
 *
 * <p>对应 {@code t_sql_audit} 表；只增不改，业务上不应对历史记录做 UPDATE。</p>
 */
@Mapper
public interface SqlAuditMapper extends BaseMapper<SqlAuditLog> {
}
