package com.cl.agent.service;

import com.cl.agent.model.RagEvalCase;

import java.util.List;

/**
 * 检索评估用例持久化服务。
 */
public interface IRagEvalCaseService {

    /**
     * 列出知识库下的全部评估用例。
     *
     * @param kbId 知识库 ID，非空
     * @return 用例列表，按创建时间升序；无用例时返回空列表
     */
    List<RagEvalCase> listByKbId(String kbId);

    /**
     * 新增或更新评估用例（按主键判断）。
     *
     * @param evalCase 用例实体，{@code id} 非空
     * @return 无返回值；方法返回时已落库
     */
    void save(RagEvalCase evalCase);

    /**
     * 根据主键查询用例。
     *
     * @param id 用例 ID，非空
     * @return 用例实体；不存在时返回 null
     */
    RagEvalCase getById(String id);

    /**
     * 逻辑删除用例。
     *
     * @param id 用例 ID，非空
     * @return 无返回值
     */
    void deleteById(String id);

    /**
     * 逻辑删除知识库下的全部用例，删除知识库时调用。
     *
     * @param kbId 知识库 ID，非空
     * @return 无返回值
     */
    void deleteByKbId(String kbId);
}
