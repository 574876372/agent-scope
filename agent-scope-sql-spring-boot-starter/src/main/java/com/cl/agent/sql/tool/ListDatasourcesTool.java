package com.cl.agent.sql.tool;

import com.alibaba.fastjson2.JSON;
import com.cl.agent.commons.UserContext;
import com.cl.agent.sql.spi.DatasourceDescriptor;
import com.cl.agent.sql.spi.DatasourceProvider;
import com.cl.agent.tool.annotation.AgentToolDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * SQL Agent 工具：列出当前用户可访问的全部业务数据源。
 *
 * <p>是 SQL Agent 工具链的入口工具，LLM 在生成 SQL 前必须先调用本工具，
 * 拿到 datasourceId 才能进一步调用 {@code get_table_schema} 与 {@code query_database}。</p>
 *
 * <p>本工具只读，不修改任何状态，无副作用。</p>
 */
@Slf4j
@Component
public class ListDatasourcesTool {

    /** 数据源 SPI（宿主提供） */
    private final DatasourceProvider datasourceProvider;

    /**
     * 构造方法。
     *
     * @param datasourceProvider 数据源 SPI 实现
     */
    public ListDatasourcesTool(DatasourceProvider datasourceProvider) {
        this.datasourceProvider = Objects.requireNonNull(datasourceProvider, "datasourceProvider");
    }

    /**
     * 列出当前用户的可用业务数据源。
     *
     * <p>使用说明：从 {@link UserContext} 取 userId，向 {@link DatasourceProvider} 查询全部可用 descriptor，
     * 序列化为 JSON 数组字符串返回给 LLM。无数据源时返回 {@code "[]"} 让 LLM 友好地告知用户。</p>
     *
     * @return JSON 字符串，元素结构 {@code {id, name, description, dbType}}
     */
    @AgentToolDef(
            name = "list_datasources",
            description = "列出当前用户可访问的全部业务数据源（含 id/name/description/dbType）。" +
                    "在生成任何 SQL 之前必须先调用本工具确定 datasourceId；" +
                    "若返回空数组，则告诉用户暂未配置任何数据源。",
            parametersSchema = """
                    {
                      "type": "object",
                      "properties": {},
                      "required": []
                    }
                    """
    )
    public String listDatasources() {
        String userId = UserContext.getUserId();
        log.info("[SQL-FLOW][1/4] list_datasources tool triggered. userId={}", userId);
        if (userId == null) {
            log.warn("[Tool:list_datasources] UserContext 中 userId 为空");
            return "[]";
        }
        List<DatasourceDescriptor> list = datasourceProvider.listAvailable(userId);
        if (list == null || list.isEmpty()) {
            log.info("[SQL-FLOW][1/4] list_datasources tool success. No datasources available for userId={}", userId);
            log.info("[Tool:list_datasources] userId={} 无可用数据源", userId);
            return "[]";
        }
        log.info("[SQL-FLOW][1/4] list_datasources tool success. Found {} datasources available for userId={}", list.size(), userId);
        log.info("[Tool:list_datasources] userId={} 返回 {} 条数据源", userId, list.size());
        return JSON.toJSONString(list);
    }
}
