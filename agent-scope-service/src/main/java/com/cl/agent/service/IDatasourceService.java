package com.cl.agent.service;

import com.cl.agent.dto.sql.DatasourceRequest;
import com.cl.agent.model.Datasource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * 外部业务数据源管理服务接口。
 *
 * <p>对应 {@code t_datasource} 表的 CRUD 与运行时连接池管理：
 * <ul>
 *   <li>{@link #create} / {@link #update} / {@link #remove} —— 表数据 + AES 加密密码</li>
 *   <li>{@link #listByUser} / {@link #getById} —— 多租户隔离查询</li>
 *   <li>{@link #resolveDataSource} —— 解密密码后构造 HikariCP 只读连接池并缓存复用</li>
 *   <li>{@link #testConnection} —— 不入缓存的瞬时连接验证（{@code SELECT 1}）</li>
 * </ul>
 *
 * <p>HostDatasourceProvider (biz/sql) 将本接口适配为 starter 的 {@code DatasourceProvider} SPI。</p>
 */
public interface IDatasourceService {

    /**
     * 创建一个新数据源。
     *
     * @param request 创建参数，{@code passwordPlain} 必填；userId 从 {@code UserContext} 注入
     * @return 持久化后的实体（不含明文密码）
     */
    Datasource create(DatasourceRequest request);

    /**
     * 更新数据源；passwordPlain 为空时表示不修改密码。
     *
     * @param request 更新参数，{@code id} 必填
     * @return 更新后的实体（不含明文密码）
     */
    Datasource update(DatasourceRequest request);

    /**
     * 软删除数据源；同时失效本节点的运行时连接池缓存。
     *
     * @param id     数据源 ID
     * @param userId 当前用户 ID（用于权限校验，仅本人可删）
     * @return 无返回值；副作用为标记 del_flag=1 并 invalidate 缓存
     */
    void remove(String id, String userId);

    /**
     * 列出指定用户的全部启用数据源。
     *
     * @param userId 用户 ID
     * @return 启用列表（按更新时间倒序），可能为空列表但不为 null
     */
    List<Datasource> listByUser(String userId);

    /**
     * 按主键查询；调用方须自行校验 {@code userId} 一致性。
     *
     * @param id 主键
     * @return 实体 Optional
     */
    Optional<Datasource> getById(String id);

    /**
     * 解析为可用的 {@link DataSource}（HikariCP 只读连接池）；缓存命中时复用。
     *
     * <p>调用方为 {@code HostDatasourceProvider}；本方法不抛异常，未找到或越权时返回 empty。</p>
     *
     * @param id     数据源 ID
     * @param userId 当前用户 ID（多租户隔离）
     * @return DataSource Optional
     */
    Optional<DataSource> resolveDataSource(String id, String userId);

    /**
     * 测试当前请求中的连接参数是否可用。
     *
     * <p>使用单独的临时 HikariDataSource，执行 {@code SELECT 1}，完成后立即关闭，不入运行时缓存。
     * 用于前端"测试连接"按钮场景，避免脏数据进入工作池。</p>
     *
     * @param request 连接参数；编辑场景下若 {@code passwordPlain} 为空，将回源到既有 {@code passwordCipher}
     * @return true 连接成功；false 连接失败
     */
    boolean testConnection(DatasourceRequest request);
}
