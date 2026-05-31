package com.cl.agent.sql.core;

import com.cl.agent.exception.BizException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 方言路由器：按 {@code dbType} 字符串选择对应 {@link Dialect} 实现。
 *
 * <p>当前注册：{@code mysql → MySqlDialect}。未来扩展只需在构造器中追加 Map 项。</p>
 *
 * <p>线程安全：内部 Map 在构造时填充后只读。</p>
 */
public class DialectRouter {

    /** dbType（全小写） → 方言实例 */
    private final Map<String, Dialect> dialects;

    /**
     * 默认构造：注册 MySQL 方言。
     *
     * <p>本方法被 {@code SqlAgentAutoConfiguration} 调用，运行期不应被业务代码直接 new。</p>
     */
    public DialectRouter() {
        this.dialects = new HashMap<>();
        registerDialect(new MySqlDialect());
    }

    /**
     * 注册方言（供未来扩展使用，本期未对外暴露 starter API）。
     *
     * @param dialect 方言实现，其 {@link Dialect#name()} 必须返回小写厂商标识
     */
    public void registerDialect(Dialect dialect) {
        dialects.put(dialect.name().toLowerCase(Locale.ROOT), dialect);
    }

    /**
     * 按 dbType 查找方言。
     *
     * <p>{@code dbType} 不区分大小写；为空时默认按 MySQL 处理（首期容忍性）。</p>
     *
     * @param dbType 数据源 dbType 字段，如 "mysql"
     * @return 对应的 {@link Dialect} 实例
     * @throws BizException dbType 未注册时抛出 400
     */
    public Dialect of(String dbType) {
        String key = (dbType == null || dbType.isBlank()) ? "mysql" : dbType.toLowerCase(Locale.ROOT);
        Dialect d = dialects.get(key);
        if (d == null) {
            throw new BizException(400, "暂不支持的数据库类型: " + dbType);
        }
        return d;
    }
}
