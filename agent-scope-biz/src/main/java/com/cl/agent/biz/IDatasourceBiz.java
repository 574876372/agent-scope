package com.cl.agent.biz;

import com.cl.agent.dto.sql.DatasourceRequest;
import com.cl.agent.dto.sql.DatasourceResponse;

import java.util.List;

/**
 * 数据源管理业务接口。
 *
 * <p>面向 {@code DatasourceController}，将实体 + service 层细节转换为前端友好的
 * {@link DatasourceResponse}（不含密码字段），并完成基础参数校验。</p>
 */
public interface IDatasourceBiz {

    /**
     * 列出当前用户全部启用数据源。
     *
     * <p>userId 由 {@code UserContext} 注入；越权场景由 service 自动过滤为空列表。</p>
     *
     * @return 数据源列表（按更新时间倒序），可能为空但不为 null
     */
    List<DatasourceResponse> listMine();

    /**
     * 创建数据源。
     *
     * @param request CRUD 请求，{@code name/jdbcUrl/username/passwordPlain} 必填
     * @return 创建后的数据源（不含密码）
     */
    DatasourceResponse create(DatasourceRequest request);

    /**
     * 更新数据源。
     *
     * @param id      数据源 ID（路径参数）
     * @param request CRUD 请求体；{@code passwordPlain} 留空表示不修改密码
     * @return 更新后的数据源（不含密码）
     */
    DatasourceResponse update(String id, DatasourceRequest request);

    /**
     * 删除数据源（软删 + 关闭运行时连接池）。
     *
     * @param id 数据源 ID
     * @return 无返回值
     */
    void delete(String id);

    /**
     * 测试连接：使用临时 HikariCP 池跑一次 {@code SELECT 1}。
     *
     * <p>支持两种用法：
     * <ul>
     *   <li>新建前测试：{@link DatasourceRequest#getId()} 为空、{@code passwordPlain} 必填</li>
     *   <li>既有数据源测试：传 {@code id}；{@code passwordPlain} 为空时自动回源 cipher</li>
     * </ul>
     *
     * @param request 连接参数
     * @return true 连通，false 失败
     */
    boolean testConnection(DatasourceRequest request);
}
