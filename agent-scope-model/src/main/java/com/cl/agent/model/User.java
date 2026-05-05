package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.*;

/**
 * 用户实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {
    
    /** 用户唯一标识 */
    @TableId(type = IdType.INPUT)
    private String id;
    
    /** 用户名 */
    @TableField("username")
    private String username;
    
    /** 密码 (加密后的) */
    @TableField("password")
    private String password;
}
