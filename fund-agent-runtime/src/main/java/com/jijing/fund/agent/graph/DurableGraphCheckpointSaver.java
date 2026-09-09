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

/** Persists LangGraph4j's native state and cursor under the outer task's fenced transaction. */
public final class DurableGraphCheckpointSaver implements BaseCheckpointSaver {
    private final String runId, taskId, graphName, graphVersion;
    private final GraphCheckpointStore store;
    private final CapabilityExecutionContext context;
    private final AgentDagRepository dag;
    private final ObjectMapper mapper;

    /** Creates a saver for one immutable graph version and task execution identity. */
    public DurableGraphCheckpointSaver(String runId, String taskId, String graphName, String graphVersion, GraphCheckpointStore store,
                                       CapabilityExecutionContext context, AgentDagRepository dag, ObjectMapper mapper) {
        this.runId=runId; this.taskId=taskId; this.graphName=graphName; this.graphVersion=graphVersion;
        this.store=store; this.context=context; this.dag=dag; this.mapper=mapper;
    }
    /** Returns all history so the framework can locate a named checkpoint or its latest checkpoint. */
    @Override public synchronized Collection<Checkpoint> list(RunnableConfig config) { return history().stream().map(this::nativeValue).toList(); }
    /** Reads the requested checkpoint id, falling back to the latest durable step for a thread resume. */
    @Override public synchronized Optional<Checkpoint> get(RunnableConfig config) {
        var all=history();
        return config.checkPointId().flatMap(id->all.stream().filter(value->value.checkpointId().equals(id)).findFirst())
                .or(()->all.stream().max(Comparator.comparingLong(GraphCheckpoint::sequence))).map(this::nativeValue);
    }
    /** Appends or replaces native checkpoints and publishes the matching node event in one lease-guarded transaction. */
    @Override public synchronized RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        context.persist(()->persist(checkpoint));
        return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
    }
    /** Retains finished checkpoints so outer task completion can be recovered without replaying the graph. */
    @Override public synchronized Tag release(RunnableConfig config) { return new Tag(threadId(config),list(config)); }
    /** Uses the durable task id as a stable graph thread id across worker attempts. */
    @Override public String threadId(RunnableConfig config) { return config.threadId().orElse(taskId); }
    /** Persists the framework's state and next-node cursor; no application phase decides resume routing. */
    private void persist(Checkpoint checkpoint) {
        Optional<GraphCheckpoint> existing=history().stream().filter(value->value.checkpointId().equals(checkpoint.getId())).findFirst();
        GraphCheckpoint saved=new GraphCheckpoint(checkpoint.getId(),runId,taskId,graphName,graphVersion,
                existing.map(GraphCheckpoint::sequence).orElseGet(()->history().stream().mapToLong(GraphCheckpoint::sequence).max().orElse(0)+1),
                checkpoint.getNodeId(),checkpoint.getNextNodeId(),checkpoint.getState(),Instant.now());
        if(existing.isPresent())store.replace(saved);else store.append(saved);
        dag.appendEvent(runId,"graph.node.completed",json(Map.of("taskKey",context.task().taskKey(),"node",checkpoint.getNodeId(),"nextNode",checkpoint.getNextNodeId(),"sequence",saved.sequence())),Instant.now());
    }
    /** Limits reads to the task's pinned graph version, so upgrades require explicit migration. */
    private List<GraphCheckpoint> history(){return store.findAll(runId,taskId,graphName,graphVersion);}
    /** Reconstructs the exact framework checkpoint representation from the database record. */
    private Checkpoint nativeValue(GraphCheckpoint value){return Checkpoint.builder().id(value.checkpointId()).state(value.state()).nodeId(value.nodeName()).nextNodeId(value.phase()).build();}
    /** Fails closed if owner-visible checkpoint metadata cannot be serialized. */
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("cannot serialize graph checkpoint event",e);}}
}
