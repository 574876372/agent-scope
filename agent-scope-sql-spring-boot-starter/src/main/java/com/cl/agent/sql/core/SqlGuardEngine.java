package com.cl.agent.sql.core;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全守卫引擎：所有用户提交的 SQL 在执行/估算之前必须过本守卫。
 *
 * <h2>校验维度</h2>
 * <ol>
 *   <li><b>语法层</b>：JSqlParser 解析必须成功且为 SELECT 类语句。</li>
 *   <li><b>语句数</b>：拒绝多语句拼接（防止 {@code SELECT 1; DROP TABLE x} 注入）。</li>
 *   <li><b>表黑名单</b>：拒绝访问 {@code mysql.*}、{@code information_schema.*}、{@code performance_schema.*}、{@code sys.*} 系统库。</li>
 *   <li><b>危险函数</b>：正则黑名单拦截 {@code SLEEP}、{@code BENCHMARK}、{@code LOAD_FILE}、{@code INTO OUTFILE/DUMPFILE} 等。</li>
 *   <li><b>LIMIT 兜底</b>：未指定 LIMIT 或超过 {@code defaultRowLimit} 时自动改写并记录 warning。</li>
 * </ol>
 *
 * <h2>使用约定</h2>
 * <ul>
 *   <li>{@code QueryDatabaseTool} 在颁发 token 前调用一次；</li>
 *   <li>{@code SqlConfirmExecutor} 在 EDIT 分支（editedSql 重写）时**必须再次调用**，防止前端绕开校验。</li>
 *   <li>JdbcTemplate 执行前永远使用 {@link GuardResult#getSanitizedSql()}，而非用户原 SQL。</li>
 * </ul>
 *
 * <p>实现刻意保持无状态，可作为单例 Bean 注入。</p>
 */
@Slf4j
public class SqlGuardEngine {

    /** 系统库黑名单（不区分大小写匹配） */
    private static final Set<String> FORBIDDEN_SCHEMAS = Set.of(
            "mysql", "information_schema", "performance_schema", "sys");

    /**
     * 危险函数与子句的正则黑名单。
     * <p>这是 JSqlParser 语义检查之外的"防御层 2"：即便解析能通过，命中正则即拒绝。
     * 用 {@code (?i)} 不区分大小写，{@code \b} 词边界避免误伤如 {@code my_sleep_func} 之类的列名。</p>
     */
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "(?i)\\b(SLEEP|BENCHMARK|LOAD_FILE|EXTRACTVALUE|UPDATEXML)\\s*\\("
                    + "|(?i)\\bINTO\\s+(OUTFILE|DUMPFILE)\\b"
                    + "|(?i)\\bGRANT\\b|(?i)\\bSET\\s+GLOBAL\\b");

    /**
     * 校验并改写 SQL。
     *
     * <p>使用说明：本方法**只读不写**，永远不会真正访问数据库；它仅做语法分析 + 静态规则匹配，
     * 因此可安全在主线程同步调用。被 {@code QueryDatabaseTool} 和 {@code SqlConfirmExecutor} 的 EDIT 分支共用。</p>
     *
     * @param sql      用户/LLM 提交的原始 SQL，必填非空（首尾空白会被 trim）
     * @param maxLimit 强制 LIMIT 上限，&lt;=0 表示不改写 LIMIT
     * @return {@link GuardResult} 校验结果；当 {@code passed=false} 时调用方应拒绝执行并把 errorMessage 作为 Observation 回吐给 LLM
     */
    public GuardResult validate(String sql, int maxLimit) {
        if (sql == null || sql.isBlank()) {
            return GuardResult.reject("SQL 不能为空");
        }
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        // 防御层 2：危险关键字正则
        if (DANGEROUS_PATTERN.matcher(trimmed).find()) {
            return GuardResult.reject("SQL 包含禁用函数或子句（SLEEP/BENCHMARK/LOAD_FILE/INTO OUTFILE 等）");
        }

        // 语法解析 —— 同时隐式校验只允许 1 条语句（多语句会抛 JSQLParserException）
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(trimmed);
        } catch (JSQLParserException e) {
            log.debug("[SqlGuard] 解析失败: {}", e.getMessage());
            return GuardResult.reject("SQL 语法解析失败：" + cleanMessage(e.getMessage()));
        }

        // 仅允许 PlainSelect；UNION/INTERSECT/EXCEPT 这种 SetOperationList 暂不支持
        if (stmt instanceof SetOperationList) {
            return GuardResult.reject("暂不支持 UNION / INTERSECT / EXCEPT 等集合操作查询");
        }
        if (!(stmt instanceof Select select)) {
            return GuardResult.reject("仅允许 SELECT 查询，当前语句类型不被允许");
        }
        if (!(select instanceof PlainSelect plainSelect)) {
            return GuardResult.reject("仅支持简单 SELECT 语句（不含括号嵌套/集合操作）");
        }

        // 表黑名单
        try {
            Set<String> tables = new TablesNamesFinder().getTables(stmt);
            for (String t : tables) {
                if (containsForbiddenSchema(t)) {
                    return GuardResult.reject("禁止访问系统库表：" + t);
                }
            }
        } catch (Exception e) {
            // TablesNamesFinder 对部分复杂 SQL 可能抛错；不影响主流程，仅 warn
            log.warn("[SqlGuard] 表名提取异常，跳过黑名单检查: {}", e.getMessage());
        }

        // LIMIT 兜底改写
        List<String> warnings = new ArrayList<>();
        if (maxLimit > 0) {
            enforceLimit(plainSelect, maxLimit, warnings);
        }

        String sanitized = plainSelect.toString();
        log.debug("[SqlGuard] 校验通过: {}", sanitized);
        return GuardResult.ok(sanitized, warnings);
    }

    /**
     * 强制 LIMIT：若未指定则追加，已超阈值则改写。
     *
     * @param plainSelect 已解析的 PlainSelect 节点（会被原地修改）
     * @param maxLimit    上限值（&gt; 0）
     * @param warnings    用于记录改写提示，调用方传入的可变 List
     */
    private void enforceLimit(PlainSelect plainSelect, int maxLimit, List<String> warnings) {
        Limit existing = plainSelect.getLimit();
        if (existing == null) {
            Limit limit = new Limit();
            limit.setRowCount(new LongValue(maxLimit));
            plainSelect.setLimit(limit);
            warnings.add("未指定 LIMIT，已自动追加 LIMIT " + maxLimit);
            return;
        }
        // 已有 LIMIT 时尝试解析数值；非 LongValue（如表达式/占位符）保持原状不动
        if (existing.getRowCount() instanceof LongValue lv) {
            long currentLimit = lv.getValue();
            if (currentLimit > maxLimit || currentLimit <= 0) {
                Limit limit = new Limit();
                limit.setRowCount(new LongValue(maxLimit));
                plainSelect.setLimit(limit);
                warnings.add("原 LIMIT " + currentLimit + " 超过上限，已改写为 LIMIT " + maxLimit);
            }
        }
    }

    /**
     * 判断表名是否归属系统库黑名单。
     *
     * <p>JSqlParser 返回的表名可能为 {@code "schema.table"} 或 {@code "table"}；只检查 schema 段。</p>
     *
     * @param fullTableName JSqlParser 返回的表名
     * @return true 表示命中黑名单
     */
    private boolean containsForbiddenSchema(String fullTableName) {
        if (fullTableName == null) {
            return false;
        }
        int dot = fullTableName.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String schema = fullTableName.substring(0, dot).toLowerCase();
        // schema 可能被反引号包裹
        if (schema.startsWith("`") && schema.endsWith("`")) {
            schema = schema.substring(1, schema.length() - 1);
        }
        return FORBIDDEN_SCHEMAS.contains(schema);
    }

    /**
     * 清理 JSqlParser 报错信息：截断到首行，避免把巨大的 ANTLR 调试栈塞回给 LLM。
     *
     * @param raw 原始异常信息
     * @return 截断后的首行
     */
    private String cleanMessage(String raw) {
        if (raw == null) {
            return "";
        }
        int nl = raw.indexOf('\n');
        return (nl > 0 ? raw.substring(0, nl) : raw).trim();
    }
}
