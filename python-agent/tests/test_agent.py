"""统一 Agent 的边界测试：跑真实图，替换外部模型与网络。"""

import asyncio
import json
from functools import partial

import httpx
import pytest
from langchain_core.messages import AIMessage, ToolMessage

from agent.graph import AgentGraph
from agent.market import MarketData
from agent.repository import AgentRepository
from agent.tools import BusinessTools
from agent.events import BusinessEvent, EventService
from agent.pcf import parse_szse
from app import create_app
from client import IdentityClient
from store import RunStore


class Model:
    def __init__(self, role, bad_citation=False, delay=0):
        self.role = role
        self.calls = 0
        self.bad_citation = bad_citation
        self.delay = delay

    def bind_tools(self, tools):
        return self

    async def ainvoke(self, messages):
        self.calls += 1
        await asyncio.sleep(self.delay)
        if self.role == "bull" and not any(isinstance(message, ToolMessage) for message in messages):
            return AIMessage(content="", tool_calls=[{"name": "get_my_watchlist", "args": {}, "id": "call1", "type": "tool_call"}])
        return AIMessage(content="数据范围有限 [E99]" if self.bad_citation else "请核对自选范围 [E01]",
                         usage_metadata={"input_tokens": 10, "output_tokens": 5, "total_tokens": 15})


def upstream(request):
    if request.url.path == "/api/v1/users/me":
        user = "bob" if request.headers.get("Authorization") == "Bearer bob" else "alice"
        data = {"userId": {"value": user}}
    elif request.url.path == "/api/v1/watchlists":
        data = [{"groupId": "g1", "displayName": "我的自选", "items": [{"fundCode": "000001"}]}]
    else:
        return httpx.Response(404)
    return httpx.Response(200, json={"code": "SUCCESS", "data": data})


def setup_app(tmp_path, monkeypatch, bad=False, delay=0):
    monkeypatch.setattr("agent.api.model_for", lambda role: object())
    identity = IdentityClient(transport=httpx.MockTransport(upstream))
    app = create_app(store=RunStore(str(tmp_path / "runs.db")), identity=identity)
    models = {role: Model(role, bad_citation=(bad and role == "judge"), delay=delay)
              for role in ("bull", "bear", "judge")}
    app.state.agent_runtime.graph_factory = partial(AgentGraph, models=models)
    return app, models


async def settled(client, identifier):
    for _ in range(100):
        response = await client.get(f"/api/agent/runs/{identifier}")
        if response.json()["status"] != "RUNNING":
            return response.json()
        await asyncio.sleep(0.01)
    pytest.fail("后台任务未结束")


@pytest.mark.asyncio
async def test_chat_real_graph_and_owned_history(tmp_path, monkeypatch):
    app, models = setup_app(tmp_path, monkeypatch)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test",
                                 headers={"Authorization": "Bearer alice"}) as client:
        conversation = (await client.post("/api/agent/conversations")).json()["conversationId"]
        response = await client.post("/api/agent/chat/stream", json={"conversationId": conversation, "message": "我的自选"})
        assert response.status_code == 200
        events = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
        completed = next(event for event in events if event["type"] == "answer.completed")
        identifier = completed["runId"]
        assert completed["data"]["evidence"][0]["evidenceId"] == "E01"
        assert models["bull"].calls == 2 and models["bear"].calls == models["judge"].calls == 1
        assert (await client.get(f"/api/agent/runs/{identifier}/report")).status_code == 200
        assert (await client.get(f"/api/agent/runs/{identifier}", headers={"Authorization": "Bearer bob"})).status_code == 404
        assert (await client.post("/api/agent/runs", headers={"Authorization": "Bearer bob"},
                                  json={"conversationId": conversation, "message": "读取历史"})).status_code == 404
        raw = app.state.agent_repository.path.read_bytes()
        assert b"Bearer alice" not in raw


@pytest.mark.asyncio
async def test_approval_checkpoint_survives_runtime_recreation(tmp_path, monkeypatch):
    app, models = setup_app(tmp_path, monkeypatch)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test",
                                 headers={"Authorization": "Bearer alice"}) as client:
        run = (await client.post("/api/agent/runs", json={"message": "研究我的自选并导出报告"})).json()
        identifier = run["runId"]
        assert (await settled(client, identifier))["status"] == "WAITING_APPROVAL"
        events = (await client.get(f"/api/agent/runs/{identifier}/events")).json()
        approval = json.loads(next(event["payloadJson"] for event in events if event["eventType"] == "approval.requested"))["approvalId"]
        assert (await client.get(f"/api/agent/runs/{identifier}/export")).status_code == 409
        # 新建服务实例，模型故意换为会报错的对象，证明恢复不重跑前面的模型。
        new_app, unused_models = setup_app(tmp_path, monkeypatch)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(new_app), base_url="http://test",
                                     headers={"Authorization": "Bearer alice"}) as resumed:
            response = await resumed.post(f"/api/agent/runs/{identifier}/approvals/{approval}", json={"parameters": "任意内容不能修改检查点"})
            assert response.status_code == 200
            assert (await settled(resumed, identifier))["status"] == "SUCCEEDED"
            assert sum(model.calls for model in unused_models.values()) == 0
            assert (await resumed.get(f"/api/agent/runs/{identifier}/export")).json()["content"].endswith("[E01]")
            assert (await resumed.post(f"/api/agent/runs/{identifier}/approvals/{approval}", json={})).status_code == 409
        assert sum(model.calls for model in models.values()) == 4


@pytest.mark.asyncio
async def test_cancel_never_changes_back_to_success(tmp_path, monkeypatch):
    app, _ = setup_app(tmp_path, monkeypatch, delay=0.2)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test",
                                 headers={"Authorization": "Bearer alice"}) as client:
        identifier = (await client.post("/api/agent/runs", json={"message": "读取自选"})).json()["runId"]
        await asyncio.sleep(0.01)
        assert (await client.post(f"/api/agent/runs/{identifier}/cancel")).json()["status"] == "CANCELLED"
        await asyncio.sleep(0.02)
        assert (await client.get(f"/api/agent/runs/{identifier}/report")).status_code == 409
        assert not any(event["type"] == "answer.completed" for event in app.state.agent_repository.events(identifier))


@pytest.mark.asyncio
async def test_unknown_citation_fails_run(tmp_path, monkeypatch):
    app, _ = setup_app(tmp_path, monkeypatch, bad=True)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test",
                                 headers={"Authorization": "Bearer alice"}) as client:
        identifier = (await client.post("/api/agent/runs", json={"message": "读取自选"})).json()["runId"]
        assert (await settled(client, identifier))["status"] == "FAILED"
        assert (await client.get(f"/api/agent/runs/{identifier}/report")).status_code == 409


@pytest.mark.asyncio
async def test_monthly_scope_must_belong_to_user(tmp_path, monkeypatch):
    app, _ = setup_app(tmp_path, monkeypatch)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test",
                                 headers={"Authorization": "Bearer alice"}) as client:
        assert (await client.post("/api/agent/reports/monthly", json={"month": "2026-01", "scopeKind": "WATCHLIST", "scopeId": "someone-else"})).status_code == 404
        assert (await client.post("/api/agent/reports/monthly", json={"month": "2026-99", "scopeKind": "WATCHLIST", "scopeId": "g1"})).status_code == 422


def test_recovery_marks_interrupted_runs(tmp_path):
    repository = AgentRepository(tmp_path / "runs.db")
    run = repository.create("alice", "分析")
    repository.recover()
    assert repository.get(run["id"], "alice")["status"] == "FAILED"
    assert repository.events(run["id"])[0]["type"] == "run.failed"


def test_sector_calculations_require_enough_samples():
    rows = [[f"day{index}", "", str(100 + index)] for index in range(61)]
    metrics = MarketData.sector_metrics(rows)
    assert metrics["return60dPercent"] == pytest.approx(60)
    assert metrics["maxDrawdownPercent"] == 0
    with pytest.raises(ValueError):
        MarketData.sector_metrics(rows[:10])


@pytest.mark.asyncio
async def test_tool_path_and_portfolio_cannot_be_injected():
    def service(request):
        assert request.headers["Authorization"] == "Bearer alice"
        if request.url.path == "/api/v1/portfolios":
            return httpx.Response(200, json={"code": "SUCCESS", "data": []})
        pytest.fail("不应该请求未授权路径")
    tools = BusinessTools("Bearer alice", IdentityClient(transport=httpx.MockTransport(service)))
    with pytest.raises(Exception):
        await tools.call("get_fund_profile", {"fundCode": "../../users"})
    with pytest.raises(Exception):
        await tools.call("get_my_portfolio", {"portfolioId": "00000000-0000-4000-8000-000000000000"})


@pytest.mark.asyncio
async def test_business_events_require_separate_secret(tmp_path, monkeypatch):
    app, _ = setup_app(tmp_path, monkeypatch)
    event = {"eventId": "one", "eventType": "PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED",
             "schemaVersion": "v1", "ownerUserId": "alice", "payload": {"previous": -5, "current": -12}}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://test") as client:
        assert (await client.post("/internal/agent/events", json=event)).status_code == 401
        headers = {"X-Agent-Event-Key": app.state.event_service.key}
        first = (await client.post("/internal/agent/events", json=event, headers=headers)).json()
        replay = (await client.post("/internal/agent/events", json=event, headers=headers)).json()
        assert first == replay and first["shouldNotify"]
        event["eventId"] = "two"
        assert (await client.post("/internal/agent/events", json=event, headers=headers)).json()["reason"] == "COOLDOWN"


def test_event_cooldown_survives_restart_and_quiet_hours(tmp_path):
    from datetime import datetime, timezone, timedelta
    repository = AgentRepository(tmp_path / "runs.db")
    service = EventService(repository)
    timestamp = datetime(2026, 9, 30, tzinfo=timezone.utc)
    def event(identifier, quiet=False):
        return BusinessEvent(eventId=identifier, eventType="PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED",
                             schemaVersion="v1", ownerUserId="alice",
                             payload={"previous": -5, "current": -12, "quietHours": quiet})
    assert service.dispatch(event("quiet", True), timestamp)["held"]
    assert service.dispatch(event("fire"), timestamp)["shouldNotify"]
    new_service = EventService(repository)
    assert new_service.dispatch(event("cool"), timestamp + timedelta(hours=1))["reason"] == "COOLDOWN"
    assert new_service.dispatch(event("later"), timestamp + timedelta(hours=7))["shouldNotify"]


def test_szse_pcf_parser_does_not_execute_script():
    rows = parse_szse("组合信息内容\n600001  公司一  1,200  允许\n159900  占位代码  100  替代\n600002  无数量  0  禁止\n")
    assert rows == [{"stockCode": "600001", "stockName": "公司一", "quantity": 1200}]


@pytest.mark.asyncio
async def test_monthly_tool_range_is_enforced_by_code():
    requests = []
    def service(request):
        requests.append(request)
        return httpx.Response(200, json={"code": "SUCCESS", "data": {"value": "data"}})
    scope = {"fundCodes": ["000001"], "periodStart": "2026-01-01", "periodEnd": "2026-01-31",
             "scopeKind": "WATCHLIST", "scopeId": "g1"}
    tools = BusinessTools("Bearer alice", IdentityClient(transport=httpx.MockTransport(service)), scope)
    with pytest.raises(Exception):
        await tools.call("get_fund_profile", {"fundCode": "110022"})
    assert not requests
    await tools.call("calculate_fund_metrics", {"fundCode": "000001", "startDate": "2025-01-01", "endDate": "2025-12-31"})
    assert requests[0].url.params["startDate"] == "2026-01-01"
    assert requests[0].url.params["endDate"] == "2026-01-31"
