"""兼容现有页面的数据结构；Agent API 独立走 Python。"""

import asyncio
import calendar
import json
from datetime import date
from typing import Literal

from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from agent.graph import model_for
from agent.repository import TERMINAL
from agent.tools import BusinessTools
from client import FundApiError
from research import ModelUnavailable


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)
    conversationId: str | None = None


class MonthlyRequest(BaseModel):
    month: str = Field(pattern=r"^\d{4}-\d{2}$")
    scopeKind: Literal["PORTFOLIO", "WATCHLIST"]
    scopeId: str = Field(min_length=1, max_length=80)


class ApprovalRequest(BaseModel):
    parameters: str = Field(default="", max_length=2000)


def routes(repository, runtime, identity):
    router = APIRouter(prefix="/api/agent")

    async def actor(authorization):
        try:
            return await identity.user_id(authorization)
        except FundApiError as error:
            raise HTTPException(error.status_code, str(error)) from error

    def owned(identifier, owner):
        run = repository.get(identifier, owner)
        if not run:
            raise HTTPException(404, "运行不存在")
        return run

    def view(run):
        events = repository.events(run["id"])
        return {"runId": run["id"], "status": run["status"], "message": run["message"],
                "startedAt": run["started"], "executionMode": "LANGGRAPH",
                "routeReason": "分析取数、风险复核、综合回答与引用校验", "planId": run["id"],
                "lastEventSequence": events[-1]["sequence"] if events else 0}

    def available():
        try:
            # 只验证配置，不提前请求模型；错误在 HTTP 响应开始前返回。
            for role in ("bull", "bear", "judge"):
                model_for(role)
        except ModelUnavailable as error:
            raise HTTPException(503, str(error)) from error

    def submit(request, owner, authorization, job=None):
        available()
        if not request.message.strip():
            raise HTTPException(422, "问题不能为空")
        if request.conversationId and not repository.owns_conversation(request.conversationId, owner):
            raise HTTPException(404, "会话不存在")
        # 同一会话同时提问会造成历史顺序混乱，要求前一轮结束后再发起。
        if request.conversationId and any(item["conversation"] == request.conversationId
                and item["status"] not in TERMINAL for item in repository.history(owner)):
            raise HTTPException(409, "这个会话仍在研究中，请先等待或取消")
        if sum(item["status"] == "RUNNING" for item in repository.history(owner)) >= 4:
            raise HTTPException(429, "最多同时运行四个研究任务")
        run = repository.create(owner, request.message.strip(), request.conversationId, job)
        runtime.start(run, authorization)
        return run

    @router.post("/conversations")
    async def conversation(authorization: str | None = Header(None)):
        owner = await actor(authorization)
        return {"conversationId": repository.conversation(owner)}

    @router.post("/runs")
    async def create(request: ChatRequest, authorization: str | None = Header(None)):
        owner = await actor(authorization)
        return view(submit(request, owner, authorization))

    @router.get("/runs")
    async def history(authorization: str | None = Header(None)):
        return [view(run) for run in repository.history(await actor(authorization))]

    @router.get("/runs/{identifier}")
    async def get(identifier: str, authorization: str | None = Header(None)):
        return view(owned(identifier, await actor(authorization)))

    @router.post("/runs/{identifier}/cancel")
    async def cancel(identifier: str, authorization: str | None = Header(None)):
        owner = await actor(authorization)
        owned(identifier, owner)
        runtime.cancel(identifier)
        return view(owned(identifier, owner))

    @router.get("/runs/{identifier}/events")
    async def events(identifier: str, after: int = 0, authorization: str | None = Header(None),
                     last_event_id: str | None = Header(None)):
        owned(identifier, await actor(authorization))
        try:
            cursor = max(0, int(last_event_id or after))
        except ValueError:
            raise HTTPException(422, "事件序号必须是整数")
        return [{"sequence": event["sequence"], "eventType": event["type"],
                 "payloadJson": json.dumps(event["data"], ensure_ascii=False)}
                for event in repository.events(identifier, cursor)]

    @router.get("/runs/{identifier}/plan")
    async def plan(identifier: str, authorization: str | None = Header(None)):
        run = owned(identifier, await actor(authorization))
        events = repository.events(identifier)
        begun = {event["data"].get("node") for event in events if event["type"] == "agent.started"}
        names = [("analyst", "ANALYST"), ("risk_review", "RISK_REVIEW"),
                 ("synthesis", "SYNTHESIS"), ("verify", "REPORT_VERIFY")]
        current = "verify" if any(e["type"] == "evidence.verifying" for e in events) else "synthesis" if "synthesis" in begun else "risk_review" if "risk_review" in begun else "analyst"
        index = [name for name, _ in names].index(current)
        tasks = []
        for position, (name, capability) in enumerate(names):
            status = "SUCCEEDED" if run["status"] == "SUCCEEDED" or position < index else "RUNNING" if position == index else "PENDING"
            if run["status"] in {"FAILED", "CANCELLED"} and position >= index:
                status = run["status"] if position == index else "CANCELLED"
            tasks.append({"taskKey": name, "capabilityType": capability, "status": status})
        return {"planId": identifier, "status": run["status"], "tasks": tasks}

    @router.get("/runs/{identifier}/report")
    async def report(identifier: str, authorization: str | None = Header(None)):
        run = owned(identifier, await actor(authorization))
        if run["status"] != "SUCCEEDED":
            raise HTTPException(409, "报告尚未完成")
        result = json.loads(run["result"])
        return {"artifactUri": f"agent:{identifier}", "content": result["answer"],
                "evidenceCount": len(result["evidence"])}

    def pending_approval(identifier, owner, approval_id):
        run = owned(identifier, owner)
        pending = next((event for event in reversed(repository.events(identifier))
                        if event["type"] == "approval.requested"), None)
        if run["status"] != "WAITING_APPROVAL" or not pending or pending["data"]["approvalId"] != approval_id:
            raise HTTPException(409, "这项审批不存在或已处理")
        return run

    @router.post("/runs/{identifier}/approvals/{approval_id}")
    async def approve(identifier: str, approval_id: str, request: ApprovalRequest,
                      authorization: str | None = Header(None)):
        owner = await actor(authorization)
        run = pending_approval(identifier, owner, approval_id)
        # 不执行 request.parameters，它只是旧页面契约；真正审批对象由检查点固定。
        available()
        if not repository.resume(identifier):
            raise HTTPException(409, "审批已被处理")
        repository.event(identifier, "approval.approved", {"approvalId": approval_id})
        runtime.start(run, authorization, resume=True)
        return view(owned(identifier, owner))

    @router.post("/runs/{identifier}/approvals/{approval_id}/reject")
    async def reject(identifier: str, approval_id: str, authorization: str | None = Header(None)):
        owner = await actor(authorization)
        pending_approval(identifier, owner, approval_id)
        if repository.finish(identifier, "REJECTED"):
            repository.event(identifier, "approval.rejected", {"approvalId": approval_id})
        return view(owned(identifier, owner))

    @router.get("/runs/{identifier}/export")
    async def export(identifier: str, authorization: str | None = Header(None)):
        run = owned(identifier, await actor(authorization))
        if run["status"] != "SUCCEEDED" or not any(event["type"] == "approval.approved"
                                                   for event in repository.events(identifier)):
            raise HTTPException(409, "请在研究问题中提出导出，并确认后再下载")
        return {"filename": f"research-{identifier}.md", "content": json.loads(run["result"])["answer"]}

    @router.get("/runs/{identifier}/queryExecutionSummary")
    async def summary(identifier: str, authorization: str | None = Header(None)):
        run = owned(identifier, await actor(authorization))
        result = json.loads(run["result"]) if run["result"] else {}
        return {"run_id": identifier, "status": run["status"], "prompt_version": "python-agent-v1",
                "model_name": "百炼三角色", "model_rounds": result.get("modelRounds", 0),
                "tool_call_count": result.get("toolCallCount", 0), "total_tokens": result.get("totalTokens"),
                "duration_ms": result.get("durationMs"), "evidenceCount": len(result.get("evidence", [])),
                "sources": sorted({item["dataSource"] for item in result.get("evidence", [])}),
                "tools": result.get("tools", []), "agentOps": {"available": False}}

    def stream(identifier, owner, after=0):
        async def generate():
            cursor = after
            while True:
                events = repository.events(identifier, cursor)
                for event in events:
                    cursor = event["sequence"]
                    yield f"id: {cursor}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"
                if owned(identifier, owner)["status"] in TERMINAL | {"WAITING_APPROVAL"}:
                    # 状态更新与最终事件之间可能相差一个调度周期，再读一次保证尾事件可见。
                    for event in repository.events(identifier, cursor):
                        yield f"id: {event['sequence']}\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"
                    return
                yield ": keepalive\n\n"
                await asyncio.sleep(0.25)
        return StreamingResponse(generate(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @router.post("/chat/stream")
    async def chat(request: ChatRequest, authorization: str | None = Header(None)):
        owner = await actor(authorization)
        run = submit(request, owner, authorization)
        return stream(run["id"], owner)

    @router.get("/runs/{identifier}/stream")
    async def reconnect(identifier: str, after: int = 0, authorization: str | None = Header(None)):
        owner = await actor(authorization)
        owned(identifier, owner)
        return stream(identifier, owner, max(0, after))

    @router.get("/reports")
    async def reports(authorization: str | None = Header(None)):
        jobs = []
        for run in repository.history(await actor(authorization)):
            if run["job"]:
                jobs.append({"jobId": run["id"], "runId": run["id"], "status": run["status"], **json.loads(run["job"])})
        return jobs

    @router.post("/reports/monthly")
    async def monthly(request: MonthlyRequest, authorization: str | None = Header(None)):
        owner = await actor(authorization)
        try:
            year, month = map(int, request.month.split("-"))
            start = date(year, month, 1)
            end = min(date(year, month, calendar.monthrange(year, month)[1]), date.today())
            if start > date.today():
                raise ValueError()
        except ValueError:
            raise HTTPException(422, "月份无效或位于未来")
        tools = BusinessTools(authorization, identity)
        try:
            scopes = await tools.request("/api/v1/portfolios" if request.scopeKind == "PORTFOLIO" else "/api/v1/watchlists")
            def identifier(item):
                value = item.get("portfolioId") if request.scopeKind == "PORTFOLIO" else item.get("groupId")
                return value.get("value") if isinstance(value, dict) else value
            scope = next((item for item in scopes if identifier(item) == request.scopeId), None)
            if not scope:
                raise HTTPException(404, "当前用户没有这个组合或自选分组")
            positions = await tools.request(f"/api/v1/portfolios/{request.scopeId}/positions") if request.scopeKind == "PORTFOLIO" else scope.get("items", [])
            codes = [item["fundCode"].get("value") if isinstance(item["fundCode"], dict) else item["fundCode"] for item in positions]
            if not codes:
                raise HTTPException(422, "所选范围没有基金")
            if len(codes) > 10:
                raise HTTPException(422, "当前月报最多研究十只基金，请缩小范围")
        except FundApiError as error:
            raise HTTPException(error.status_code, str(error)) from error
        question = (f"生成 {request.month} 月报，范围为{request.scopeKind} {request.scopeId}，"
                    f"基金为 {','.join(codes)}。指标区间必须是 {start} 至 {end}。"
                    "总结收益风险和数据限制。只报告这个范围，组合估值需说明是当前持仓口径而非历史月末持仓。")
        job = {"periodStart": str(start), "periodEnd": str(end), "scopeKind": request.scopeKind,
               "scopeId": request.scopeId, "fundCodes": codes}
        run = submit(ChatRequest(message=question), owner, authorization, job)
        return {"jobId": run["id"], "runId": run["id"], "status": run["status"], **job}

    return router
