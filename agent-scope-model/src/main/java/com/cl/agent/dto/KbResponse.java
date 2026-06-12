package com.cl.agent.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 知识库详细信息数据响应 DTO。
 * <p>面向前端展现知识库的主体元数据以及创建时间等要素，便于列表或配置卡片渲染。</p>
 */
@Data
public class KbResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库唯一标识符 ID */
    private String id;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 封面标识/头像 */
    private String avatar;

    /** 所属用户 ID */
    private String userId;

    /** 创建时间 */
    private LocalDateTime createTime;
}
