package com.cl.agent.dto.rag;

import lombok.Data;

import java.io.Serializable;

/**
 * 新增 / 修改检索评估用例的请求。
 */
@Data
public class RagEvalCaseRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用例 ID；为空表示新增，非空表示修改 */
    private String id;

    /** 所属知识库 ID，必填 */
    private String kbId;

    /** 测试问题，必填 */
    private String question;

    /** 标准答案要点，必填，每行一个（如字段名、错误码或关键结论），按不区分大小写的包含关系判定是否覆盖 */
    private String expectedPoints;
}
