package com.cl.agent.sql.spi.support;

import com.cl.agent.sql.spi.DatasourceDescriptor;
import com.cl.agent.sql.spi.DatasourceProvider;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * {@link DatasourceProvider} 的空操作兜底实现。
 *
 * <p>使用场景：宿主项目暂未实现 {@link DatasourceProvider} 时，starter 仍可被引入并启动，
 * 此时 {@code list_datasources} 工具返回空列表、{@code query_database} 工具因找不到 DataSource
 * 而返回失败提示给 LLM，不会导致 NPE 或 Spring 启动失败。</p>
 *
 * <p>本类由 {@code SqlAgentAutoConfiguration} 在 {@code @ConditionalOnMissingBean(DatasourceProvider.class)}
 * 条件下注入；一旦宿主自定义 Bean 注册，本类即被覆盖。</p>
 */
@Slf4j
public class NoOpDatasourceProvider implements DatasourceProvider {

    /**
     * 永远返回 {@link Optional#empty()}，并仅在 DEBUG 级别打印一行，避免噪音日志。
     *
     * @param datasourceId 数据源 ID（被忽略）
     * @param userId       用户 ID（被忽略）
     * @return 始终 empty
     */
    @Override
    public Optional<DataSource> resolve(String datasourceId, String userId) {
        log.debug("[SQL-SPI] NoOpDatasourceProvider.resolve called (datasourceId={}, userId={}), "
                + "宿主未注册 DatasourceProvider Bean", datasourceId, userId);
        return Optional.empty();
    }

    /**
     * 始终返回空列表（非 null），让 list_datasources 工具优雅地告诉 LLM "当前没有可用数据源"。
     *
     * @param userId 用户 ID（被忽略）
     * @return 空列表
     */
    @Override
    public List<DatasourceDescriptor> listAvailable(String userId) {
        return Collections.emptyList();
    }
}
