package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 模型厂商（Model Provider）连接信息实体类。
 * <p>对应 MySQL 中的 {@code t_model_provider} 表。保存某个厂商的接口地址与密钥，
 * 同一厂商下的对话模型与向量模型共用这一份连接信息。
 * 密钥始终以 AES-GCM 加密形式持久化，由公共组件 {@code CryptoService} 加解密。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "apiKeyCipher")
@TableName("t_model_provider")
public class ModelProvider extends BaseEntity {

    /** 厂商唯一标识符 ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 厂商编码，创建后不可修改；智能体的 modelType 字段引用该值（如 Qwen / DeepSeek / OpenAI） */
    @TableField("code")
    private String code;

    /** 厂商显示名称 */
    @TableField("name")
    private String name;

    /** 接口协议，对应 ModelProtocolEnum（当前仅 OPENAI） */
    @TableField("protocol")
    private String protocol;

    /** 接口基础地址 */
    @TableField("base_url")
    private String baseUrl;

    /** AES-GCM 加密后的 API Key Base64，**禁止以明文形式落库**；为空表示尚未配置 */
    @TableField("api_key_cipher")
    private String apiKeyCipher;

    /** 是否启用 1 启用 0 停用 */
    @TableField("enabled")
    private Integer enabled;
}
