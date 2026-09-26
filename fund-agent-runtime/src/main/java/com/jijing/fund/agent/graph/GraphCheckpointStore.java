package com.jijing.fund.agent.graph;

import java.util.List;
import java.util.Optional;

/**
 * 图节点状态的持久化边界。实现必须按任务保持序号递增，否则恢复会读到错误步骤。
 * 重复序号或缺失检查点由实现抛出 IllegalStateException，接口本身不吞掉失败。
 */
public interface GraphCheckpointStore {
    /**
     * 追加一个节点完成后的快照。同一任务出现重复序号时必须拒绝，不能覆盖已有步骤。
     */
    void append(GraphCheckpoint checkpoint);

    /**
     * 按检查点标识替换已有快照，序号保持不变。标识不存在时必须失败，不能偷偷追加。
     */
    void replace(GraphCheckpoint checkpoint);

    /**
     * 返回指定运行、任务和图版本的全部快照，并按序号从旧到新排列。没有记录时返回空列表。
     */
    List<GraphCheckpoint> findAll(String runId, String taskId, String graphName, String graphVersion);

    /**
     * 返回该图版本下最新的快照。任务尚未执行时返回空，调用方应按全新输入启动，而不是猜测恢复点。
     */
    Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion);
}
