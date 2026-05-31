package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 用户注册的外部业务数据源实体，对应 {@code t_datasource} 表。
 *
 * <p>由 {@code IDatasourceService} 负责 CRUD，{@code HostDatasourceProvider} 通过该实体的
 * {@link #jdbcUrl} / {@link #username} / {@link #passwordCipher} 构造 HikariDataSource。
 * 密码字段始终以 AES-GCM 加密形式持久化，由 starter 提供的 {@code CryptoService} 加解密。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_datasource")
public class Datasource extends BaseEntity {

    /** 主键 UUID（业务层在 insert 时填充） */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属用户 ID（多租户隔离，与 t_user.id 对齐） */
    @TableField("user_id")
    private String userId;

    /** 展示名称，如「线上订单库」 */
    @TableField("name")
    private String name;

    /** 用途描述，是 LLM 选择 datasourceId 的关键决策依据，**强烈建议填详尽业务语义** */
    @TableField("description")
    private String description;

    /** 数据库类型：mysql / postgres（首期仅 mysql） */
    @TableField("db_type")
    private String dbType;

    /** JDBC URL，如 {@code jdbc:mysql://host:3306/db?useSSL=false&characterEncoding=utf8} */
    @TableField("jdbc_url")
    private String jdbcUrl;

    /** 账号（建议外部库使用只读账号） */
    @TableField("username")
    private String username;

    /** AES-GCM 加密后的密码 Base64，**禁止以明文形式落库** */
    @TableField("password_cipher")
    private String passwordCipher;

    /** 是否强制只读：1=只读（HikariCP readOnly=true），首期固定 1 */
    @TableField("read_only")
    private Integer readOnly;

    /** 是否启用：0=禁用（不出现在 list_datasources），1=启用 */
    @TableField("enabled")
    private Integer enabled;
}
