package com.cl.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 知识库文档（Knowledge Document）元数据明细实体类。
 * <p>对应 MySQL 中的 {@code t_knowledge_document} 表，继承自 {@link BaseEntity}。
 * 追踪记录用户上传到特定知识库中的单个物理文档，并提供状态机监控（uploading -> parsing -> indexed/failed）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName("t_knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    /** 文档唯一标识符 ID */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属知识库唯一的 ID */
    @TableField("kb_id")
    private String kbId;

    /** 上传的原始文档文件名 */
    @TableField("name")
    private String name;

    /** 文件扩展名类型 (pdf, txt, md, docx, xlsx, xml等) */
    @TableField("type")
    private String type;

    /** 解析状态: uploading/parsing/indexed/failed */
    @TableField("status")
    private String status;

    /** 文档物理文件字节大小 */
    @TableField("size_bytes")
    private Long sizeBytes;

    /** 文本总字数/字符数 */
    @TableField("char_count")
    private Integer charCount;

    /** 经切片算法分割后的切片总数 */
    @TableField("chunk_count")
    private Integer chunkCount;

    /** 在服务器本地存储的物理文件绝对路径 */
    @TableField("file_path")
    private String filePath;

    /** 状态为 failed 时的失败/异常描述信息 */
    @TableField("error_message")
    private String errorMessage;
}
