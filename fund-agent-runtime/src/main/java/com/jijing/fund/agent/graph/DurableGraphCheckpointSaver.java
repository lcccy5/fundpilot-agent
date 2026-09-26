package com.jijing.fund.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.capability.CapabilityExecutionContext;
import com.jijing.fund.agent.port.AgentDagRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;

/**
 * 在外层任务的租约事务里保存 LangGraph4j 的原生状态和游标。图中断或恢复都依赖这里读回的检查点。
 * 事件载荷无法序列化时失败关闭；存储拒绝重复序号时异常会穿出 put，不会假装保存成功。
 */
public final class DurableGraphCheckpointSaver implements BaseCheckpointSaver {
    private final String runId, taskId, graphName, graphVersion;
    private final GraphCheckpointStore store;
    private final CapabilityExecutionContext context;
    private final AgentDagRepository dag;
    private final ObjectMapper mapper;

    /**
     * 绑定一次任务执行的不变图版本。参数为空时会在后续读写时失败，构造器不额外校验。
     */
    public DurableGraphCheckpointSaver(String runId, String taskId, String graphName, String graphVersion, GraphCheckpointStore store,
                                       CapabilityExecutionContext context, AgentDagRepository dag, ObjectMapper mapper) {
        this.runId = runId;
        this.taskId = taskId;
        this.graphName = graphName;
        this.graphVersion = graphVersion;
        this.store = store;
        this.context = context;
        this.dag = dag;
        this.mapper = mapper;
    }

    /**
     * 返回当前图版本的全部历史，供框架按检查点标识或最新步骤恢复。没有历史时返回空集合，不抛出异常。
     */
    @Override
    public synchronized Collection<Checkpoint> list(RunnableConfig config) {
        return history().stream().map(this::nativeValue).toList();
    }

    /**
     * 优先读取配置里的检查点标识；没有标识或找不到时退回该线程最新的持久化步骤。
     * 两者都不存在时返回空，表示图应使用全新输入而不是中断恢复。
     */
    @Override
    public synchronized Optional<Checkpoint> get(RunnableConfig config) {
        var all = history();
        return config.checkPointId().flatMap(id -> all.stream().filter(value -> value.checkpointId().equals(id)).findFirst())
                .or(() -> all.stream().max(Comparator.comparingLong(GraphCheckpoint::sequence))).map(this::nativeValue);
    }

    /**
     * 追加或替换原生检查点，并在同一租约保护的事务里发布节点完成事件。
     * 持久化回调抛出的异常会原样穿出；事件序列化失败时抛出 IllegalStateException。
     */
    @Override
    public synchronized RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        context.persist(() -> persist(checkpoint));
        return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
    }

    /**
     * 保留已完成检查点，使外层任务在图结束后、任务提交前崩溃时仍能恢复，而不是重放整张图。
     */
    @Override
    public synchronized Tag release(RunnableConfig config) {
        return new Tag(threadId(config), list(config));
    }

    /**
     * 用持久化任务标识作为跨 worker 尝试稳定的图线程标识。配置里已有线程标识时沿用它。
     */
    @Override
    public String threadId(RunnableConfig config) {
        return config.threadId().orElse(taskId);
    }

    /**
     * 保存框架状态和下一个节点游标。已有同一检查点标识时替换，否则追加。
     * 存储拒绝写入或事件无法序列化时失败；应用层不另选恢复路由。
     */
    private void persist(Checkpoint checkpoint) {
        Optional<GraphCheckpoint> existing = history().stream().filter(value -> value.checkpointId().equals(checkpoint.getId())).findFirst();
        GraphCheckpoint saved = new GraphCheckpoint(checkpoint.getId(), runId, taskId, graphName, graphVersion,
                existing.map(GraphCheckpoint::sequence).orElseGet(() -> history().stream().mapToLong(GraphCheckpoint::sequence).max().orElse(0) + 1),
                checkpoint.getNodeId(), checkpoint.getNextNodeId(), checkpoint.getState(), Instant.now());
        if (existing.isPresent()) {
            store.replace(saved);
        } else {
            store.append(saved);
        }
        dag.appendEvent(runId, "graph.node.completed", json(Map.of("taskKey", context.task().taskKey(), "node", checkpoint.getNodeId(),
                "nextNode", checkpoint.getNextNodeId(), "sequence", saved.sequence())), Instant.now());
    }

    /**
     * 只读取该任务钉住的图版本。版本升级不会自动混入旧快照，需要显式迁移。
     */
    private List<GraphCheckpoint> history() {
        return store.findAll(runId, taskId, graphName, graphVersion);
    }

    /**
     * 把数据库记录还原成框架检查点。状态映射原样交回，缺失字段不会在这里补造引用。
     */
    private Checkpoint nativeValue(GraphCheckpoint value) {
        return Checkpoint.builder().id(value.checkpointId()).state(value.state()).nodeId(value.nodeName()).nextNodeId(value.phase()).build();
    }

    /**
     * 序列化面向所有者的检查点事件。序列化失败时抛出 IllegalStateException，避免发出无法审计的节点事件。
     */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize graph checkpoint event", e);
        }
    }
}
