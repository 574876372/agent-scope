package com.cl.agent.biz.impl;

import com.cl.agent.biz.IRagEvalBiz;
import com.cl.agent.biz.rag.RetrievalPipeline;
import com.cl.agent.dto.model.ModelConnection;
import com.cl.agent.dto.rag.RagEvalCaseRequest;
import com.cl.agent.dto.rag.RagEvalCaseResponse;
import com.cl.agent.dto.rag.RagEvalCaseResult;
import com.cl.agent.dto.rag.RagEvalRunResponse;
import com.cl.agent.dto.rag.RetrievalOptions;
import com.cl.agent.dto.rag.RetrievalTrace;
import com.cl.agent.exception.BizException;
import com.cl.agent.model.RagEvalCase;
import com.cl.agent.service.IKnowledgeService;
import com.cl.agent.service.IModelConfigService;
import com.cl.agent.service.IRagEvalCaseService;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库检索评估业务实现。
 * <p>要点覆盖判定：去除空白与常见标点、忽略大小写后做包含匹配。要点宜写成回答中必然出现的短语，
 * 如字段名 {@code partnerOutBizNo}、错误码 {@code BBLMAP00000}，而不是整句结论。</p>
 */
@Service
@Slf4j
public class RagEvalBizImpl implements IRagEvalBiz {

    /** 生成回答的请求超时时间 */
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(90);

    /** 生成回答的最大 token 数 */
    private static final long ANSWER_MAX_TOKENS = 2000;

    /** 生成回答时的系统提示词 */
    private static final String ANSWER_SYSTEM_PROMPT = "你是知识库问答助手。只依据用户提供的参考资料回答问题，"
            + "完整列出资料中与问题相关的全部信息（如参数表的每一行），资料中没有的内容请说明未检索到。";

    /** 判定要点覆盖时忽略的字符：空白与常见中英文标点 */
    private static final String IGNORED_CHARS = "[\\s，。、；：！？,.;:!?\"'“”‘’（）()【】\\[\\]《》<>「」『』|`*#-]";

    @Autowired
    private IRagEvalCaseService ragEvalCaseService;

    @Autowired
    private IKnowledgeService knowledgeService;

    @Autowired
    private IModelConfigService modelConfigService;

    @Autowired
    private RetrievalPipeline retrievalPipeline;

    /** {@inheritDoc} */
    @Override
    public List<RagEvalCaseResponse> listCases(String kbId) {
        return ragEvalCaseService.listByKbId(kbId).stream().map(RagEvalBizImpl::toResponse).collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    public RagEvalCaseResponse saveCase(RagEvalCaseRequest request) {
        if (isBlank(request.getKbId()) || isBlank(request.getQuestion()) || splitPoints(request.getExpectedPoints()).isEmpty()) {
            throw new BizException(400, "知识库、测试问题与标准答案要点均不能为空");
        }
        if (knowledgeService.getBaseById(request.getKbId()) == null) {
            throw new BizException(404, "知识库不存在: " + request.getKbId());
        }
        RagEvalCase evalCase;
        if (isBlank(request.getId())) {
            evalCase = RagEvalCase.builder().id(UUID.randomUUID().toString()).kbId(request.getKbId()).build();
            evalCase.setCreateTime(LocalDateTime.now());
        } else {
            evalCase = ragEvalCaseService.getById(request.getId());
            if (evalCase == null) {
                throw new BizException(404, "评估用例不存在: " + request.getId());
            }
        }
        evalCase.setQuestion(request.getQuestion().trim());
        evalCase.setExpectedPoints(String.join("\n", splitPoints(request.getExpectedPoints())));
        ragEvalCaseService.save(evalCase);
        return toResponse(evalCase);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteCase(String id) {
        ragEvalCaseService.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public RagEvalRunResponse run(String kbId, boolean withAnswer) {
        if (knowledgeService.getBaseById(kbId) == null) {
            throw new BizException(404, "知识库不存在: " + kbId);
        }
        long start = System.currentTimeMillis();
        // 需要生成回答时先解析默认对话模型，配置缺失直接报错，避免跑完检索才失败
        ModelConnection chat = withAnswer ? modelConfigService.resolveDefaultChatConnection() : null;
        List<RagEvalCase> cases = ragEvalCaseService.listByKbId(kbId);
        RagEvalRunResponse resp = new RagEvalRunResponse();
        for (RagEvalCase c : cases) {
            resp.getResults().add(runCase(kbId, c, chat));
        }
        resp.setTotal(cases.size());
        resp.setAvgContextRecall(resp.getResults().stream().mapToDouble(RagEvalCaseResult::getContextRecall).average().orElse(0));
        resp.setFullyRecalled((int) resp.getResults().stream().filter(r -> r.getContextRecall() >= 1.0).count());
        if (withAnswer) {
            resp.setAvgAnswerCompleteness(resp.getResults().stream()
                    .filter(r -> r.getAnswerCompleteness() != null)
                    .mapToDouble(RagEvalCaseResult::getAnswerCompleteness).average().orElse(0));
        }
        resp.setCostMs(System.currentTimeMillis() - start);
        log.info("[RAG-Eval] 评估完成: kbId={}, 用例={}, 平均召回率={}, 平均完整度={}, costMs={}",
                kbId, resp.getTotal(), resp.getAvgContextRecall(), resp.getAvgAnswerCompleteness(), resp.getCostMs());
        return resp;
    }

    /**
     * 运行单个用例：检索、统计召回率，按需生成回答并统计完整度。单个用例失败不影响其余用例。
     */
    private RagEvalCaseResult runCase(String kbId, RagEvalCase c, ModelConnection chat) {
        long start = System.currentTimeMillis();
        RagEvalCaseResult r = new RagEvalCaseResult();
        r.setCaseId(c.getId());
        r.setQuestion(c.getQuestion());
        List<String> points = splitPoints(c.getExpectedPoints());
        r.setPointCount(points.size());
        try {
            RetrievalTrace trace = retrievalPipeline.run(RetrievalOptions.builder()
                    .kbIds(List.of(kbId))
                    .query(c.getQuestion())
                    .queryRewrite(false)
                    .build());
            r.getWarnings().addAll(trace.getWarnings());
            r.setSegmentCount(trace.getSegments().size());
            String context = trace.getContextText() == null ? "" : trace.getContextText();
            r.setContextChars(trace.getSegments().stream().mapToInt(s -> s.getCharCount() == null ? 0 : s.getCharCount()).sum());
            r.setMissingInContext(missingPoints(points, context));
            r.setContextRecall(coverage(points.size(), r.getMissingInContext().size()));
            if (chat != null) {
                String answer = generateAnswer(chat, c.getQuestion(), context);
                r.setAnswer(answer);
                r.setMissingInAnswer(missingPoints(points, answer));
                r.setAnswerCompleteness(coverage(points.size(), r.getMissingInAnswer().size()));
            }
        } catch (Exception e) {
            log.warn("[RAG-Eval] 用例运行失败: caseId={}, reason={}", c.getId(), e.getMessage());
            r.getWarnings().add("运行失败: " + e.getMessage());
            if (r.getMissingInContext().isEmpty()) {
                r.setMissingInContext(points);
            }
        }
        r.setCostMs(System.currentTimeMillis() - start);
        return r;
    }

    /**
     * 用对话模型基于检索上下文回答问题。
     *
     * @return 回答文本；模型未返回内容时为空串
     */
    private String generateAnswer(ModelConnection chat, String question, String context) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(chat.getApiKey())
                .baseUrl(chat.getBaseUrl())
                .timeout(ANSWER_TIMEOUT)
                .maxRetries(1)
                .build();
        try {
            String user = (context.isEmpty() ? "（未检索到参考资料）" : context) + "\n\n问题：" + question;
            @SuppressWarnings("deprecation")
            ChatCompletion completion = client.chat().completions().create(ChatCompletionCreateParams.builder()
                    .model(chat.getModelName())
                    .addSystemMessage(ANSWER_SYSTEM_PROMPT)
                    .addUserMessage(user)
                    .temperature(0)
                    .maxTokens(ANSWER_MAX_TOKENS)
                    .build());
            return completion.choices().isEmpty() ? "" : completion.choices().get(0).message().content().orElse("");
        } finally {
            client.close();
        }
    }

    /**
     * 找出文本中未覆盖的要点。
     *
     * @param points 要点列表
     * @param text   被检查的文本，可为 null
     * @return 未覆盖的要点，保持原顺序
     */
    static List<String> missingPoints(List<String> points, String text) {
        String haystack = normalize(text);
        return points.stream().filter(p -> !haystack.contains(normalize(p))).collect(Collectors.toList());
    }

    /**
     * 按行拆分要点，去掉空行与首尾空白及行首的列表符号（- * • 1.）。
     *
     * @param raw 要点原文，可为 null
     * @return 要点列表；无有效要点时为空列表
     */
    static List<String> splitPoints(String raw) {
        List<String> points = new ArrayList<>();
        if (raw == null) {
            return points;
        }
        for (String line : raw.split("\\r?\\n")) {
            String p = line.strip().replaceFirst("^([-*•]|\\d+[.、)）])\\s*", "").strip();
            if (!p.isEmpty()) {
                points.add(p);
            }
        }
        return points;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll(IGNORED_CHARS, "").toLowerCase(Locale.ROOT);
    }

    private static double coverage(int total, int missing) {
        return total == 0 ? 0 : (double) (total - missing) / total;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static RagEvalCaseResponse toResponse(RagEvalCase c) {
        RagEvalCaseResponse resp = new RagEvalCaseResponse();
        resp.setId(c.getId());
        resp.setKbId(c.getKbId());
        resp.setQuestion(c.getQuestion());
        resp.setExpectedPoints(c.getExpectedPoints());
        resp.setCreateTime(c.getCreateTime());
        return resp;
    }
}
