package com.cl.agent.biz;

import com.cl.agent.dto.rag.RagEvalCaseRequest;
import com.cl.agent.dto.rag.RagEvalCaseResponse;
import com.cl.agent.dto.rag.RagEvalRunResponse;

import java.util.List;

/**
 * 知识库检索评估业务接口（方案 P3）。
 * <p>维护「问题 + 标准答案要点」评估集，并一键运行：对每个问题执行与智能体相同的检索流水线，
 * 统计要点在检索上下文中的召回率；可选地用默认对话模型基于检索上下文生成回答，统计答案完整度。</p>
 */
public interface IRagEvalBiz {

    /**
     * 列出知识库的评估用例。
     *
     * @param kbId 知识库 ID，非空
     * @return 用例列表；无用例时返回空列表
     */
    List<RagEvalCaseResponse> listCases(String kbId);

    /**
     * 新增或修改评估用例。
     *
     * @param request 用例内容，{@code kbId}、{@code question}、{@code expectedPoints} 必填
     * @return 保存后的用例
     * @throws com.cl.agent.exception.BizException 必填项缺失（400）或知识库 / 用例不存在（404）时抛出
     */
    RagEvalCaseResponse saveCase(RagEvalCaseRequest request);

    /**
     * 删除评估用例。
     *
     * @param id 用例 ID，非空
     * @return 无返回值
     */
    void deleteCase(String id);

    /**
     * 运行知识库的全部评估用例。
     * <p>使用说明：同步执行，耗时与用例数成正比（每个用例一次检索，开启生成回答时再加一次模型调用）；
     * 评估时关闭查询改写，参数取全局默认，与未单独配置的智能体一致。</p>
     *
     * @param kbId       知识库 ID，非空
     * @param withAnswer 是否用默认对话模型生成回答并统计答案完整度
     * @return 汇总指标与各用例结果；无用例时 total 为 0
     * @throws com.cl.agent.exception.BizException 知识库不存在，或需要生成回答但未配置默认对话模型时抛出
     */
    RagEvalRunResponse run(String kbId, boolean withAnswer);
}
