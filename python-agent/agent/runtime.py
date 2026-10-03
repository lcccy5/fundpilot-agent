"""后台任务与取消：网页断线不等于取消，任务结果仍会写入数据库。"""

import asyncio
import logging
import json
from datetime import datetime, timezone
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from agent.graph import AgentGraph
from agent.repository import AgentRepository
from agent.tools import BusinessTools


logger = logging.getLogger(__name__)


class AgentRuntime:
    def __init__(self, repository: AgentRepository, identity, graph_factory=AgentGraph):
        self.repository = repository
        self.identity = identity
        self.graph_factory = graph_factory
        self.tasks: dict[str, asyncio.Task] = {}
        self.capacity = asyncio.Semaphore(4)

    def start(self, run: dict, authorization: str, resume=False):
        # 令牌只存在任务内存中，不进入 SQLite、事件或模型上下文。
        identifier = run["id"]
        task = asyncio.create_task(self.execute(run, authorization, resume))
        self.tasks[identifier] = task
        def finished(completed):
            if self.tasks.get(identifier) is completed:
                self.tasks.pop(identifier, None)
        task.add_done_callback(finished)

    async def execute(self, run: dict, authorization: str, resume=False):
        identifier = run["id"]
        emit = lambda kind, data: self.repository.event(identifier, kind, data)
        emit("run.resumed" if resume else "run.started", {"conversationId": run["conversation"], "mode": "LANGGRAPH"})
        try:
            async with self.capacity:
                checkpoint_path = str(self.repository.path.with_name(self.repository.path.stem + "-checkpoints.db"))
                async with AsyncSqliteSaver.from_conn_string(checkpoint_path) as checkpointer:
                    scope = json.loads(run["job"]) if run["job"] else None
                    graph = self.graph_factory(BusinessTools(authorization, self.identity, scope), emit, checkpointer=checkpointer)
                    state = await graph.run(run["message"], self.repository.memories(run["conversation"], run["owner"]), identifier, resume)
            if pending := state.get("__interrupt__"):
                if self.repository.finish(identifier, "WAITING_APPROVAL"):
                    emit("approval.requested", {"approvalId": pending[0].id, **pending[0].value})
                return
            result = {"answer": state["answer"], "runId": identifier,
                      "conversationId": run["conversation"], "evidence": state["evidence"],
                      "review": state["review"], "tools": state["tools"],
                      "modelRounds": state["rounds"] + 2, "toolCallCount": state["calls"],
                      "totalTokens": state["tokens"],
                      "durationMs": round((datetime.now(timezone.utc) - datetime.fromisoformat(run["started"])).total_seconds() * 1000),
                      "limitations": ["引用校验仅检查证据编号；知识库模块尚未迁移"]}
            if self.repository.finish(identifier, "SUCCEEDED", result):
                emit("answer.completed", result)
                emit("run.completed", {"status": "SUCCEEDED"})
        except asyncio.CancelledError:
            if self.repository.finish(identifier, "CANCELLED"):
                emit("run.cancelled", {"message": "任务已取消"})
            raise
        except Exception as error:
            # 只记录异常类别，避免 SDK 的异常正文包含服务配置等敏感信息。
            logger.warning("Agent run %s failed: %s", identifier, type(error).__name__)
            if self.repository.finish(identifier, "FAILED"):
                emit("run.failed", {"message": "研究失败，请检查模型配置、业务数据后重新提交",
                                    "reason": "模型或数据服务不可用，或引用未通过校验"})

    def cancel(self, identifier: str):
        if self.repository.finish(identifier, "CANCELLED"):
            self.repository.event(identifier, "run.cancelled", {"message": "任务已取消"})
        if task := self.tasks.get(identifier):
            task.cancel()

    async def shutdown(self):
        tasks = list(self.tasks.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
