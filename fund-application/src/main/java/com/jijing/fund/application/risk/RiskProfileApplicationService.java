package com.jijing.fund.application.risk;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.risk.RiskLevel;
import com.jijing.fund.domain.risk.RiskProfile;
import com.jijing.fund.domain.risk.RiskProfileRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 根据固定问卷计算风险等级并按用户保存。等级只由服务端分数决定，调用方不能直接指定等级。
 * 没有档案、版本不对或答案不完整时都抛出 {@link RiskProfileException}，不能从异常类型区分原因。
 */
public class RiskProfileApplicationService implements RiskProfileUseCase {
    /**
     * 当前唯一接受的问卷版本。其他版本字符串，包括空值，都会在提交时被拒绝。
     */
    public static final String VERSION = "risk-questionnaire-v1";

    private static final List<Question> QUESTIONS = List.of(
            q("horizon", "投资期限", List.of(c(1, "1 年以内"), c(2, "1～3 年"), c(3, "3～5 年"), c(4, "5～10 年"), c(5, "10 年以上"))),
            q("income", "收入稳定性", List.of(c(1, "很不稳定"), c(2, "不太稳定"), c(3, "一般"), c(4, "比较稳定"), c(5, "非常稳定"))),
            q("drawdown", "可承受最大回撤", List.of(c(1, "5% 以内"), c(2, "10% 以内"), c(3, "20% 以内"), c(4, "30% 以内"), c(5, "超过 30%"))),
            q("liquidity", "流动性需求", List.of(c(1, "随时可能用钱"), c(2, "一年内可能用钱"), c(3, "两到三年"), c(4, "五年内"), c(5, "长期不用"))),
            q("experience", "投资经验", List.of(c(1, "几乎没有"), c(2, "少于 1 年"), c(3, "1～3 年"), c(4, "3～5 年"), c(5, "5 年以上"))),
            q("lossAttitude", "目标收益与损失态度", List.of(c(1, "保本优先"), c(2, "宁可少赚也要少亏"), c(3, "平衡"), c(4, "可接受波动换收益"), c(5, "追求高收益并接受大幅回撤")))
    );

    private final RiskProfileRepository repository;
    private final Clock clock;

    /**
     * 保存档案仓库和时钟。二者为空时不会立刻失败，读档案或提交时才会抛出空指针异常。
     */
    public RiskProfileApplicationService(RiskProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 返回当前问卷的题目和 1 到 5 的选项。没有输入，也不会失败。
     */
    @Override
    public Questionnaire questionnaire() {
        return new Questionnaire(VERSION, QUESTIONS);
    }

    /**
     * 读取该用户最近一次确认的档案。还没有档案时抛出风险异常，消息为找不到。用户为空时抛出空指针异常。
     * 不会返回其他用户的档案，隔离依赖仓库按用户过滤。
     */
    @Override
    public RiskProfile current(AuthenticatedUser actor) {
        return repository.findLatestByOwner(actor.userId())
                .orElseThrow(() -> new RiskProfileException("risk profile not found"));
    }

    /**
     * 校验六题答案、计分并保存。版本不是 {@link #VERSION} 时抛出异常。答案映射为空时抛出异常。
     * 任一题目缺失、值为空、小于 1 或大于 5 时抛出异常，且不会保存。多余的键被忽略，不能用来抬高分数。
     * 分数不超过 12 为稳健，不超过 18 为平衡，不超过 24 为成长，其余为进取。用户为空时抛出空指针异常。
     */
    @Override
    @Transactional
    public RiskProfile submit(AuthenticatedUser actor, String questionnaireVersion, Map<String, Integer> answers) {
        if (!VERSION.equals(questionnaireVersion)) {
            throw new RiskProfileException("unsupported questionnaire version");
        }
        if (answers == null) {
            throw new RiskProfileException("answers are required");
        }
        int score = 0;
        var canonical = new TreeMap<String, Integer>();
        for (Question question : QUESTIONS) {
            Integer value = answers.get(question.id());
            if (value == null || value < 1 || value > 5) {
                throw new RiskProfileException("answer required: " + question.id());
            }
            canonical.put(question.id(), value);
            score += value;
        }
        RiskLevel level = score <= 12 ? RiskLevel.CONSERVATIVE
                : score <= 18 ? RiskLevel.BALANCED
                : score <= 24 ? RiskLevel.GROWTH
                : RiskLevel.AGGRESSIVE;
        Instant now = clock.instant();
        var profile = new RiskProfile(UUID.randomUUID().toString(), actor.userId(), VERSION, sha(canonical.toString()), score, level, now, now);
        repository.save(profile);
        return profile;
    }

    /**
     * 组装一道题。不检查题号是否为空、选项是否覆盖 1 到 5；空选项列表会原样进入问卷，提交时会因缺少合法答案而失败。
     */
    private static Question q(String id, String prompt, List<Choice> choices) {
        return new Question(id, prompt, choices);
    }

    /**
     * 组装一个选项。不检查分值是否落在 1 到 5，非法分值要到提交校验才会被拒绝。
     */
    private static Choice c(int value, String label) {
        return new Choice(value, label);
    }

    /**
     * 计算答案文本的 SHA-256 摘要。文本为空时抛出空指针异常；算法不可用时包装成非法状态异常。
     * 摘要使用有序映射的文本形式，键按字典序排列，不保留调用方原来的插入顺序。
     */
    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
