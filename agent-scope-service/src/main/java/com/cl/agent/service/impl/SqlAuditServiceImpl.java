package com.cl.agent.service.impl;

import com.cl.agent.dao.SqlAuditMapper;
import com.cl.agent.model.SqlAuditLog;
import com.cl.agent.service.ISqlAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * {@link ISqlAuditService} 默认实现。
 *
 * <p>任何异常都被吞掉为 WARN 级日志，避免拖累主链路；审计可观测性可后续接入异步队列。</p>
 */
@Slf4j
@Service
public class SqlAuditServiceImpl implements ISqlAuditService {

    /** 审计 Mapper */
    @Autowired
    private SqlAuditMapper sqlAuditMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public void save(SqlAuditLog log) {
        if (log == null) {
            return;
        }
        if (log.getOccurredAt() == null) {
            log.setOccurredAt(LocalDateTime.now());
        }
        try {
            sqlAuditMapper.insert(log);
        } catch (Exception e) {
            SqlAuditServiceImpl.log.warn("[SqlAudit] 落库失败（已吞）: phase={}, ds={}, err={}",
                    log.getPhase(), log.getDatasourceId(), e.getMessage());
        }
    }
}
