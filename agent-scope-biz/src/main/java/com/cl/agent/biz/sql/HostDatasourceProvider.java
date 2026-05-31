package com.cl.agent.biz.sql;

import com.cl.agent.model.Datasource;
import com.cl.agent.service.IDatasourceService;
import com.cl.agent.sql.spi.DatasourceDescriptor;
import com.cl.agent.sql.spi.DatasourceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 宿主侧 {@link DatasourceProvider} 实现：将 starter SPI 委托到 {@link IDatasourceService}。
 *
 * <p>Spring Bean 形式注册后，{@code SqlAgentAutoConfiguration} 通过 {@code @ConditionalOnMissingBean}
 * 自动让出 NoOp 兜底，由本 Bean 承接所有数据源解析请求。</p>
 *
 * <p>本类故意不持有任何状态，所有缓存（HikariDataSource）放在 service 层，便于其它模块共享。</p>
 */
@Slf4j
@Component
public class HostDatasourceProvider implements DatasourceProvider {

    /** 数据源服务，提供解密 + Hikari 池缓存 */
    @Autowired
    private IDatasourceService datasourceService;

    /**
     * 按 datasourceId 解析为可用 DataSource。
     *
     * <p>使用说明：被 starter 内 {@code QueryDatabaseTool} / {@code SqlConfirmExecutor} / {@code GetTableSchemaTool} 调用；
     * 严格走 service 的归属校验，越权或不存在统一返回空，**不抛异常**以避免打断 LLM 主链路。</p>
     *
     * @param datasourceId 数据源 ID
     * @param userId       当前用户 ID（starter 已从 UserContext 取出后传入）
     * @return DataSource Optional
     */
    @Override
    public Optional<DataSource> resolve(String datasourceId, String userId) {
        return datasourceService.resolveDataSource(datasourceId, userId);
    }

    /**
     * 列出当前用户可用的数据源描述符。
     *
     * <p>实体到描述符的字段映射：id / name / description / dbType；
     * password 等敏感字段不在此处暴露。</p>
     *
     * @param userId 用户 ID
     * @return 描述符列表（可能为空，不为 null）
     */
    @Override
    public List<DatasourceDescriptor> listAvailable(String userId) {
        List<Datasource> entities = datasourceService.listByUser(userId);
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        return entities.stream()
                .map(e -> DatasourceDescriptor.builder()
                        .id(e.getId())
                        .name(e.getName())
                        .description(e.getDescription())
                        .dbType(e.getDbType())
                        .build())
                .collect(Collectors.toList());
    }
}
