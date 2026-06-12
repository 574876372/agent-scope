package com.cl.agent.dto;

import java.io.Serializable;
import lombok.Data;

/**
 * 创建/更新私有知识库的请求参数 DTO。
 * <p>用于从 Web 层接收前端提交的知识库元数据配置，支持名称、业务描述及封面设置。</p>
 */
@Data
public class CreateKbRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库显示名称，必填 */
    private String name;

    /** 知识库详细业务描述，选填 */
    private String description;

    /** 知识库封面头像链接/标识，选填 */
    private String avatar;
}
