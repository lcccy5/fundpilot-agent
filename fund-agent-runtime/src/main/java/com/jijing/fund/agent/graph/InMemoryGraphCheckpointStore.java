package com.jijing.fund.agent.graph;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 线程安全的内存检查点存储，供测试和本地回退使用。生产环境应使用保持相同序号语义的 JDBC 实现。
 * 重复序号和缺失替换都会失败，不静默丢弃。
 */
public final class InMemoryGraphCheckpointStore implements GraphCheckpointStore {
    /** 只追加的历史与生产恢复语义一致，且不共享可变状态。 */
    private final List<GraphCheckpoint> values = new CopyOnWriteArrayList<>();

    /**
     * 追加一张图快照。同一运行、任务和序号已存在时抛出 IllegalStateException，不覆盖旧快照。
     */
    @Override
    public void append(GraphCheckpoint checkpoint) {
        boolean duplicate = values.stream().anyMatch(value -> value.runId().equals(checkpoint.runId())
                && value.taskId().equals(checkpoint.taskId()) && value.sequence() == checkpoint.sequence());
        if (duplicate) {
            throw new IllegalStateException("duplicate graph checkpoint sequence");
        }
        values.add(checkpoint);
    }

    /**
     * 按检查点标识替换已有快照，序号保持调用方传入的值。标识不存在时抛出 IllegalStateException。
     */
    @Override
    public void replace(GraphCheckpoint checkpoint) {
        int index = values.indexOf(values.stream().filter(value -> value.checkpointId().equals(checkpoint.checkpointId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("checkpoint does not exist")));
        values.set(index, checkpoint);
    }

    /**
     * 按运行、任务和图版本过滤，并按序号从旧到新返回。版本不一致的快照不会出现，避免误恢复到旧图。
     */
    @Override
    public List<GraphCheckpoint> findAll(String runId, String taskId, String graphName, String graphVersion) {
        return values.stream().filter(value -> value.runId().equals(runId) && value.taskId().equals(taskId)
                        && value.graphName().equals(graphName) && value.graphVersion().equals(graphVersion))
                .sorted(Comparator.comparingLong(GraphCheckpoint::sequence)).toList();
    }

    /**
     * 返回所请求图版本的最新快照。没有兼容快照时返回空，表示应从新输入开始。
     */
    @Override
    public Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion) {
        return findAll(runId, taskId, graphName, graphVersion).stream().max(Comparator.comparingLong(GraphCheckpoint::sequence));
    }
}
