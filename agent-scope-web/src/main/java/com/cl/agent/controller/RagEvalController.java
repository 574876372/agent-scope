package com.cl.agent.controller;

import com.cl.agent.biz.IRagEvalBiz;
import com.cl.agent.dto.rag.RagEvalCaseRequest;
import com.cl.agent.dto.rag.RagEvalCaseResponse;
import com.cl.agent.dto.rag.RagEvalRunResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识库检索评估 REST 控制器。
 * <p>维护「问题 + 标准答案要点」评估集并一键运行，输出召回率与答案完整度；路径前缀 {@code /api/knowledge-base/eval}，
 * 业务 ID 一律通过查询参数传递。</p>
 */
@RestController
@RequestMapping("/api/knowledge-base/eval")
public class RagEvalController {

    @Autowired
    private IRagEvalBiz ragEvalBiz;

    /**
     * 列出知识库的评估用例。
     * <p>使用说明：GET {@code /api/knowledge-base/eval/case/list?kbId=}。</p>
     *
     * @param kbId 知识库 ID，非空，通过查询参数 {@code ?kbId=} 传入
     * @return {@link ResponseEntity} 用例列表，HTTP 200；无用例时为空列表
     */
    @GetMapping("/case/list")
    public ResponseEntity<List<RagEvalCaseResponse>> listCases(@RequestParam("kbId") String kbId) {
        return ResponseEntity.ok(ragEvalBiz.listCases(kbId));
    }

    /**
     * 新增或修改评估用例（请求体 {@code id} 为空时新增）。
     * <p>使用说明：POST {@code /api/knowledge-base/eval/case/save}，要点每行一个。</p>
     *
     * @param request 用例内容，{@code kbId}、{@code question}、{@code expectedPoints} 必填
     * @return {@link ResponseEntity} 保存后的用例，HTTP 200；必填项缺失 400，知识库或用例不存在 404
     */
    @PostMapping("/case/save")
    public ResponseEntity<RagEvalCaseResponse> saveCase(@RequestBody RagEvalCaseRequest request) {
        return ResponseEntity.ok(ragEvalBiz.saveCase(request));
    }

    /**
     * 删除评估用例。
     * <p>使用说明：DELETE {@code /api/knowledge-base/eval/case/delete?id=}。</p>
     *
     * @param id 用例 ID，非空，通过查询参数 {@code ?id=} 传入
     * @return {@link ResponseEntity} 无内容，HTTP 204
     */
    @DeleteMapping("/case/delete")
    public ResponseEntity<Void> deleteCase(@RequestParam("id") String id) {
        ragEvalBiz.deleteCase(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 一键运行知识库的全部评估用例。
     * <p>使用说明：POST {@code /api/knowledge-base/eval/run?kbId=&withAnswer=}；同步执行，用例多时耗时较长。
     * {@code withAnswer=true} 时用默认对话模型基于检索上下文生成回答并统计答案完整度。</p>
     *
     * @param kbId       知识库 ID，非空，通过查询参数 {@code ?kbId=} 传入
     * @param withAnswer 是否生成回答，默认 false
     * @return {@link ResponseEntity} 汇总指标与各用例结果，HTTP 200
     */
    @PostMapping("/run")
    public ResponseEntity<RagEvalRunResponse> run(@RequestParam("kbId") String kbId,
                                                  @RequestParam(value = "withAnswer", defaultValue = "false") boolean withAnswer) {
        return ResponseEntity.ok(ragEvalBiz.run(kbId, withAnswer));
    }
}
