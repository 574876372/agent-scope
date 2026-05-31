package com.cl.agent.dto.sql;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据源管理 CRUD 响应 DTO。
 *
 * <p>由 {@code DatasourceController} 返回给前端列表/详情页；
 * 出于安全考量，{@code passwordCipher} 与明文均不下发，只保留连接参数与展示元数据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据源 ID */
    private String id;

    /** 展示名称 */
    private String name;

    /** 用途描述 */
    private String description;

    /** 数据库类型 */
    private String dbType;

    /** JDBC URL */
    private String jdbcUrl;

    /** 账号 */
    private String username;

    /** 是否启用 */
    private Integer enabled;

    /** 是否只读（首期固定 1） */
    private Integer readOnly;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最近更新时间 */
    private LocalDateTime updateTime;
}
