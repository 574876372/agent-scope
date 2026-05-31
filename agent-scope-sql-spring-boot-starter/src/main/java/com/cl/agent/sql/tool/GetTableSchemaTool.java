package com.cl.agent.sql.tool;

import com.cl.agent.commons.UserContext;
import com.cl.agent.sql.core.SchemaRetriever;
import com.cl.agent.sql.spi.DatasourceDescriptor;
import com.cl.agent.sql.spi.DatasourceProvider;
import com.cl.agent.tool.annotation.AgentToolDef;
import com.cl.agent.tool.annotation.AgentToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL Agent 工具：获取指定数据源中若干表的建表 DDL，供 LLM 理解字段含义后生成 SQL。
 *
 * <p>LLM 调用顺序为：list_datasources → get_table_schema → query_database。
 * 本工具只读，结果由 {@link SchemaRetriever} 内部 Caffeine 缓存（5min TTL）。</p>
 */
@Slf4j
@Component
public class GetTableSchemaTool {

    /** 数据源 SPI（宿主提供） */
    private final DatasourceProvider datasourceProvider;

    /** Schema 抽取器 */
    private final SchemaRetriever schemaRetriever;

    /**
     * 构造方法。
     *
     * @param datasourceProvider 数据源 SPI
     * @param schemaRetriever    schema 抽取器
     */
    public GetTableSchemaTool(DatasourceProvider datasourceProvider, SchemaRetriever schemaRetriever) {
        this.datasourceProvider = Objects.requireNonNull(datasourceProvider, "datasourceProvider");
        this.schemaRetriever = Objects.requireNonNull(schemaRetriever, "schemaRetriever");
    }

    /**
     * 获取若干表的 DDL 文本。
     *
     * <p>使用说明：当传入 {@code tables} 为空数组时，先列出全部表名并返回（不含 DDL），
     * 供 LLM 在表多时分步确认目标；非空时返回拼接后的 DDL。</p>
     *
     * @param datasourceId 数据源 ID（必填，来自 list_datasources）
     * @param tables       表名列表，可为空（空时仅返回表清单）
     * @return DDL 文本或表清单文本；数据源不存在时返回提示语
     */
    @AgentToolDef(
            name = "get_table_schema",
            description = "获取指定数据源中若干表的建表 DDL（CREATE TABLE 语句），用于理解字段含义后生成 SQL。" +
                    "若不确定有哪些表，传入空 tables 数组，本工具会先返回全部表清单（含 comment），" +
                    "随后再用具体表名再次调用以获取 DDL。",
            parametersSchema = """
                    {
                      "type": "object",
                      "properties": {
                        "datasourceId": {
                          "type": "string",
                          "description": "数据源 ID，由 list_datasources 返回"
                        },
                        "tables": {
                          "type": "array",
                          "items": {"type": "string"},
                          "description": "需要获取 DDL 的表名列表；为空时仅返回表清单"
                        }
                      },
                      "required": ["datasourceId", "tables"]
                    }
                    """
    )
    public String getTableSchema(
            @AgentToolParam(name = "datasourceId", description = "数据源 ID") String datasourceId,
            @AgentToolParam(name = "tables", description = "表名列表") List<String> tables) {
        String userId = UserContext.getUserId();
        if (datasourceId == null || datasourceId.isBlank()) {
            return "错误：datasourceId 不能为空，请先调用 list_datasources 获取";
        }
        Optional<DataSource> dsOpt = datasourceProvider.resolve(datasourceId, userId);
        if (dsOpt.isEmpty()) {
            return "错误：数据源不存在或未授权: " + datasourceId;
        }
        String dbType = resolveDbType(userId, datasourceId);

        if (tables == null || tables.isEmpty()) {
            log.info("[SQL-FLOW][2/4] get_table_schema list-tables triggered. userId={}, datasourceId={}", userId, datasourceId);
            List<String> tableList = schemaRetriever.listTables(datasourceId, dsOpt.get(), dbType);
            if (tableList.isEmpty()) {
                log.info("[SQL-FLOW][2/4] get_table_schema list-tables success. No tables found for datasourceId={}", datasourceId);
                return "该数据源暂无可见的基础表";
            }
            log.info("[SQL-FLOW][2/4] get_table_schema list-tables success. Found {} tables for datasourceId={}", tableList.size(), datasourceId);
            return "数据源 " + datasourceId + " 包含以下 " + tableList.size() + " 张表：\n"
                    + String.join("\n", tableList)
                    + "\n\n请用具体表名再次调用本工具以获取 DDL。";
        }

        log.info("[SQL-FLOW][3/4] get_table_schema fetch-ddl triggered. userId={}, datasourceId={}, tables={}", userId, datasourceId, tables);
        String ddl = schemaRetriever.describeTablesAsDdl(datasourceId, dsOpt.get(), dbType, tables);
        log.info("[SQL-FLOW][3/4] get_table_schema fetch-ddl success. DDL length: {} bytes for tables={}", ddl.length(), tables);
        log.info("[Tool:get_table_schema] userId={}, ds={}, tables={} -> DDL {} bytes",
                userId, datasourceId, tables, ddl.length());
        return ddl;
    }

    /**
     * 从 DatasourceProvider 的 listAvailable 中反查 dbType。
     *
     * <p>这里多走一次查询是为了避免在 DatasourceProvider 接口上新增 getDbType 方法。
     * 业务列表很短（一般 &lt;10）查询代价可忽略，且宿主层面通常已缓存。</p>
     *
     * @param userId       用户 ID
     * @param datasourceId 数据源 ID
     * @return dbType 字符串；未知时回退为 "mysql"
     */
    private String resolveDbType(String userId, String datasourceId) {
        try {
            List<DatasourceDescriptor> list = datasourceProvider.listAvailable(userId);
            for (DatasourceDescriptor d : list) {
                if (datasourceId.equals(d.getId())) {
                    return d.getDbType() != null ? d.getDbType() : "mysql";
                }
            }
        } catch (Exception e) {
            log.debug("[Tool:get_table_schema] 反查 dbType 失败，回退 mysql: {}", e.getMessage());
        }
        return "mysql";
    }
}
