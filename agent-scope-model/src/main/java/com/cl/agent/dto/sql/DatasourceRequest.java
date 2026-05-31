package com.cl.agent.dto.sql;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 数据源管理 CRUD 入参 DTO。
 *
 * <p>由 {@code DatasourceController} 接收前端数据源管理页（新增/编辑）的表单数据。
 * userId 不在请求体内，由 {@code AuthInterceptor} 解析 X-User-Id 头注入到 {@code UserContext}。</p>
 *
 * <p>passwordPlain 字段仅用于"创建/重置密码"，从不返回给前端；编辑时若 passwordPlain 为空表示不修改密码。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据源 ID，新增时为空，编辑时必填 */
    private String id;

    /** 展示名称，必填 */
    private String name;

    /** 用途描述（LLM 决策依据） */
    private String description;

    /** 数据库类型：mysql / postgres，必填，首期仅支持 mysql */
    private String dbType;

    /** JDBC URL，必填 */
    private String jdbcUrl;

    /** 账号，必填 */
    private String username;

    /** 密码明文：新增必填；编辑时为空 = 不修改密码 */
    private String passwordPlain;

    /** 是否启用，null 时默认 1 */
    private Integer enabled;
}
