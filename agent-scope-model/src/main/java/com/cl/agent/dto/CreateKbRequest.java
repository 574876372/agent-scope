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

    /** 绑定的向量模型 ID，选填；为空时使用默认向量模型，创建后不可更换 */
    private String embeddingModelId;

    /** 知识库类型：GENERAL / TECH_DOC / FAQ / TABLE，选填；为空时为 GENERAL */
    private String kbType;

    /** 切片策略：SECTION / QA / TABLE_ROW，选填；为空时使用类型预设 */
    private String chunkStrategy;

    /** 单个切片最大字符数，选填（100 ~ 4000）；为空时使用类型预设或全局默认 */
    private Integer chunkSize;

    /** 超长段落拆分时的重叠字符数，选填（0 ~ chunkSize/2）；为空时使用全局默认 */
    private Integer chunkOverlap;

    /** 检索命中后前后各补充的相邻切片数，选填（0 ~ 5）；为空时使用类型预设 */
    private Integer contextWindow;
}
