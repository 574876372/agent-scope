package com.cl.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cl.agent.dao.RagEvalCaseMapper;
import com.cl.agent.model.RagEvalCase;
import com.cl.agent.service.IRagEvalCaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索评估用例持久化服务实现。
 */
@Service
public class RagEvalCaseServiceImpl implements IRagEvalCaseService {

    @Autowired
    private RagEvalCaseMapper ragEvalCaseMapper;

    /** {@inheritDoc} */
    @Override
    public List<RagEvalCase> listByKbId(String kbId) {
        List<RagEvalCase> list = ragEvalCaseMapper.selectList(new LambdaQueryWrapper<RagEvalCase>()
                .eq(RagEvalCase::getKbId, kbId)
                .orderByAsc(RagEvalCase::getCreateTime));
        return list != null ? list : List.of();
    }

    /** {@inheritDoc} */
    @Override
    public void save(RagEvalCase evalCase) {
        if (ragEvalCaseMapper.selectById(evalCase.getId()) != null) {
            ragEvalCaseMapper.updateById(evalCase);
        } else {
            ragEvalCaseMapper.insert(evalCase);
        }
    }

    /** {@inheritDoc} */
    @Override
    public RagEvalCase getById(String id) {
        return ragEvalCaseMapper.selectById(id);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(String id) {
        ragEvalCaseMapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteByKbId(String kbId) {
        ragEvalCaseMapper.delete(new LambdaQueryWrapper<RagEvalCase>().eq(RagEvalCase::getKbId, kbId));
    }
}
