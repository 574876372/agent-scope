package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.commons.UserContext;
import com.cl.agent.dao.DatasourceMapper;
import com.cl.agent.dto.sql.DatasourceRequest;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.Datasource;
import com.cl.agent.service.IDatasourceService;
import com.cl.agent.sql.core.CryptoService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link IDatasourceService} 默认实现。
 *
 * <h2>运行时连接池</h2>
 * 使用 {@link ConcurrentHashMap} 缓存 {@code datasourceId → HikariDataSource}，按需懒构造，
 * 一次构造长期复用；更新/删除时通过 {@link #invalidate} 主动释放。
 *
 * <h2>多租户隔离</h2>
 * 所有按 ID 的读写都附加 {@code user_id} 过滤条件；越权访问统一返回空，不抛异常以减少信息泄漏。
 */
@Slf4j
@Service
public class DatasourceServiceImpl implements IDatasourceService, DisposableBean {

    /** Mapper */
    @Autowired
    private DatasourceMapper datasourceMapper;

    /** AES-GCM 加解密服务，来自 starter */
    @Autowired
    private CryptoService cryptoService;

    /** 运行时数据源缓存：datasourceId → HikariDataSource */
    private final ConcurrentHashMap<String, HikariDataSource> dsCache = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public Datasource create(DatasourceRequest request) {
        validateMutation(request, true);
        String userId = requireUserId();
        Datasource entity = Datasource.builder()
                .userId(userId)
                .name(request.getName())
                .description(request.getDescription())
                .dbType(safeDbType(request.getDbType()))
                .jdbcUrl(request.getJdbcUrl())
                .username(request.getUsername())
                .passwordCipher(cryptoService.encrypt(request.getPasswordPlain()))
                .readOnly(1)
                .enabled(request.getEnabled() == null ? 1 : request.getEnabled())
                .build();
        datasourceMapper.insert(entity);
        entity.setPasswordCipher(null);
        log.info("[Datasource] 创建: userId={}, id={}, name={}", userId, entity.getId(), entity.getName());
        return entity;
    }

    /** {@inheritDoc} */
    @Override
    public Datasource update(DatasourceRequest request) {
        if (request.getId() == null || request.getId().isBlank()) {
            throw new BizException(400, "更新数据源缺少 id");
        }
        validateMutation(request, false);
        String userId = requireUserId();
        Datasource existing = getOwnedOrThrow(request.getId(), userId);
        existing.setName(request.getName());
        existing.setDescription(request.getDescription());
        existing.setDbType(safeDbType(request.getDbType()));
        existing.setJdbcUrl(request.getJdbcUrl());
        existing.setUsername(request.getUsername());
        if (request.getPasswordPlain() != null && !request.getPasswordPlain().isBlank()) {
            existing.setPasswordCipher(cryptoService.encrypt(request.getPasswordPlain()));
        }
        if (request.getEnabled() != null) {
            existing.setEnabled(request.getEnabled());
        }
        datasourceMapper.updateById(existing);
        invalidate(existing.getId());
        existing.setPasswordCipher(null);
        log.info("[Datasource] 更新: userId={}, id={}", userId, existing.getId());
        return existing;
    }

    /** {@inheritDoc} */
    @Override
    public void remove(String id, String userId) {
        if (id == null || id.isBlank()) {
            return;
        }
        Datasource existing = getOwnedOrThrow(id, userId);
        datasourceMapper.deleteById(existing.getId());
        invalidate(id);
        log.info("[Datasource] 删除: userId={}, id={}", userId, id);
    }

    /** {@inheritDoc} */
    @Override
    public List<Datasource> listByUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<Datasource> w = new LambdaQueryWrapper<>();
        w.eq(Datasource::getUserId, userId)
                .eq(Datasource::getEnabled, 1)
                .orderByDesc(Datasource::getUpdateTime);
        return datasourceMapper.selectList(w);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Datasource> getById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(datasourceMapper.selectById(id));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DataSource> resolveDataSource(String id, String userId) {
        if (id == null || id.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Datasource entity = datasourceMapper.selectById(id);
        if (entity == null
                || !userId.equals(entity.getUserId())
                || entity.getEnabled() == null || entity.getEnabled() != 1) {
            return Optional.empty();
        }
        try {
            HikariDataSource hds = dsCache.computeIfAbsent(id, k -> buildHikari(entity));
            return Optional.of(hds);
        } catch (Exception e) {
            log.warn("[Datasource] 构造连接池失败 id={}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean testConnection(DatasourceRequest request) {
        if (request == null) {
            return false;
        }
        String plain = request.getPasswordPlain();
        if ((plain == null || plain.isBlank()) && request.getId() != null) {
            Datasource existing = getOwnedOrThrow(request.getId(), requireUserId());
            plain = cryptoService.decrypt(existing.getPasswordCipher());
        }
        if (plain == null || plain.isBlank()) {
            throw new BizException(400, "缺少数据库密码，无法测试连接");
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(request.getJdbcUrl());
        cfg.setUsername(request.getUsername());
        cfg.setPassword(plain);
        cfg.setReadOnly(true);
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionTimeout(5_000L);
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.setPoolName("ds-test-" + System.currentTimeMillis());
        try (HikariDataSource tmp = new HikariDataSource(cfg);
             Connection conn = tmp.getConnection()) {
            return conn.isValid(3);
        } catch (Exception e) {
            log.info("[Datasource] 测试连接失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 主动失效缓存中的连接池（更新/删除时调用）。
     *
     * @param id 数据源 ID
     */
    private void invalidate(String id) {
        HikariDataSource removed = dsCache.remove(id);
        if (removed != null) {
            try {
                removed.close();
            } catch (Exception e) {
                log.debug("[Datasource] 关闭旧 HikariDataSource 异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 校验创建/更新请求的关键字段。
     *
     * @param req     请求体
     * @param isCreate 是否为创建分支，true 时要求 passwordPlain 必填
     */
    private void validateMutation(DatasourceRequest req, boolean isCreate) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(400, "name 必填");
        }
        if (req.getJdbcUrl() == null || req.getJdbcUrl().isBlank()) {
            throw new BizException(400, "jdbcUrl 必填");
        }
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            throw new BizException(400, "username 必填");
        }
        if (isCreate && (req.getPasswordPlain() == null || req.getPasswordPlain().isBlank())) {
            throw new BizException(400, "新增数据源时 passwordPlain 必填");
        }
    }

    /**
     * 取数据源并校验归属；越权或不存在时抛 404。
     *
     * @param id     数据源 ID
     * @param userId 当前用户
     * @return 校验通过的实体
     */
    private Datasource getOwnedOrThrow(String id, String userId) {
        Datasource ds = datasourceMapper.selectById(id);
        if (ds == null || !userId.equals(ds.getUserId())) {
            throw new BizException(404, "数据源不存在或无权访问");
        }
        return ds;
    }

    /**
     * 取当前请求的 userId；为空时抛 401。
     *
     * @return 用户 ID
     */
    private String requireUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "缺少用户上下文");
        }
        return userId;
    }

    /**
     * 容错的 dbType 兜底（首期仅 mysql）。
     *
     * @param dbType 入参 dbType
     * @return 规整后的 dbType（默认 "mysql"）
     */
    private String safeDbType(String dbType) {
        return (dbType == null || dbType.isBlank()) ? "mysql" : dbType.toLowerCase();
    }

    /**
     * 实际构造 HikariDataSource（只读、4 路连接、SELECT 1 心跳）。
     *
     * @param entity 数据源实体
     * @return 新建的 HikariDataSource
     */
    private HikariDataSource buildHikari(Datasource entity) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(entity.getJdbcUrl());
        cfg.setUsername(entity.getUsername());
        cfg.setPassword(cryptoService.decrypt(entity.getPasswordCipher()));
        cfg.setReadOnly(true);
        cfg.setAutoCommit(true);
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(5_000L);
        cfg.setIdleTimeout(60_000L);
        cfg.setMaxLifetime(10 * 60_000L);
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.setPoolName("ds-" + entity.getId());
        log.info("[Datasource] 创建 HikariDataSource: id={}, url={}", entity.getId(), entity.getJdbcUrl());
        return new HikariDataSource(cfg);
    }

    /**
     * Bean 销毁时关闭所有连接池。
     *
     * @return 无返回值；副作用为遍历 close()
     */
    @Override
    public void destroy() {
        log.info("[Datasource] 关闭 {} 个外部数据源连接池", dsCache.size());
        dsCache.values().forEach(ds -> {
            try {
                ds.close();
            } catch (Exception ignored) {
                // 关闭异常忽略
            }
        });
        dsCache.clear();
    }
}
