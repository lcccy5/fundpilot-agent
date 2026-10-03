"""保存成功的研究运行，供页面按运行 ID 追溯。"""

import os
import sqlite3
from pathlib import Path

from models import ResearchResult


class RunStore:
    def __init__(self, db_path: str | None = None) -> None:
        default = Path(__file__).resolve().parent / "data" / "research.db"
        self.db_path = Path(db_path or os.getenv("AGENT_DB_PATH", str(default)))

    def _connect(self) -> sqlite3.Connection:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.db_path)
        connection.execute("""
            CREATE TABLE IF NOT EXISTS research_run (
                run_id TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                result_json TEXT NOT NULL
            )
        """)
        columns = {row[1] for row in connection.execute("PRAGMA table_info(research_run)")}
        if "owner_id" not in columns:
            # 旧版记录没有归属信息，标为空归属并禁止通过用户查询访问。
            connection.execute("ALTER TABLE research_run ADD COLUMN owner_id TEXT NOT NULL DEFAULT ''")
        connection.execute("CREATE INDEX IF NOT EXISTS idx_research_run_owner ON research_run(owner_id)")
        return connection

    def save(self, result: ResearchResult, owner_id: str) -> None:
        # 一次性写入同次快照、各角色产物与耗时，避免出现不完整的成功运行。
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO research_run (run_id, owner_id, result_json) VALUES (?, ?, ?)",
                (result.run_id, owner_id, result.model_dump_json(by_alias=True)),
            )

    def load(self, run_id: str, owner_id: str) -> ResearchResult | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT result_json FROM research_run WHERE run_id = ? AND owner_id = ?",
                (run_id, owner_id),
            ).fetchone()
        return ResearchResult.model_validate_json(row[0]) if row else None
