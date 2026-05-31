package com.cl.agent.sql.executor;

import ch.qos.logback.core.util.MD5Util;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 执行结果值对象。
 *
 * <p>由 {@code SqlConfirmExecutor.execute} 返回，宿主 {@code SqlAgentBizImpl} 据此构造
 * SSE 推送给前端的 {@code tool_result} 帧。结果同时承担"成功执行"和"用户拒绝/异常"两种语义，
 * 通过 {@link #status} 字段区分。</p>
 *
 * <p>设计上行 + 列分离，便于前端 SqlResultTable 直接渲染表格，且支持空表（仅列、无行）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlExecutionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：EXECUTED / REJECTED / ERROR / TOKEN_EXPIRED */
    private String status;

    /** 实际执行的 SQL（EDIT 分支可能与 token 内 SQL 不同） */
    private String sql;

    /** 数据源 ID */
    private String datasourceId;

    /** 结果列名（按 SELECT 列顺序） */
    @Builder.Default
    private List<String> columns = new ArrayList<>();

    /** 结果行；每行长度等于 columns.size()，元素类型由 JDBC 自动映射 */
    @Builder.Default
    private List<List<Object>> rows = new ArrayList<>();

    /** 返回行数 */
    private int rowCount;

    /** 执行耗时（毫秒），仅 EXECUTED 状态填 */
    private long elapsedMs;

    /** 是否被 LIMIT 截断（行数等于 LIMIT 时即视为可能被截断，前端提示用户） */
    private boolean truncated;

    /** 文本提示，用于回吐给 LLM 或在前端展示（如"用户已取消执行"） */
    private String message;

    /** 错误信息，仅 ERROR 状态填 */
    private String error;

    public static void main(String[] args){

    }
}
