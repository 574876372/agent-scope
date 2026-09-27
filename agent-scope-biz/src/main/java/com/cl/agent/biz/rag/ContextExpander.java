package com.cl.agent.biz.rag;

import com.cl.agent.dto.rag.RetrievalHit;
import com.cl.agent.dto.rag.RetrievalSegment;
import com.cl.agent.model.KnowledgeBase;
import com.cl.agent.model.KnowledgeChunk;
import com.cl.agent.service.IKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 上下文扩展器：把排好序的命中切片扩展、合并为若干段连续原文（检索流水线第 ⑤ 步）。
 * <p>规则（见方案第四节）：</p>
 * <ul>
 *   <li>技术 / 接口文档：命中切片所在章节总长不超过上限时整章带入；超过时在章节内以命中位置为中心尽量取满上限（章节节选）；</li>
 *   <li>通用文档、表格数据：前后各 N 片；FAQ：不扩展；N 取知识库级配置或类型预设；</li>
 *   <li>同一文档中重叠或相邻的范围合并为一段，相邻切片之间的重叠文字与重复表头去重；</li>
 *   <li>最多保留 Top-K 段，总长超过上限时按融合排名从低到高舍弃，排名最高的一段过长时截断。</li>
 * </ul>
 */
@Slf4j
@Component
public class ContextExpander {

    /** 判定相邻切片存在重叠文字的最短长度，太短容易误判 */
    private static final int MIN_OVERLAP = 10;

    /** 查找相邻切片重叠文字的最大长度 */
    private static final int MAX_OVERLAP = 400;

    /** 扩展方式由弱到强的顺序，合并段时取更强者 */
    private static final List<String> MODE_ORDER = List.of("none", "window", "section-partial", "section");

    /** 截断时附加的提示 */
    private static final String TRUNCATED_MARK = "\n……（内容过长，已截断）";

    @Autowired
    private IKnowledgeService knowledgeService;

    @Autowired
    private KbConfigResolver kbConfigResolver;

    /**
     * 扩展并合并命中切片。
     *
     * @param rankedHits      已按最终排名（融合 / 重排）升序排列的命中切片，可为空
     * @param kbMap           知识库 ID 到实体的映射，用于读取类型与扩展窗口；缺失的知识库按通用文档处理
     * @param finalTopK       最多保留的段数，正数
     * @param maxChars        所有段原文总字符数上限，正数
     * @param sectionMaxChars 整章带入时单个章节的字符数上限；≤0 表示与 maxChars 一致
     * @return 最终来源段，已按「文档首次出现的排名 → 文档内切片顺序」排列并分配引用编号；无命中时返回空列表
     */
    public List<RetrievalSegment> expand(List<RetrievalHit> rankedHits, Map<String, KnowledgeBase> kbMap,
                                         int finalTopK, int maxChars, int sectionMaxChars) {
        List<RetrievalSegment> segments = buildRanges(rankedHits, kbMap, finalTopK, maxChars, sectionMaxChars);
        if (segments.isEmpty()) {
            return segments;
        }
        Map<RetrievalSegment, List<KnowledgeChunk>> chunksBySegment = materialize(segments, rankedHits);
        List<RetrievalSegment> accepted = applyBudget(segments, chunksBySegment, maxChars);
        return orderAndCite(accepted);
    }

    // ========================================================
    // 1. 计算每段的切片序号范围
    // ========================================================

    /**
     * 按排名依次处理命中切片，计算扩展范围并与同文档中重叠或相邻的段合并。
     */
    private List<RetrievalSegment> buildRanges(List<RetrievalHit> rankedHits, Map<String, KnowledgeBase> kbMap,
                                               int finalTopK, int maxChars, int sectionMaxChars) {
        List<RetrievalSegment> segments = new ArrayList<>();
        // 章节切片缓存，键为 docId|sectionPath，避免同一章节多次查询
        Map<String, List<KnowledgeChunk>> sectionCache = new HashMap<>();
        int rank = 0;
        for (RetrievalHit hit : rankedHits) {
            rank++;
            if (hit.getDocId() == null || hit.getChunkIndex() == null) {
                continue;
            }
            KnowledgeBase kb = kbMap.get(hit.getKbId());
            int idx = hit.getChunkIndex();
            int lo = idx;
            int hi = idx;
            String mode = "none";
            boolean sectionApplied = false;
            if (kbConfigResolver.wholeSection(kb) && hit.getSectionPath() != null && !hit.getSectionPath().isBlank()) {
                List<KnowledgeChunk> section = sectionCache.computeIfAbsent(hit.getDocId() + "|" + hit.getSectionPath(),
                        k -> knowledgeService.listChunksBySection(hit.getDocId(), hit.getSectionPath()));
                // 整章上限：未单独配置（≤0）时与上下文总长上限一致，保证大参数表能整章带入
                int limit = sectionMaxChars > 0 ? Math.min(sectionMaxChars, maxChars) : maxChars;
                int total = section.stream().mapToInt(ContextExpander::length).sum();
                if (!section.isEmpty() && total <= limit) {
                    lo = Math.min(idx, section.get(0).getChunkIndex());
                    hi = Math.max(idx, section.get(section.size() - 1).getChunkIndex());
                    mode = "section";
                    sectionApplied = true;
                } else if (!section.isEmpty()) {
                    // 章节超过上限：在章节内以命中切片为中心向两侧扩展，尽量取满上限，而不是只补前后几片
                    int[] range = sectionExcerpt(section, idx, limit);
                    if (range != null) {
                        lo = range[0];
                        hi = range[1];
                        mode = "section-partial";
                        sectionApplied = true;
                    }
                }
            }
            if (!sectionApplied) {
                int window = kbConfigResolver.contextWindow(kb);
                if (window > 0) {
                    lo = Math.max(0, idx - window);
                    hi = idx + window;
                    mode = "window";
                }
            }
            double score = hit.getRerankScore() != null ? hit.getRerankScore()
                    : (hit.getFusedScore() != null ? hit.getFusedScore() : 0.0);

            RetrievalSegment target = findMergeable(segments, hit.getDocId(), lo, hi);
            if (target == null) {
                // 已满 Top-K 段时，排名更低且不与已有段相连的命中直接舍弃
                if (segments.size() >= finalTopK) {
                    continue;
                }
                RetrievalSegment seg = new RetrievalSegment();
                seg.setKbId(hit.getKbId());
                seg.setKbName(hit.getKbName());
                seg.setDocId(hit.getDocId());
                seg.setDocName(hit.getDocName());
                seg.setSectionPath(hit.getSectionPath());
                seg.setStartIndex(lo);
                seg.setEndIndex(hi);
                seg.getHitIndexes().add(idx);
                seg.setBestRank(rank);
                seg.setScore(score);
                seg.setExpandMode(mode);
                segments.add(seg);
            } else {
                target.setStartIndex(Math.min(target.getStartIndex(), lo));
                target.setEndIndex(Math.max(target.getEndIndex(), hi));
                if (!target.getHitIndexes().contains(idx)) {
                    target.getHitIndexes().add(idx);
                }
                target.setScore(Math.max(target.getScore(), score));
                target.setExpandMode(strongerMode(target.getExpandMode(), mode));
                absorbOverlapping(segments, target);
            }
        }
        return segments;
    }

    /**
     * 章节节选：从命中切片开始，交替向后、向前纳入同章节的相邻切片，直到再加一片就超过上限。
     * <p>先向后扩展，因为参数表、步骤说明等内容通常从命中位置往后延续。</p>
     *
     * @param section 章节全部切片，按序号升序，非空
     * @param idx     命中切片序号
     * @param limit   字符数上限
     * @return {起始序号, 结束序号}；命中切片不在该章节内时返回 null
     */
    static int[] sectionExcerpt(List<KnowledgeChunk> section, int idx, int limit) {
        int pos = -1;
        for (int i = 0; i < section.size(); i++) {
            if (section.get(i).getChunkIndex() == idx) {
                pos = i;
                break;
            }
        }
        if (pos < 0) {
            return null;
        }
        int lo = pos;
        int hi = pos;
        int total = length(section.get(pos));
        boolean forward = true;
        while (lo > 0 || hi < section.size() - 1) {
            boolean canForward = hi < section.size() - 1 && total + length(section.get(hi + 1)) <= limit;
            boolean canBackward = lo > 0 && total + length(section.get(lo - 1)) <= limit;
            if (!canForward && !canBackward) {
                break;
            }
            if ((forward && canForward) || !canBackward) {
                hi++;
                total += length(section.get(hi));
            } else {
                lo--;
                total += length(section.get(lo));
            }
            forward = !forward;
        }
        return new int[]{section.get(lo).getChunkIndex(), section.get(hi).getChunkIndex()};
    }

    /**
     * 合并两个段时取覆盖范围更大的扩展方式：整章 > 章节节选 > 相邻切片 > 不扩展。
     *
     * @param a 扩展方式，可为 null
     * @param b 扩展方式，可为 null
     * @return 两者中更强的扩展方式
     */
    static String strongerMode(String a, String b) {
        return MODE_ORDER.indexOf(a == null ? "none" : a) >= MODE_ORDER.indexOf(b == null ? "none" : b) ? a : b;
    }

    private static int length(KnowledgeChunk c) {
        return c.getContent() == null ? 0 : c.getContent().length();
    }

    /**
     * 查找同文档中与 [lo, hi] 重叠或相邻的段。
     */
    private static RetrievalSegment findMergeable(List<RetrievalSegment> segments, String docId, int lo, int hi) {
        for (RetrievalSegment s : segments) {
            if (s.getDocId().equals(docId) && lo <= s.getEndIndex() + 1 && hi >= s.getStartIndex() - 1) {
                return s;
            }
        }
        return null;
    }

    /**
     * 段扩大后可能与同文档的其他段相连，合并到 target 中。
     */
    private static void absorbOverlapping(List<RetrievalSegment> segments, RetrievalSegment target) {
        boolean merged = true;
        while (merged) {
            merged = false;
            for (RetrievalSegment s : new ArrayList<>(segments)) {
                if (s == target || !s.getDocId().equals(target.getDocId())) {
                    continue;
                }
                if (s.getStartIndex() <= target.getEndIndex() + 1 && s.getEndIndex() >= target.getStartIndex() - 1) {
                    target.setStartIndex(Math.min(target.getStartIndex(), s.getStartIndex()));
                    target.setEndIndex(Math.max(target.getEndIndex(), s.getEndIndex()));
                    s.getHitIndexes().stream().filter(i -> !target.getHitIndexes().contains(i))
                            .forEach(i -> target.getHitIndexes().add(i));
                    target.setBestRank(Math.min(target.getBestRank(), s.getBestRank()));
                    target.setScore(Math.max(target.getScore(), s.getScore()));
                    target.setExpandMode(strongerMode(target.getExpandMode(), s.getExpandMode()));
                    segments.remove(s);
                    merged = true;
                }
            }
        }
    }

    // ========================================================
    // 2. 读取切片原文
    // ========================================================

    /**
     * 按范围读取每段的切片并拼接原文；切片表中缺失（仅存在于向量库的旧数据）时使用命中切片自带的原文。
     *
     * @return 段到其切片列表的映射（按身份区分段）
     */
    private Map<RetrievalSegment, List<KnowledgeChunk>> materialize(List<RetrievalSegment> segments,
                                                                     List<RetrievalHit> rankedHits) {
        Map<String, RetrievalHit> hitByKey = new HashMap<>();
        for (RetrievalHit h : rankedHits) {
            hitByKey.putIfAbsent(h.key(), h);
        }
        Map<RetrievalSegment, List<KnowledgeChunk>> result = new IdentityHashMap<>();
        for (RetrievalSegment seg : segments) {
            List<KnowledgeChunk> chunks = knowledgeService.listChunksInRange(seg.getDocId(), seg.getStartIndex(), seg.getEndIndex());
            if (chunks.isEmpty()) {
                chunks = new ArrayList<>();
                for (Integer idx : seg.getHitIndexes()) {
                    RetrievalHit h = hitByKey.get(seg.getDocId() + "#" + idx);
                    if (h != null && h.getContent() != null) {
                        KnowledgeChunk c = new KnowledgeChunk();
                        c.setDocId(seg.getDocId());
                        c.setChunkIndex(idx);
                        c.setContent(h.getContent());
                        c.setSectionPath(h.getSectionPath());
                        chunks.add(c);
                    }
                }
                chunks.sort(Comparator.comparing(KnowledgeChunk::getChunkIndex));
            }
            result.put(seg, chunks);
            fillContent(seg, chunks);
        }
        return result;
    }

    /**
     * 用切片列表填充段的原文、实际序号范围与章节路径。
     */
    private static void fillContent(RetrievalSegment seg, List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) {
            seg.setContent("");
            seg.setCharCount(0);
            return;
        }
        // 窗口可能越过文档末尾，以实际读到的切片为准
        seg.setStartIndex(chunks.get(0).getChunkIndex());
        seg.setEndIndex(chunks.get(chunks.size() - 1).getChunkIndex());
        String hitSection = chunks.stream()
                .filter(c -> seg.getHitIndexes().contains(c.getChunkIndex()))
                .map(KnowledgeChunk::getSectionPath)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse(chunks.stream().map(KnowledgeChunk::getSectionPath)
                        .filter(p -> p != null && !p.isBlank()).findFirst().orElse(seg.getSectionPath()));
        seg.setSectionPath(hitSection);
        String content = joinChunks(chunks.stream().map(KnowledgeChunk::getContent).collect(Collectors.toList()));
        seg.setContent(content);
        seg.setCharCount(content.length());
    }

    // ========================================================
    // 3. 总长预算
    // ========================================================

    /**
     * 按排名依次纳入各段；放不下时先收缩到命中切片本身，仍放不下则舍弃，排名第一的段过长时截断。
     */
    private List<RetrievalSegment> applyBudget(List<RetrievalSegment> segments,
                                               Map<RetrievalSegment, List<KnowledgeChunk>> chunksBySegment, int maxChars) {
        List<RetrievalSegment> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparingInt(RetrievalSegment::getBestRank));
        List<RetrievalSegment> accepted = new ArrayList<>();
        int remaining = maxChars;
        for (RetrievalSegment seg : ordered) {
            if (seg.getCharCount() == 0) {
                continue;
            }
            if (seg.getCharCount() > remaining) {
                shrinkToHits(seg, chunksBySegment.get(seg));
            }
            if (seg.getCharCount() > remaining) {
                if (!accepted.isEmpty()) {
                    log.debug("[RAG-Expand] 超出上下文上限，舍弃来源段: doc={}, range={}-{}, rank={}",
                            seg.getDocName(), seg.getStartIndex(), seg.getEndIndex(), seg.getBestRank());
                    continue;
                }
                int keep = Math.max(0, remaining - TRUNCATED_MARK.length());
                seg.setContent(seg.getContent().substring(0, Math.min(keep, seg.getContent().length())) + TRUNCATED_MARK);
                seg.setCharCount(seg.getContent().length());
                seg.setExpandMode("trimmed");
            }
            accepted.add(seg);
            remaining -= seg.getCharCount();
            if (remaining <= 0) {
                break;
            }
        }
        return accepted;
    }

    /**
     * 把段收缩到命中切片覆盖的最小范围（去掉扩展进来的相邻切片）。
     */
    private static void shrinkToHits(RetrievalSegment seg, List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || seg.getHitIndexes().isEmpty()) {
            return;
        }
        int minHit = seg.getHitIndexes().stream().min(Integer::compare).orElse(seg.getStartIndex());
        int maxHit = seg.getHitIndexes().stream().max(Integer::compare).orElse(seg.getEndIndex());
        List<KnowledgeChunk> core = chunks.stream()
                .filter(c -> c.getChunkIndex() >= minHit && c.getChunkIndex() <= maxHit)
                .collect(Collectors.toList());
        if (core.isEmpty() || core.size() == chunks.size()) {
            return;
        }
        fillContent(seg, core);
        seg.setExpandMode("trimmed");
    }

    // ========================================================
    // 4. 排序与引用编号
    // ========================================================

    /**
     * 同一文档的段排在一起并按原文顺序排列，文档之间按其最佳排名排列；依次分配引用编号。
     */
    private static List<RetrievalSegment> orderAndCite(List<RetrievalSegment> accepted) {
        Map<String, Integer> docBestRank = new LinkedHashMap<>();
        for (RetrievalSegment s : accepted) {
            docBestRank.merge(s.getDocId(), s.getBestRank(), Math::min);
        }
        List<RetrievalSegment> ordered = new ArrayList<>(accepted);
        ordered.sort(Comparator.<RetrievalSegment>comparingInt(s -> docBestRank.get(s.getDocId()))
                .thenComparing(RetrievalSegment::getDocId)
                .thenComparingInt(RetrievalSegment::getStartIndex));
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setCitation(i + 1);
            ordered.get(i).getHitIndexes().sort(Integer::compare);
        }
        return ordered;
    }

    // ========================================================
    // 文本拼接
    // ========================================================

    /**
     * 按顺序拼接相邻切片：去掉与上一片末尾重叠的文字（旧版固定长度切片带重叠），
     * 以及表格拆分时每片重复的表头。
     *
     * @param contents 切片原文，按序号升序
     * @return 拼接后的连续原文
     */
    static String joinChunks(List<String> contents) {
        StringBuilder sb = new StringBuilder();
        for (String raw : contents) {
            String next = raw == null ? "" : raw;
            if (next.isEmpty()) {
                continue;
            }
            if (sb.length() == 0) {
                sb.append(next);
                continue;
            }
            next = stripRepeatedTableHeader(sb, next);
            int overlap = overlapLength(sb, next);
            if (overlap > 0) {
                sb.append(next.substring(overlap));
            } else {
                sb.append('\n').append(next);
            }
        }
        return sb.toString();
    }

    /**
     * 下一片以表头 + 分隔行开头且与前文中出现过的表头相同时，去掉重复的表头。
     */
    private static String stripRepeatedTableHeader(CharSequence previous, String next) {
        String[] lines = next.split("\n", 3);
        if (lines.length < 2) {
            return next;
        }
        String header = lines[0].trim();
        String separator = lines[1].trim();
        if (!header.startsWith("|") || !separator.matches("^\\|?\\s*:?-{2,}.*")) {
            return next;
        }
        if (previous.toString().contains(header + "\n" + separator) || previous.toString().contains(lines[0] + "\n" + lines[1])) {
            return lines.length == 3 ? lines[2] : "";
        }
        return next;
    }

    /**
     * 计算 previous 末尾与 next 开头重叠的最大长度（不小于 {@link #MIN_OVERLAP}），无重叠返回 0。
     */
    private static int overlapLength(CharSequence previous, String next) {
        int max = Math.min(MAX_OVERLAP, Math.min(previous.length(), next.length()));
        String tail = previous.subSequence(previous.length() - max, previous.length()).toString();
        for (int len = max; len >= MIN_OVERLAP; len--) {
            if (tail.endsWith(next.substring(0, len))) {
                return len;
            }
        }
        return 0;
    }
}
