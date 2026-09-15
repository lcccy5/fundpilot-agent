-- Native LangGraph4j checkpoint ids and ordering are scoped to the pinned graph version.
-- This permits a task to retain its old recoverable history while a newer graph version is deployed.
ALTER TABLE agent_graph_checkpoint ADD UNIQUE KEY uk_graph_checkpoint_task_version_seq (task_id, graph_name, graph_version, sequence);
-- Keep an index beginning with task_id available while the foreign key is active.
ALTER TABLE agent_graph_checkpoint DROP INDEX uk_graph_checkpoint_task_seq;
