package com.cl.agent.biz.impl;

import com.cl.agent.biz.IDatasourceBiz;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dto.sql.DatasourceRequest;
import com.cl.agent.dto.sql.DatasourceResponse;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.Datasource;
import com.cl.agent.service.IDatasourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link IDatasourceBiz} 默认实现。
 *
 * <p>仅做：1) UserContext 校验；2) 实体到 Response DTO 的字段映射（剔除敏感字段）；
 * 真正的 CRUD + 加解密 + 池管理在 {@link IDatasourceService}。</p>
 */
@Slf4j
@Service
public class DatasourceBizImpl implements IDatasourceBiz {

    /** 数据源服务 */
    @Autowired
    private IDatasourceService datasourceService;

    /** {@inheritDoc} */
    @Override
    public List<DatasourceResponse> listMine() {
        String userId = requireUserId();
        return datasourceService.listByUser(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    public DatasourceResponse create(DatasourceRequest request) {
        requireUserId();
        Datasource saved = datasourceService.create(request);
        return toResponse(saved);
    }

    /** {@inheritDoc} */
    @Override
    public DatasourceResponse update(String id, DatasourceRequest request) {
        requireUserId();
        if (request == null) {
            throw new BizException(400, "请求体不能为空");
        }
        request.setId(id);
        Datasource updated = datasourceService.update(request);
        return toResponse(updated);
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String id) {
        String userId = requireUserId();
        datasourceService.remove(id, userId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean testConnection(DatasourceRequest request) {
        requireUserId();
        return datasourceService.testConnection(request);
    }

    /**
     * 取当前 userId，缺失抛 401。
     *
     * @return userId
     */
    private String requireUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "缺少用户上下文");
        }
        return userId;
    }

    /**
     * 实体 → 响应 DTO 转换（剔除 password_cipher）。
     *
     * @param entity 数据源实体
     * @return 响应 DTO
     */
    private DatasourceResponse toResponse(Datasource entity) {
        if (entity == null) {
            return null;
        }
        return DatasourceResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .dbType(entity.getDbType())
                .jdbcUrl(entity.getJdbcUrl())
                .username(entity.getUsername())
                .enabled(entity.getEnabled())
                .readOnly(entity.getReadOnly())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }
}
