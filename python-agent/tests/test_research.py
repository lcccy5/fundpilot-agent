"""覆盖真实 HTTP 契约和 LangGraph 节点交接，不依赖外部服务。"""

import asyncio
import json
from datetime import date
from decimal import Decimal
from types import SimpleNamespace

import httpx
import pytest

from app import create_app
from client import FundApiError, FundClient, IdentityClient
from models import AnalystOpinion, CompareRequest, FinalDecision
from research import ModelUnavailable, OpenAIResearchModel, ResearchWorkflow
from store import RunStore


def comparison_data() -> dict:
    def fund(code: str, cumulative: str, volatility: str) -> dict:
        def metric(value: str) -> dict:
            return {"status": "AVAILABLE", "value": value, "unavailableReason": None}

        return {
            # 与 Java 接口的真实 JSON 一致：fundCode 序列化为字符串。
            "fundCode": code,
            "observationCount": 180,
            "coverage": {"status": "COMPLETE"},
            "dataVersion": "7",
            "algorithmVersion": "fund-metrics-v1",
            "cumulativeReturn": metric(cumulative),
            "annualizedReturn": metric(cumulative),
            "annualizedVolatility": metric(volatility),
            "maxDrawdown": metric("-0.08"),
            "sharpeRatio": metric("0.7"),
        }

    return {
        "code": "SUCCESS",
        "data": {
            "commonStartDate": "2026-01-05",
            "commonEndDate": "2026-09-29",
            "navBasis": "UNIT_NAV",
            "funds": [fund("000001", "0.12", "0.18"), fund("110022", "0.09", "0.11")],
        },
    }


class FakeModel:
    def __init__(self, bad_evidence: bool = False):
        self.bad_evidence = bad_evidence
        self.running = 0
        self.parallel = False
        self.calls: list[str] = []

    def ensure_available(self) -> None:
        pass

    async def analyst(self, role, snapshot):
        self.calls.append(role)
        self.running += 1
        await asyncio.sleep(0.01)
        self.parallel = self.parallel or self.running == 2
        self.running -= 1
        evidence = next(iter(snapshot.evidence_ids()))
        if self.bad_evidence:
            evidence = "不存在的证据"
        return AnalystOpinion.model_validate({
            "summary": f"{role} 的观点",
            "claims": [{"statement": "有可比较的历史指标", "evidenceIds": [evidence]}],
        })

    async def judge(self, snapshot, bull, bear):
        self.calls.append("judge")
        assert bull.summary and bear.summary
        return FinalDecision.model_validate({
            "preferredFundCode": None,
            "conclusion": "仅凭历史指标无法判断未来表现",
            "rationale": [],
            "disagreements": ["收益和波动侧重点不同"],
            "limitations": ["缺少未来信息"],
        })


def make_client(payload=None, required_authorization=None):
    def handle(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/fund-comparisons"
        if required_authorization and request.headers.get("authorization") != required_authorization:
            return httpx.Response(401, json={"error": "UNAUTHORIZED"})
        body = json.loads(request.content)
        assert body["fundCodes"] == ["000001", "110022"]
        assert body["startDate"] == "2026-01-01"
        return httpx.Response(200, json=payload or comparison_data())

    return FundClient("http://java.test", httpx.MockTransport(handle))


class FakeIdentity:
    async def user_id(self, authorization):
        if not authorization:
            raise FundApiError("请先登录", 401)
        return "owner-b" if authorization == "Bearer other-user" else "owner-a"


def make_app(workflow, store=None):
    return create_app(workflow, store, FakeIdentity())


@pytest.mark.asyncio
async def test_identity_uses_java_to_verify_token():
    def handle(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/users/me"
        assert request.headers["authorization"] == "Bearer real-token"
        return httpx.Response(200, json={"code": "SUCCESS", "data": {"userId": {"value": "owner-a"}}})

    identity = IdentityClient("http://java.test", httpx.MockTransport(handle))
    assert await identity.user_id("Bearer real-token") == "owner-a"
    with pytest.raises(FundApiError) as error:
        await identity.user_id(None)
    assert error.value.status_code == 401


@pytest.mark.asyncio
async def test_research_forwards_login_token_to_java_without_returning_it():
    token = "Bearer test-session-token"
    app = make_app(ResearchWorkflow(make_client(required_authorization=token), FakeModel()))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test") as client:
        response = await client.post("/api/research/compare/stream", headers={"Authorization": token}, json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })
        missing = await client.post("/api/research/compare", json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })

    assert "event: completed" in response.text
    assert "test-session-token" not in response.text
    assert missing.status_code == 401
    assert "登录" in missing.json()["detail"]


@pytest.mark.asyncio
async def test_bailian_models_are_selected_per_role_with_one_key(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "test-key")
    monkeypatch.delenv("RESEARCH_API_KEY", raising=False)
    calls = []

    class FakeChatModel:
        def __init__(self, **settings):
            assert settings["api_key"] == "test-key"
            assert settings["base_url"] == "https://dashscope.aliyuncs.com/compatible-mode/v1"
            assert settings["extra_body"] == {"enable_thinking": False}
            self.model = settings["model"]

        async def ainvoke(self, messages, **options):
            calls.append({"model": self.model, "messages": messages, **options})
            if self.model == "qwen3.8-max":
                content = json.dumps({"preferredFundCode": None, "conclusion": "证据不足"})
            else:
                content = json.dumps({"summary": "有历史依据", "claims": [
                    {"statement": "可比较", "evidenceIds": ["E01"]}
                ]})
            return SimpleNamespace(content=content)

    monkeypatch.setattr("research.ChatOpenAI", FakeChatModel)
    model = OpenAIResearchModel()
    snapshot = await make_client().compare(CompareRequest.model_validate({
        "fundCodes": ["000001", "110022"],
        "startDate": "2026-01-01",
        "endDate": "2026-09-29",
    }))
    bull = await model.analyst("bull", snapshot)
    bear = await model.analyst("bear", snapshot)
    await model.judge(snapshot, bull, bear)

    assert bull.claims[0].evidence_ids[0] in snapshot.evidence_ids()
    assert '"evidenceId": "E01"' in calls[0]["messages"][1][1]
    assert [call["model"] for call in calls] == ["qwen3.8-flash", "qwen3.7-plus", "qwen3.8-max"]
    assert all(call["response_format"] == {"type": "json_object"} for call in calls)


@pytest.mark.asyncio
async def test_compare_runs_parallel_analysts_and_judge():
    model = FakeModel()
    workflow = ResearchWorkflow(make_client(), model)
    request = CompareRequest.model_validate({
        "fundCodes": ["000001", "110022"],
        "startDate": "2026-01-01",
        "endDate": "2026-09-29",
    })

    result = await workflow.run(request)

    assert model.parallel
    assert model.calls[-1] == "judge"
    assert result.snapshot.funds[0].fund_code == "000001"
    assert result.snapshot.funds[0].metrics["cumulativeReturn"].value == Decimal("0.12")
    assert [trace.role for trace in result.trace] == ["data", "bull", "bear", "judge"]
    assert result.decision.preferred_fund_code is None


@pytest.mark.asyncio
async def test_api_rejects_fabricated_evidence():
    app = make_app(ResearchWorkflow(make_client(), FakeModel(bad_evidence=True)))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test", headers={"Authorization": "Bearer test-user"}) as client:
        response = await client.post("/api/research/compare", json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })
    assert response.status_code == 502
    assert "证据" in response.json()["detail"]


@pytest.mark.asyncio
async def test_api_saves_result_for_later_read(tmp_path):
    app = make_app(
        ResearchWorkflow(make_client(), FakeModel()),
        RunStore(str(tmp_path / "research.db")),
    )
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test", headers={"Authorization": "Bearer test-user"}) as client:
        response = await client.post("/api/research/compare", json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })
        assert response.status_code == 200
        run_id = response.json()["runId"]
        saved = await client.get(f"/api/research/runs/{run_id}")
        missing = await client.get("/api/research/runs/unknown")
        other_owner = await client.get(
            f"/api/research/runs/{run_id}", headers={"Authorization": "Bearer other-user"}
        )

    assert saved.status_code == 200
    assert saved.json() == response.json()
    assert missing.status_code == 404
    assert other_owner.status_code == 404


@pytest.mark.asyncio
async def test_stream_reports_graph_nodes_and_saves_same_result(tmp_path):
    model = FakeModel()
    app = make_app(
        ResearchWorkflow(make_client(), model),
        RunStore(str(tmp_path / "stream.db")),
    )
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test", headers={"Authorization": "Bearer test-user"}) as client:
        response = await client.post("/api/research/compare/stream", json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })

        # SSE 每个事件由空行分隔；测试真实 HTTP 输出而不只测试生成器。
        events = []
        for block in response.text.strip().split("\n\n"):
            lines = block.splitlines()
            name = next((line[7:] for line in lines if line.startswith("event: ")), None)
            data = next((line[6:] for line in lines if line.startswith("data: ")), None)
            if name and data:
                events.append((name, json.loads(data)))
        saved = await client.get(f"/api/research/runs/{events[-1][1]['result']['runId']}")

    assert response.status_code == 200
    assert events[0][0] == "started"
    nodes = [item[1]["node"] for item in events if item[0] == "node_completed"]
    assert nodes[0] == "data"
    assert set(nodes[1:3]) == {"bull", "bear"}
    assert nodes[-1] == "judge"
    assert model.parallel
    assert events[-1][0] == "completed"
    assert saved.json() == events[-1][1]["result"]


@pytest.mark.asyncio
async def test_stream_reports_model_failure_without_claiming_completion():
    app = make_app(ResearchWorkflow(make_client(), FakeModel(bad_evidence=True)))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test", headers={"Authorization": "Bearer test-user"}) as client:
        response = await client.post("/api/research/compare/stream", json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })
    assert response.status_code == 200  # SSE 已开始，错误由 failed 事件表达。
    assert "event: failed" in response.text
    assert "event: completed" not in response.text


@pytest.mark.asyncio
async def test_api_validates_request_before_calling_model():
    app = make_app(ResearchWorkflow(make_client(), FakeModel()))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test", headers={"Authorization": "Bearer test-user"}) as client:
        response = await client.post("/api/research/compare", json={
            "fundCodes": ["000001", "000001"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_missing_model_configuration_returns_503():
    class MissingModel(FakeModel):
        def ensure_available(self):
            raise ModelUnavailable("模型未配置")

    app = make_app(ResearchWorkflow(make_client(), MissingModel()))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test", headers={"Authorization": "Bearer test-user"}) as client:
        response = await client.post("/api/research/compare", json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })
    assert response.status_code == 503


@pytest.mark.asyncio
async def test_java_contract_error_returns_502():
    app = make_app(ResearchWorkflow(make_client({"code": "SUCCESS", "data": {}}), FakeModel()))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://agent.test", headers={"Authorization": "Bearer test-user"}) as client:
        response = await client.post("/api/research/compare", json={
            "fundCodes": ["000001", "110022"],
            "startDate": "2026-01-01",
            "endDate": "2026-09-29",
        })
    assert response.status_code == 502
    assert "字段不完整" in response.json()["detail"]


def test_request_rejects_future_end_date():
    with pytest.raises(ValueError):
        CompareRequest.model_validate({
            "fundCodes": ["000001", "110022"],
            "startDate": date.today().isoformat(),
            "endDate": "2099-01-01",
        })
