package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 检索评估用例响应。
 */
@Data
public class RagEvalCaseResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用例 ID */
    private String id;

    /** 所属知识库 ID */
    private String kbId;

    /** 测试问题 */
    private String question;

    /** 标准答案要点，每行一个 */
    private String expectedPoints;

    /** 创建时间 */
    private LocalDateTime createTime;
}
