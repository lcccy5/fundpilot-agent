"""LangGraph 多 Agent 研究流程：同一份数据，并行观点，最后裁决。"""

import os
import json
from datetime import datetime, timezone
from time import perf_counter
from typing import AsyncIterator, NotRequired, Protocol, TypedDict, TypeVar
from uuid import uuid4

from langgraph.graph import END, START, StateGraph
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ValidationError

from client import FundApiError, FundClient
from models import (
    AnalystOpinion,
    CompareRequest,
    ComparisonSnapshot,
    FinalDecision,
    NodeTrace,
    ResearchResult,
)


class ModelUnavailable(Exception):
    pass


class ModelError(Exception):
    pass


class ResearchModel(Protocol):
    def ensure_available(self) -> None: ...

    async def analyst(self, role: str, snapshot: ComparisonSnapshot) -> AnalystOpinion: ...

    async def judge(
        self, snapshot: ComparisonSnapshot, bull: AnalystOpinion, bear: AnalystOpinion
    ) -> FinalDecision: ...


Output = TypeVar("Output", bound=BaseModel)


class OpenAIResearchModel:
    """三个研究角色共用百炼密钥，但各自调用不同的千问模型。"""

    def __init__(self) -> None:
        # 优先复用本机已有的百炼密钥；不把密钥写进项目文件。
        self.api_key = os.getenv("RESEARCH_API_KEY") or os.getenv("DASHSCOPE_API_KEY")
        self.base_url = os.getenv(
            "RESEARCH_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
        )
        self.models = {
            "bull": os.getenv("RESEARCH_BULL_MODEL", "qwen3.8-flash"),
            "bear": os.getenv("RESEARCH_BEAR_MODEL", "qwen3.7-plus"),
            "judge": os.getenv("RESEARCH_JUDGE_MODEL", "qwen3.8-max"),
        }

    def ensure_available(self) -> None:
        if not self.api_key:
            raise ModelUnavailable("请设置 DASHSCOPE_API_KEY 或 RESEARCH_API_KEY 后再运行研究 Agent")

    @staticmethod
    def _prompt_snapshot(snapshot: ComparisonSnapshot) -> tuple[str, dict[str, str]]:
        """模型只看短编号；完整编号留在服务端，返回后再还原。"""
        data = snapshot.model_dump(mode="json", by_alias=True)
        aliases: dict[str, str] = {}
        for fund in data["funds"]:
            for metric in fund["metrics"].values():
                if metric["status"] != "AVAILABLE":
                    metric.pop("evidenceId", None)
                    continue
                short_id = f"E{len(aliases) + 1:02d}"
                aliases[short_id] = metric["evidenceId"]
                metric["evidenceId"] = short_id
        return json.dumps(data, ensure_ascii=False), aliases

    @staticmethod
    def _expand_evidence(opinion: AnalystOpinion | FinalDecision, aliases: dict[str, str]) -> None:
        claims = opinion.claims if isinstance(opinion, AnalystOpinion) else opinion.rationale
        for claim in claims:
            if any(short_id not in aliases for short_id in claim.evidence_ids):
                raise ModelError("模型引用了本次快照之外的证据编号")
            claim.evidence_ids = [aliases[short_id] for short_id in claim.evidence_ids]

    async def _ask(self, role: str, system: str, user: str, schema: type[Output]) -> Output:
        self.ensure_available()
        try:
            # LangChain 统一模型调用接口；LangGraph 负责角色执行顺序与状态。
            model = ChatOpenAI(
                model=self.models[role],
                api_key=self.api_key,
                base_url=self.base_url,
                timeout=45.0,
                extra_body={"enable_thinking": False},
            )
            response = await model.ainvoke(
                [("system", system), ("human", user)],
                response_format={"type": "json_object"},
            )
            content = response.content
            if not isinstance(content, str) or not content:
                raise ModelError("模型没有返回研究结果")
            # 某些兼容模型会给 JSON 外面包一层 Markdown 代码块。
            cleaned = content.strip()
            if cleaned.startswith("```") and cleaned.endswith("```"):
                cleaned = cleaned.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
            return schema.model_validate(json.loads(cleaned))
        except json.JSONDecodeError as error:
            raise ModelError("模型没有返回有效 JSON") from error
        except ValidationError as error:
            raise ModelError("模型返回的 JSON 不符合研究结果格式") from error
        except ModelError:
            raise
        except Exception as error:
            # 不把供应商异常原文返回给前端，避免泄露密钥或请求内容。
            raise ModelError("模型调用失败，请检查模型服务及其 JSON 输出能力") from error

    async def analyst(self, role: str, snapshot: ComparisonSnapshot) -> AnalystOpinion:
        angle = "寻找两只基金中较有利的历史证据" if role == "bull" else "质疑收益稳定性、波动和数据缺口"
        system = (
            f"你是基金研究团队的{role}分析师，任务是{angle}。"
            "只使用用户提供的数据，不推测未来收益，也不编造基金名称或市场新闻。"
            "只返回 JSON：summary 字符串，claims 数组（每项含 statement 和 evidenceIds），"
            "limitations 字符串数组。每条可检验的论断必须引用给出的 evidenceId。"
            "evidenceIds 只能填写快照中 E01、E02 这类完整短编号，不要拆开编号。"
            "最多四条 claims，中文简洁表达。"
        )
        prompt_snapshot, aliases = self._prompt_snapshot(snapshot)
        opinion = await self._ask(role, system, prompt_snapshot, AnalystOpinion)
        self._expand_evidence(opinion, aliases)
        return opinion

    async def judge(
        self, snapshot: ComparisonSnapshot, bull: AnalystOpinion, bear: AnalystOpinion
    ) -> FinalDecision:
        system = (
            "你是独立的基金研究裁决员。综合两位分析师的观点，指出分歧和数据限制。"
            "只依据原始快照中的指标裁决；历史表现不能保证未来收益。"
            "证据不足时 preferredFundCode 必须为 null。"
            "只返回 JSON：preferredFundCode（六码基金代码或 null）、conclusion 字符串、"
            "rationale 数组（每项含 statement 和 evidenceIds）、disagreements 字符串数组、"
            "limitations 字符串数组。evidenceIds 只能填写快照中的 E 编号。中文简洁表达。"
        )
        prompt_snapshot, aliases = self._prompt_snapshot(snapshot)
        full_to_short = {full: short for short, full in aliases.items()}
        bull_data = bull.model_dump(mode="json", by_alias=True)
        bear_data = bear.model_dump(mode="json", by_alias=True)
        for opinion in (bull_data, bear_data):
            for claim in opinion["claims"]:
                claim["evidenceIds"] = [full_to_short[value] for value in claim["evidenceIds"]]
        user = (
            f"原始数据：{prompt_snapshot}\n"
            f"看多观点：{json.dumps(bull_data, ensure_ascii=False)}\n"
            f"看空观点：{json.dumps(bear_data, ensure_ascii=False)}"
        )
        decision = await self._ask("judge", system, user, FinalDecision)
        self._expand_evidence(decision, aliases)
        return decision


class ResearchState(TypedDict):
    request: CompareRequest
    authorization: NotRequired[str | None]
    snapshot: NotRequired[ComparisonSnapshot]
    bull: NotRequired[AnalystOpinion]
    bear: NotRequired[AnalystOpinion]
    decision: NotRequired[FinalDecision]
    data_trace: NotRequired[NodeTrace]
    bull_trace: NotRequired[NodeTrace]
    bear_trace: NotRequired[NodeTrace]
    judge_trace: NotRequired[NodeTrace]


def _trace(role: str, started_at: datetime, started_clock: float) -> NodeTrace:
    return NodeTrace.model_validate({
        "role": role,
        "startedAt": started_at,
        "finishedAt": datetime.now(timezone.utc),
        "durationMs": max(0, round((perf_counter() - started_clock) * 1000)),
    })


def _verify_claims(opinion: AnalystOpinion | FinalDecision, snapshot: ComparisonSnapshot) -> None:
    valid = snapshot.evidence_ids()
    claims = opinion.claims if isinstance(opinion, AnalystOpinion) else opinion.rationale
    for claim in claims:
        if not set(claim.evidence_ids).issubset(valid):
            raise ModelError("模型引用了当前比较快照之外的证据")


class ResearchWorkflow:
    def __init__(self, funds: FundClient, model: ResearchModel) -> None:
        self.funds = funds
        self.model = model
        graph = StateGraph(ResearchState)
        graph.add_node("data", self._data)
        graph.add_node("bull", self._bull)
        graph.add_node("bear", self._bear)
        graph.add_node("judge", self._judge)
        graph.add_edge(START, "data")
        graph.add_edge("data", "bull")
        graph.add_edge("data", "bear")
        # 两位分析师写入不同的状态字段；裁决员等待两个分支都完成。
        graph.add_edge(["bull", "bear"], "judge")
        graph.add_edge("judge", END)
        self.graph = graph.compile()

    async def run(self, request: CompareRequest, authorization: str | None = None) -> ResearchResult:
        self.model.ensure_available()
        state = await self.graph.ainvoke({"request": request, "authorization": authorization})
        return self.result_from_state(state)

    async def updates(
        self, request: CompareRequest, authorization: str | None = None
    ) -> AsyncIterator[tuple[str, dict]]:
        """逐个交出已完成节点的增量；并行分支按实际完成顺序到达。"""
        self.model.ensure_available()
        async for update in self.graph.astream(
            {"request": request, "authorization": authorization}, stream_mode="updates"
        ):
            for role, changes in update.items():
                yield role, changes

    @staticmethod
    def result_from_state(state: ResearchState, run_id: str | None = None) -> ResearchResult:
        """同步接口和事件流共用同一套最终结果结构。"""
        return ResearchResult(
            runId=run_id or str(uuid4()),
            snapshot=state["snapshot"],
            bull=state["bull"],
            bear=state["bear"],
            decision=state["decision"],
            trace=[state[f"{role}_trace"] for role in ("data", "bull", "bear", "judge")],
        )

    async def _data(self, state: ResearchState) -> dict:
        started_at, started_clock = datetime.now(timezone.utc), perf_counter()
        snapshot = await self.funds.compare(state["request"], state.get("authorization"))
        if any(not any(metric.status == "AVAILABLE" for metric in fund.metrics.values()) for fund in snapshot.funds):
            raise FundApiError("至少一只基金缺少可用于研究的指标", 422)
        return {"snapshot": snapshot, "data_trace": _trace("data", started_at, started_clock)}

    async def _bull(self, state: ResearchState) -> dict:
        started_at, started_clock = datetime.now(timezone.utc), perf_counter()
        opinion = await self.model.analyst("bull", state["snapshot"])
        _verify_claims(opinion, state["snapshot"])
        return {"bull": opinion, "bull_trace": _trace("bull", started_at, started_clock)}

    async def _bear(self, state: ResearchState) -> dict:
        started_at, started_clock = datetime.now(timezone.utc), perf_counter()
        opinion = await self.model.analyst("bear", state["snapshot"])
        _verify_claims(opinion, state["snapshot"])
        return {"bear": opinion, "bear_trace": _trace("bear", started_at, started_clock)}

    async def _judge(self, state: ResearchState) -> dict:
        started_at, started_clock = datetime.now(timezone.utc), perf_counter()
        decision = await self.model.judge(state["snapshot"], state["bull"], state["bear"])
        _verify_claims(decision, state["snapshot"])
        codes = {fund.fund_code for fund in state["snapshot"].funds}
        if decision.preferred_fund_code is not None and decision.preferred_fund_code not in codes:
            raise ModelError("模型选择了本次比较范围之外的基金")
        return {"decision": decision, "judge_trace": _trace("judge", started_at, started_clock)}
