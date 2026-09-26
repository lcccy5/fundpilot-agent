package com.jijing.fund.domain.risk;

import com.jijing.fund.domain.identity.UserId;
import java.util.Optional;

/** 风险测评结果的持久化端口，只追加新结果、按确认时间取最新结果。接口不校验参数，null 的处理由实现决定。 */
public interface RiskProfileRepository {
    /** 查询用户确认时间最晚的一条测评结果；用户从未完成测评时返回空。 */
    Optional<RiskProfile> findLatestByOwner(UserId owner);

    /** 追加一条测评结果，不覆盖历史记录；profileId 重复时由实现抛出存储层异常。 */
    void save(RiskProfile profile);
}
