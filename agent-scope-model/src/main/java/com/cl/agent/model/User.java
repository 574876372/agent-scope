package com.cl.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    
    /** 用户唯一标识 */
    private String id;
    
    /** 用户名 */
    private String username;
    
    /** 密码 (加密后的) */
    private String password;
}
