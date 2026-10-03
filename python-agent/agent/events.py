"""处理 Java 已提交的业务事件；通知去重与冷却判断由 Python 完成。"""

import hmac
import json
import os
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field



class BusinessEvent(BaseModel):
    eventId: str = Field(min_length=1, max_length=100)
    eventType: str = Field(min_length=1, max_length=100)
    schemaVersion: str | None = None
    ownerUserId: str | None = Field(default=None, max_length=100)
    payload: dict = Field(default_factory=dict)


class DrawdownValues(BaseModel):
    previous: float = Field(allow_inf_nan=False)
    current: float = Field(allow_inf_nan=False)
    threshold: float = Field(default=-10, allow_inf_nan=False)
    quietHours: bool = False


class EventService:
    def __init__(self, repository):
        self.repository = repository
        path = repository.path.with_name("agent-event.key")
        configured = os.getenv("AGENT_EVENT_SECRET")
        if configured:
            self.key = configured
        else:
            # 本地双进程通过被 Git 忽略的文件共享独立凭证，不复用百炼密钥。
            try:
                with path.open("x", encoding="utf-8") as file:
                    file.write(secrets.token_hex(32))
            except FileExistsError:
                pass
            self.key = path.read_text(encoding="utf-8").strip()
        with repository.connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS agent_business_event(id TEXT PRIMARY KEY, result TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS agent_notification_cooldown(
                    owner TEXT NOT NULL, rule TEXT NOT NULL, fired TEXT NOT NULL,
                    PRIMARY KEY(owner, rule)
                );
            """)

    def dispatch(self, event: BusinessEvent, timestamp: datetime | None = None):
        timestamp = timestamp or datetime.now(timezone.utc)
        with self.repository.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            saved = db.execute("SELECT result FROM agent_business_event WHERE id=?", (event.eventId,)).fetchone()
            if saved:
                return json.loads(saved[0])
            result = {"deadLetter": False, "reason": "IGNORED", "shouldNotify": False, "held": False}
            if event.schemaVersion != "v1":
                result.update(deadLetter=True, reason="UNKNOWN_SCHEMA")
            elif event.eventType == "PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED" and event.ownerUserId:
                try:
                    values = DrawdownValues.model_validate(event.payload)
                except ValueError:
                    result.update(deadLetter=True, reason="INVALID_DRAWDOWN_VALUES")
                else:
                    result.update(ruleId="drawdown", ownerUserId=event.ownerUserId,
                                  fingerprint=f"drawdown|down|{values.threshold}|{timestamp:%Y-%m-%dT%H}")
                    fired = db.execute("SELECT fired FROM agent_notification_cooldown WHERE owner=? AND rule='drawdown'",
                                       (event.ownerUserId,)).fetchone()
                    if not values.previous > values.threshold >= values.current:
                        result["reason"] = "NO_CROSSING"
                    elif fired and timestamp < datetime.fromisoformat(fired[0]) + timedelta(hours=6):
                        result["reason"] = "COOLDOWN"
                    elif values.quietHours:
                        result.update(held=True, reason="QUIET_HOURS_HELD")
                    else:
                        result.update(shouldNotify=True, reason="FIRED")
                        db.execute("INSERT OR REPLACE INTO agent_notification_cooldown VALUES (?, 'drawdown', ?)",
                                   (event.ownerUserId, timestamp.isoformat()))
            db.execute("INSERT INTO agent_business_event VALUES (?, ?)",
                       (event.eventId, json.dumps(result, ensure_ascii=False)))
        return result

    def router(self):
        router = APIRouter()

        @router.post("/internal/agent/events")
        async def receive(event: BusinessEvent, x_agent_event_key: str | None = Header(None)):
            if not x_agent_event_key or not hmac.compare_digest(x_agent_event_key.encode(), self.key.encode()):
                raise HTTPException(401, "业务事件来源未认证")
            return self.dispatch(event)

        return router
