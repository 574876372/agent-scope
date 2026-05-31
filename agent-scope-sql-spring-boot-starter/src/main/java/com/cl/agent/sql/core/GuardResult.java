package com.cl.agent.sql.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SqlGuardEngine#validate} 的结构化校验结果。
 *
 * <p>非内部类抽出为独立文件（按 {@code .cursorrules} 包分组规则）；同时被
 * {@code QueryDatabaseTool} 与 {@code SqlConfirmExecutor}（EDIT 分支）复用。</p>
 *
 * <p>校验失败时调用方应抛出 {@code BizException}，不要把 errorMessage 当作正常 SQL 字符串使用；
 * 校验通过时使用 {@link #sanitizedSql} 作为后续执行的最终 SQL 文本。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GuardResult {

    /** 是否通过校验；false 表示存在不可接受的语法或语义风险 */
    private boolean passed;

    /** 校验后的最终 SQL（可能被改写：如自动追加 LIMIT、剔除尾分号） */
    private String sanitizedSql;

    /**
     * 非致命提示（不阻断执行），如：
     * <ul>
     *   <li>"已自动追加 LIMIT 500"</li>
     *   <li>"EXPLAIN 估算行数 1.2M 较大，请确认"</li>
     * </ul>
     */
    private List<String> warnings = new ArrayList<>();

    /** 致命错误说明，passed=false 时填，用于回吐给 LLM 让其重试 */
    private String errorMessage;

    /**
     * 工厂方法：构造通过校验的结果。
     *
     * @param sanitizedSql 净化后的 SQL
     * @param warnings     非致命提示列表（可为空但不为 null）
     * @return passed=true 的 {@link GuardResult}
     */
    public static GuardResult ok(String sanitizedSql, List<String> warnings) {
        GuardResult r = new GuardResult();
        r.passed = true;
        r.sanitizedSql = sanitizedSql;
        r.warnings = warnings == null ? new ArrayList<>() : warnings;
        return r;
    }

    /**
     * 工厂方法：构造拒绝结果。
     *
     * @param errorMessage 拒绝原因，将作为 Observation 喂给 LLM
     * @return passed=false 的 {@link GuardResult}
     */
    public static GuardResult reject(String errorMessage) {
        GuardResult r = new GuardResult();
        r.passed = false;
        r.errorMessage = errorMessage;
        return r;
    }
}
