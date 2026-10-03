"""持久化会话、运行和事件；所有查询都带用户条件。"""

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "REJECTED"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class AgentRepository:
    def __init__(self, path: Path):
        self.path = path
        with self.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS agent_conversation (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS agent_run (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, conversation TEXT,
                    message TEXT NOT NULL, status TEXT NOT NULL, started TEXT NOT NULL,
                    result TEXT, job TEXT
                );
                CREATE TABLE IF NOT EXISTS agent_event (
                    run TEXT NOT NULL, sequence INTEGER NOT NULL, type TEXT NOT NULL,
                    data TEXT NOT NULL, occurred TEXT NOT NULL,
                    PRIMARY KEY(run, sequence)
                );
                CREATE INDEX IF NOT EXISTS agent_owner ON agent_run(owner, started);
            """)

    @contextmanager
    def connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def conversation(self, owner: str) -> str:
        identifier = str(uuid4())
        with self.connect() as db:
            db.execute("INSERT INTO agent_conversation VALUES (?, ?)", (identifier, owner))
        return identifier

    def owns_conversation(self, identifier: str, owner: str) -> bool:
        with self.connect() as db:
            return db.execute("SELECT 1 FROM agent_conversation WHERE id=? AND owner=?",
                              (identifier, owner)).fetchone() is not None

    def create(self, owner: str, message: str, conversation: str | None = None,
               job: dict | None = None) -> dict:
        identifier = str(uuid4())
        with self.connect() as db:
            db.execute("INSERT INTO agent_run VALUES (?, ?, ?, ?, 'RUNNING', ?, NULL, ?)",
                       (identifier, owner, conversation, message, now(),
                        json.dumps(job, ensure_ascii=False) if job else None))
        return self.get(identifier, owner)

    def get(self, identifier: str, owner: str) -> dict | None:
        with self.connect() as db:
            row = db.execute("SELECT * FROM agent_run WHERE id=? AND owner=?",
                             (identifier, owner)).fetchone()
        return dict(row) if row else None

    def history(self, owner: str) -> list[dict]:
        with self.connect() as db:
            return [dict(row) for row in db.execute(
                "SELECT * FROM agent_run WHERE owner=? ORDER BY started DESC LIMIT 100", (owner,))]

    def memories(self, conversation: str | None, owner: str) -> list[tuple[str, str]]:
        if not conversation:
            return []
        with self.connect() as db:
            rows = db.execute("""SELECT message, result FROM agent_run
                WHERE conversation=? AND owner=? AND status='SUCCEEDED'
                ORDER BY started DESC LIMIT 4""", (conversation, owner)).fetchall()
        # 限制轮数和每轮长度，避免聊天历史不断增长、耗尽模型上下文。
        messages = []
        for row in reversed(rows):
            messages.extend([("human", row["message"]),
                             ("ai", json.loads(row["result"])["answer"][:4000])])
        return messages

    def event(self, identifier: str, kind: str, data) -> dict:
        with self.connect() as db:
            # 先取得写锁，再分配序号，防止取消与执行同时写入同一序号。
            db.execute("BEGIN IMMEDIATE")
            sequence = db.execute("SELECT COALESCE(MAX(sequence),0)+1 FROM agent_event WHERE run=?",
                                  (identifier,)).fetchone()[0]
            timestamp = now()
            db.execute("INSERT INTO agent_event VALUES (?, ?, ?, ?, ?)",
                       (identifier, sequence, kind, json.dumps(data, ensure_ascii=False), timestamp))
        return {"sequence": sequence, "type": kind, "runId": identifier,
                "data": data, "occurredAt": timestamp}

    def events(self, identifier: str, after: int = 0) -> list[dict]:
        with self.connect() as db:
            rows = db.execute("SELECT * FROM agent_event WHERE run=? AND sequence>? ORDER BY sequence",
                              (identifier, after)).fetchall()
        return [{"sequence": row["sequence"], "type": row["type"], "runId": identifier,
                 "data": json.loads(row["data"]), "occurredAt": row["occurred"]} for row in rows]

    def finish(self, identifier: str, status: str, result: dict | None = None) -> bool:
        with self.connect() as db:
            cursor = db.execute("""UPDATE agent_run SET status=?, result=?
                WHERE id=? AND status IN ('RUNNING','WAITING_APPROVAL')""",
                (status, json.dumps(result, ensure_ascii=False) if result else None, identifier))
        # 条件更新保证取消之后，迟到的模型回答不会把状态改回成功。
        return cursor.rowcount == 1

    def resume(self, identifier: str) -> bool:
        with self.connect() as db:
            cursor = db.execute("UPDATE agent_run SET status='RUNNING' WHERE id=? AND status='WAITING_APPROVAL'", (identifier,))
        return cursor.rowcount == 1

    def recover(self):
        with self.connect() as db:
            identifiers = [row[0] for row in db.execute("SELECT id FROM agent_run WHERE status='RUNNING'")]
        for identifier in identifiers:
            if self.finish(identifier, "FAILED"):
                self.event(identifier, "run.failed", {"message": "服务重启中断了研究，请重新提交", "reason": "服务重启"})
