package com.cl.agent.sql.spi;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * 数据源解析 SPI —— **宿主必须实现并以 Spring Bean 形式提供**。
 *
 * <p>使用场景：starter 中的 3 个 {@code @AgentToolDef} 工具（list_datasources / get_table_schema / query_database）
 * 与 {@code SqlConfirmExecutor} 在需要访问外部业务库时，统一通过本 SPI 获取 JDBC DataSource，
 * starter 自身不关心数据源是如何持久化、加密、缓存的（宿主可走 t_datasource + AES + Hikari 池）。</p>
 *
 * <p>若宿主未提供实现，starter 兜底注入 {@code NoOpDatasourceProvider}：listAvailable 始终返回空、
 * resolve 始终返回 empty，保证 starter 单独引入也能编译运行。</p>
 *
 * <p>线程安全要求：实现类必须线程安全；resolve 可被高频并发调用，建议内部做连接池缓存。</p>
 */
public interface DatasourceProvider {

    /**
     * 根据用户态 datasourceId 解析为 JDBC DataSource。
     *
     * <p>实现方应在此处完成：
     * <ol>
     *   <li>按 userId 做多租户隔离校验（datasourceId 必须归属当前 userId）</li>
     *   <li>解密持久化的密码字段</li>
     *   <li>构造或复用 {@link DataSource}（建议 HikariCP，readOnly=true）</li>
     * </ol>
     *
     * @param datasourceId 数据源 ID，必填；为空或越权时实现方应返回 {@link Optional#empty()}
     * @param userId       当前请求用户 ID，可由 starter 调用方通过 {@code UserContext.getUserId()} 获取并传入
     * @return 解析到的 DataSource；若不存在 / 未授权 / 已禁用，返回 {@link Optional#empty()}（**禁止抛异常导致 LLM 链路终止**）
     */
    Optional<DataSource> resolve(String datasourceId, String userId);

    /**
     * 列出当前用户可用的全部数据源描述符。
     *
     * <p>供 {@code list_datasources} 工具向 LLM 展示。实现方应仅返回 enabled=1 且归属本 userId 的记录。</p>
     *
     * @param userId 当前请求用户 ID
     * @return 数据源描述符列表，可能为空列表但**不应**返回 {@code null}
     */
    List<DatasourceDescriptor> listAvailable(String userId);
}
