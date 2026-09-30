"""跨节点传递的结构化数据；接口字段均可直接序列化为 JSON。"""

from datetime import date, datetime
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator


class CompareRequest(BaseModel):
    fund_codes: list[str] = Field(alias="fundCodes", min_length=2, max_length=2)
    start_date: date = Field(alias="startDate")
    end_date: date = Field(alias="endDate")
    nav_basis: str | None = Field(default=None, alias="navBasis")

    @field_validator("fund_codes")
    @classmethod
    def valid_codes(cls, codes: list[str]) -> list[str]:
        if any(len(code) != 6 or not code.isascii() or not code.isdigit() for code in codes):
            raise ValueError("基金代码必须是 6 位数字")
        if codes[0] == codes[1]:
            raise ValueError("请选择两只不同的基金")
        return codes

    @model_validator(mode="after")
    def valid_dates(self) -> "CompareRequest":
        if self.start_date > self.end_date:
            raise ValueError("startDate 不能晚于 endDate")
        if self.end_date > date.today():
            raise ValueError("endDate 不能晚于今天")
        return self


class MetricEvidence(BaseModel):
    status: str
    value: Decimal | None
    evidence_id: str = Field(alias="evidenceId")
    unavailable_reason: str | None = Field(default=None, alias="unavailableReason")


class FundSnapshot(BaseModel):
    fund_code: str = Field(alias="fundCode")
    observation_count: int = Field(alias="observationCount")
    coverage_status: str = Field(alias="coverageStatus")
    data_version: str = Field(alias="dataVersion")
    algorithm_version: str = Field(alias="algorithmVersion")
    metrics: dict[str, MetricEvidence]


class ComparisonSnapshot(BaseModel):
    common_start_date: date = Field(alias="commonStartDate")
    common_end_date: date = Field(alias="commonEndDate")
    nav_basis: str = Field(alias="navBasis")
    funds: list[FundSnapshot]

    def evidence_ids(self) -> set[str]:
        return {
            metric.evidence_id
            for fund in self.funds
            for metric in fund.metrics.values()
            if metric.status == "AVAILABLE"
        }


class Claim(BaseModel):
    statement: str = Field(min_length=1)
    evidence_ids: list[str] = Field(alias="evidenceIds", min_length=1)


class AnalystOpinion(BaseModel):
    summary: str = Field(min_length=1)
    claims: list[Claim] = Field(min_length=1, max_length=4)
    limitations: list[str] = Field(default_factory=list)


class FinalDecision(BaseModel):
    preferred_fund_code: str | None = Field(alias="preferredFundCode")
    conclusion: str = Field(min_length=1)
    rationale: list[Claim] = Field(default_factory=list, max_length=4)
    disagreements: list[str] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)


class NodeTrace(BaseModel):
    role: Literal["data", "bull", "bear", "judge"]
    started_at: datetime = Field(alias="startedAt")
    finished_at: datetime = Field(alias="finishedAt")
    duration_ms: int = Field(alias="durationMs")


class ResearchResult(BaseModel):
    run_id: str = Field(alias="runId")
    snapshot: ComparisonSnapshot
    bull: AnalystOpinion
    bear: AnalystOpinion
    decision: FinalDecision
    trace: list[NodeTrace]
