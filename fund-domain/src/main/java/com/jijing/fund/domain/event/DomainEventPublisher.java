package com.jijing.fund.domain.event;

import java.util.Map;

/**
 * 领域事件发布端口，要求与业务写操作处于同一事务中追加事件（事务性发件箱），业务回滚时事件一并丢弃。
 * 接口不校验参数；{@link #NOOP} 实现可用于测试或不需要事件的场景。
 */
public interface DomainEventPublisher {
    /** 空实现：忽略所有入参（包括 null），不抛异常，可被重复调用且没有副作用。 */
    DomainEventPublisher NOOP = (eventType, aggregateType, aggregateId, ownerUserId, schemaVersion, deduplicationKey, payload) -> {};

    /**
     * 追加一条领域事件，由事件类型、聚合类型与标识、所属用户、载荷结构版本和去重键共同描述。
     * 载荷为 null 时由实现决定是否替换为空 Map；其余参数为 null 或重复去重键时的行为（报错或忽略）由实现和底层存储约束决定。
     */
    void append(String eventType, String aggregateType, String aggregateId, String ownerUserId, String schemaVersion,
                String deduplicationKey, Map<String, Object> payload);
}
