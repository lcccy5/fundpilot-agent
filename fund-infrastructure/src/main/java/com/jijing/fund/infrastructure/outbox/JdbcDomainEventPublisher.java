package com.jijing.fund.infrastructure.outbox;

import com.jijing.fund.agent.event.DomainEvent;
import com.jijing.fund.domain.event.DomainEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把领域事件端口接到发件箱。每次调用生成新的事件编号，因此端口层看不到重复提交。
 * 相同去重键是否被忽略取决于表上的约束和 {@code INSERT IGNORE}，本类不检查影响行数。
 * 载荷为 null 时写成空 Map。发生时间取系统时钟。没有超时处理。
 */
@Component
public class JdbcDomainEventPublisher implements DomainEventPublisher {
    private final JdbcTransactionalOutbox outbox;

    /** 发件箱负责事务和忽略重复主键。 */
    public JdbcDomainEventPublisher(JdbcTransactionalOutbox outbox) {
        this.outbox = outbox;
    }

    /** 证据列表固定为空，调用方目前不能从该端口附带证据。 */
    @Override
    public void append(String eventType, String aggregateType, String aggregateId, String ownerUserId,
            String schemaVersion, String deduplicationKey, Map<String, Object> payload) {
        outbox.append(new DomainEvent(UUID.randomUUID().toString(), eventType, aggregateType, aggregateId,
                ownerUserId, Instant.now(), schemaVersion, deduplicationKey, payload == null ? Map.of() : payload,
                List.of(), null));
    }
}
