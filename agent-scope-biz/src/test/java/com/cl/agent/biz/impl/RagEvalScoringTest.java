package com.cl.agent.biz.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检索评估要点覆盖判定单元测试。
 */
public class RagEvalScoringTest {

    /**
     * 要点拆分去掉列表符号与空行。
     */
    @Test
    public void splitPointsStripsBullets() {
        assertEquals(List.of("partnerOutBizNo", "BBLMAP00000", "金额单位为分"),
                RagEvalBizImpl.splitPoints("- partnerOutBizNo\n\n2. BBLMAP00000\r\n • 金额单位为分 "));
    }

    /**
     * 覆盖判定忽略大小写、空白与标点。
     */
    @Test
    public void missingPointsIgnoresCaseAndPunctuation() {
        String text = "| partnerOutBizNo | 商户订单号 |\n错误码：bblmap00000。金额单位为「分」";
        assertEquals(List.of("响应时间"),
                RagEvalBizImpl.missingPoints(List.of("PartnerOutBizNo", "BBLMAP00000", "金额单位为分", "响应时间"), text));
    }
}
