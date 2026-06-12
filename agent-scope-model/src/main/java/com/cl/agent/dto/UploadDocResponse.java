package com.cl.agent.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 上传知识库文档的响应数据 DTO。
 * <p>提供当前上传文件在服务器落盘后的属性明细、解析状态机（uploading/parsing/indexed/failed）及错误提示。</p>
 */
@Data
public class UploadDocResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档唯一标识 ID */
    private String id;

    /** 所属知识库唯一的 ID */
    private String kbId;

    /** 上传文档的文件名 */
    private String name;

    /** 扩展名类型 (pdf, docx, txt 等) */
    private String type;

    /** 物理文件字节大小 */
    private Long sizeBytes;

    /** 切片状态机：uploading/parsing/indexed/failed */
    private String status;

    /** 文档文本总字数 */
    private Integer charCount;

    /** 文档文本段落切片总数 */
    private Integer chunkCount;

    /** 异常解析时的错误信息描述 */
    private String errorMessage;

    /** 创建/上传时间 */
    private LocalDateTime createTime;
}
