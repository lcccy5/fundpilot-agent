package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.exception.AgentEvidenceViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 检查并在可能时补上回答中的证据引用。
 * 没有任何证据却写出引用、一次补引用后仍缺少正确类型的证据时，抛出证据异常，回答不得返回。
 * 计划、路由和审批的失败不在这里被改写成已引用事实；对等代理未产出证据时同样拒绝编造引用。
 */
public final class FundAgentCitationPolicy {
    private static final Pattern CITATION = Pattern.compile(
            "(?:DOC:[A-Za-z0-9._:-]+|ev-[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)");
    private static final Pattern NUMERIC = Pattern.compile("(?<![A-Za-z])(?:\\d{6}|[-+]?\\d+(?:\\.\\d+)?%)");
    private static final Set<String> DOCUMENT_EVIDENCE = Set.of(
            "FUND_DOCUMENT", "COMPANY_ANNOUNCEMENT", "GOVERNMENT_POLICY", "VERIFIED_NEWS");

    /**
     * 去掉不存在的引用，并为缺少兼容证据的声明补上一条引用。
     * 证据列表为空但回答含引用时直接拒绝。补引用找不到正确类型，或补完后复查仍失败时抛出
     * {@link AgentEvidenceViolationException}。全部声明都已兼容时返回清洗后的原文。
     */
    public String validateAndRepair(String answer, List<EvidenceReference> evidence) {
        Set<String> allowed = evidence.stream().map(EvidenceReference::evidenceId).collect(Collectors.toSet());
        if (allowed.isEmpty()) {
            Matcher matcher = CITATION.matcher(answer);
            if (matcher.find()) {
                throw new AgentEvidenceViolationException("模型生成了本次运行不存在的证据引用");
            }
            return answer;
        }
        String sanitized = removeInvalidCitations(answer, allowed);
        List<AnswerClaim> claims = claims(sanitized);
        List<String> missing = new ArrayList<>();
        for (AnswerClaim claim : claims) {
            if (requiresEvidence(claim.type()) && !hasCompatibleEvidence(claim, evidence)) {
                missing.add(claim.claimId());
            }
        }
        if (missing.isEmpty()) {
            return sanitized;
        }
        StringBuilder repaired = new StringBuilder();
        for (AnswerClaim claim : claims) {
            if (requiresEvidence(claim.type()) && !hasCompatibleEvidence(claim, evidence)) {
                EvidenceReference selected = select(claim, evidence);
                if (selected == null) {
                    throw new AgentEvidenceViolationException("Claim 缺少正确类型的可用证据");
                }
                repaired.append(withCitation(claim.text(), format(selected)));
            } else {
                repaired.append(claim.text());
            }
        }
        List<AnswerClaim> repairedClaims = claims(repaired.toString());
        for (AnswerClaim claim : repairedClaims) {
            if (requiresEvidence(claim.type()) && !hasCompatibleEvidence(claim, evidence)) {
                throw new AgentEvidenceViolationException("一次修复后 Claim 仍缺少正确类型证据");
            }
        }
        return repaired.toString();
    }

    /**
     * 删除不在本次证据集合中的引用，并收掉因此留下的空括号和多余空格。
     * 合法引用原样保留。清洗不会发明新的证据编号。
     */
    private String removeInvalidCitations(String answer, Set<String> allowed) {
        Matcher matcher = CITATION.matcher(answer);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sanitized, allowed.contains(matcher.group())
                    ? Matcher.quoteReplacement(matcher.group())
                    : "");
        }
        matcher.appendTail(sanitized);
        return sanitized.toString().replaceAll("【\\s*】", "").replaceAll("[ \\t]{2,}", " ");
    }

    /**
     * 按句末标点把回答拆成声明，并收集每句里的引用编号。
     * 空片段被跳过。无法分类时归为通识教育，不因此拒绝整段回答。
     */
    public List<AnswerClaim> claims(String answer) {
        String[] sentences = answer.split("(?<=[。！？!?\\n])", -1);
        List<AnswerClaim> result = new ArrayList<>();
        int index = 1;
        for (String text : sentences) {
            if (text.isEmpty()) {
                continue;
            }
            Matcher matcher = CITATION.matcher(text);
            List<String> ids = new ArrayList<>();
            while (matcher.find()) {
                ids.add(matcher.group());
            }
            result.add(new AnswerClaim("claim-" + index++, type(text), text, ids));
        }
        return List.copyOf(result);
    }

    /**
     * 为句子选择声明类型。
     * 免责声明优先于文档和数字，避免风险提示被当成必须引用的事实。
     * 都不匹配时归为通识教育。
     */
    private ClaimType type(String text) {
        if (text.contains("历史表现不代表") || text.contains("仅供参考") || text.contains("不构成投资建议")) {
            return ClaimType.LIMITATION;
        }
        if (text.contains("公告") || text.contains("招募说明书") || text.contains("季度报告")
                || text.contains("半年度报告") || text.contains("年度报告") || text.contains("定期报告")
                || text.contains("投资策略") || text.contains("投资范围") || text.contains("业绩比较基准")
                || text.contains("基金经理观点") || text.contains("基金经理强调") || text.contains("基金经理表示")
                || text.contains("原文") || text.contains("条款") || text.contains("利好") || text.contains("利空")
                || text.contains("事件")) {
            return ClaimType.DOCUMENT_FACT;
        }
        if (NUMERIC.matcher(text).find()) {
            return ClaimType.NUMERIC_FACT;
        }
        if (text.contains("可能") || text.contains("表明") || text.contains("意味着") || text.contains("基于")) {
            return ClaimType.INTERPRETATION;
        }
        return ClaimType.GENERAL_EDUCATION;
    }

    /**
     * 判断该类型是否必须带有兼容证据。
     * 局限性和通识教育返回 false，缺少引用也不会导致修复失败。
     */
    private boolean requiresEvidence(ClaimType type) {
        return type == ClaimType.NUMERIC_FACT || type == ClaimType.DOCUMENT_FACT || type == ClaimType.INTERPRETATION;
    }

    /**
     * 为缺少引用的声明挑选一条类型匹配的证据。
     * 文档声明优先选标题最接近的文档证据；经理身份事实可以退回基金概况。
     * 找不到时返回 null，调用方必须拒绝回答，不能留下无证据声明。
     */
    private EvidenceReference select(AnswerClaim claim, List<EvidenceReference> evidence) {
        if (claim.type() == ClaimType.DOCUMENT_FACT) {
            var document = evidence.stream()
                    .filter(item -> DOCUMENT_EVIDENCE.contains(item.evidenceType()))
                    .max(Comparator.comparingInt(item -> documentMatchScore(claim.text(), item)));
            if (document.isPresent()) {
                return document.get();
            }
            if (isManagerProfileFact(claim.text())) {
                return evidence.stream()
                        .filter(item -> "FUND_PROFILE".equals(item.evidenceType()))
                        .findFirst()
                        .orElse(null);
            }
            return null;
        }
        if (claim.type() == ClaimType.NUMERIC_FACT) {
            return evidence.stream()
                    .filter(item -> !DOCUMENT_EVIDENCE.contains(item.evidenceType()))
                    .findFirst()
                    .orElse(null);
        }
        return evidence.stream().findFirst().orElse(null);
    }

    /**
     * 把引用插到句子末尾标点之前。
     * 没有句末标点时直接追加。不改写声明正文。
     */
    private String withCitation(String text, String citation) {
        if (!text.isEmpty() && "。！？!?\n".indexOf(text.charAt(text.length() - 1)) >= 0) {
            return text.substring(0, text.length() - 1) + " " + citation + text.charAt(text.length() - 1);
        }
        return text + " " + citation;
    }

    /**
     * 判断声明已有的引用里是否存在类型兼容的证据。
     * 引用编号不在本次证据中时跳过。没有任何兼容引用时返回 false，触发补引用或拒绝。
     */
    private boolean hasCompatibleEvidence(AnswerClaim claim, List<EvidenceReference> evidence) {
        Map<String, EvidenceReference> byId = evidence.stream()
                .collect(Collectors.toMap(EvidenceReference::evidenceId, item -> item, (left, right) -> left));
        for (String id : claim.evidenceIds()) {
            EvidenceReference reference = byId.get(id);
            if (reference == null) {
                continue;
            }
            if (claim.type() == ClaimType.DOCUMENT_FACT && (DOCUMENT_EVIDENCE.contains(reference.evidenceType())
                    || (isManagerProfileFact(claim.text()) && "FUND_PROFILE".equals(reference.evidenceType())))) {
                return true;
            }
            if (claim.type() == ClaimType.NUMERIC_FACT && !DOCUMENT_EVIDENCE.contains(reference.evidenceType())) {
                return true;
            }
            if (claim.type() == ClaimType.INTERPRETATION) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断句子是在陈述经理身份，而不是引用经理观点或报告原文。
     * 身份事实允许使用基金概况证据；观点和报告仍必须使用文档证据。
     */
    private boolean isManagerProfileFact(String text) {
        return text.contains("基金经理") && !text.contains("观点") && !text.contains("表示") && !text.contains("原文")
                && !text.contains("报告");
    }

    /**
     * 按标题与声明的重合程度为文档证据打分。
     * 标题为空时得 0。完全包含标题时给予最高分，便于在多份文档中选最接近的一条。
     */
    private int documentMatchScore(String claim, EvidenceReference evidence) {
        String title = evidence.documentTitle();
        if (title == null || title.isBlank()) {
            return 0;
        }
        if (claim.contains(title)) {
            return 1000;
        }
        int score = 0;
        for (String token : title.split("[，。：:（）()\\s]+")) {
            if (token.length() >= 2 && claim.contains(token)) {
                score += token.length();
            }
        }
        for (String keyword : List.of("回购", "增持", "减持", "中标", "签订", "处罚", "诉讼", "立案", "半年度报告", "业绩说明会")) {
            if (claim.contains(keyword) && title.contains(keyword)) {
                score += 50;
            }
        }
        return score;
    }

    /**
     * 把证据格式化成可插入回答的引用文本。
     * 带页码的基金文档使用带页码的括号形式；其他证据只返回编号。
     */
    private String format(EvidenceReference evidence) {
        if ("FUND_DOCUMENT".equals(evidence.evidenceType()) && evidence.pageStart() != null) {
            String pages = Objects.equals(evidence.pageStart(), evidence.pageEnd())
                    ? evidence.pageStart().toString()
                    : evidence.pageStart() + "-" + evidence.pageEnd();
            return "【" + evidence.evidenceId() + "，第" + pages + "页】";
        }
        return evidence.evidenceId();
    }
}
