"""访问 Java 基金服务，并把上游结果转换为有证据编号的快照。"""

import os
from datetime import date
from typing import Any

import httpx

from models import CompareRequest, ComparisonSnapshot


class FundApiError(Exception):
    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.status_code = status_code


class IdentityClient:
    """让 Java 业务层验证登录令牌，Python 只使用返回的用户 ID。"""

    def __init__(self, base_url: str | None = None, transport: httpx.AsyncBaseTransport | None = None):
        self.base_url = (base_url or os.getenv("FUND_API_BASE_URL", "http://127.0.0.1:8080")).rstrip("/")
        self.transport = transport

    async def user_id(self, authorization: str | None) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise FundApiError("请先登录，或重新登录后再试", 401)
        try:
            async with httpx.AsyncClient(
                base_url=self.base_url, transport=self.transport, timeout=10.0, trust_env=False
            ) as client:
                response = await client.get("/api/v1/users/me", headers={"Authorization": authorization})
                response.raise_for_status()
                payload = response.json()
        except httpx.HTTPStatusError as error:
            if error.response.status_code in (401, 403):
                raise FundApiError("请先登录，或重新登录后再试", 401) from error
            raise FundApiError("无法验证登录状态，请检查 Java 后端") from error
        except (httpx.RequestError, ValueError) as error:
            raise FundApiError("无法验证登录状态，请检查 Java 后端") from error
        data = payload.get("data") if isinstance(payload, dict) and payload.get("code") == "SUCCESS" else None
        user_id = data.get("userId") if isinstance(data, dict) else None
        if isinstance(user_id, dict):
            user_id = user_id.get("value")
        if not isinstance(user_id, str) or not user_id:
            raise FundApiError("登录信息格式不正确")
        return user_id


class FundClient:
    METRICS = ("cumulativeReturn", "annualizedReturn", "annualizedVolatility", "maxDrawdown", "sharpeRatio")

    def __init__(self, base_url: str | None = None, transport: httpx.AsyncBaseTransport | None = None):
        self.base_url = (base_url or os.getenv("FUND_API_BASE_URL", "http://127.0.0.1:8080")).rstrip("/")
        self.transport = transport

    async def compare(self, request: CompareRequest, authorization: str | None = None) -> ComparisonSnapshot:
        body: dict[str, Any] = {
            "fundCodes": request.fund_codes,
            "startDate": request.start_date.isoformat(),
            "endDate": request.end_date.isoformat(),
        }
        if request.nav_basis:
            body["navBasis"] = request.nav_basis

        try:
            # 本地 Java 服务不能经过系统 HTTP 代理；与 main.py 的净值请求保持一致。
            async with httpx.AsyncClient(
                base_url=self.base_url, transport=self.transport, timeout=15.0, trust_env=False
            ) as client:
                # Java 的比较接口要求登录；只转发本次请求的 Bearer 凭证。
                headers = {"Authorization": authorization} if authorization else {}
                response = await client.post("/api/v1/fund-comparisons", json=body, headers=headers)
                response.raise_for_status()
                payload = response.json()
        except httpx.HTTPStatusError as error:
            # 上游数据不足等业务错误可供调用者修正；不要包装成“模型失败”。
            if error.response.status_code == 401:
                raise FundApiError("请先登录，或重新登录后再试", 401) from error
            if error.response.status_code == 403:
                raise FundApiError("当前账号没有权限比较基金", 403) from error
            if error.response.status_code == 502:
                try:
                    upstream = error.response.json()
                except ValueError:
                    upstream = None
                if isinstance(upstream, dict) and upstream.get("code") == "DATA_QUALITY_ERROR":
                    raise FundApiError("基金数据源返回无效数据，请更换基金或日期范围", 502) from error
            status = 422 if error.response.status_code in (400, 404, 409, 422) else 502
            raise FundApiError(f"基金服务返回 HTTP {error.response.status_code}", status) from error
        except (httpx.RequestError, ValueError) as error:
            raise FundApiError("无法读取基金服务，请检查 Java 后端和返回数据") from error

        if not isinstance(payload, dict) or payload.get("code") != "SUCCESS" or not isinstance(payload.get("data"), dict):
            raise FundApiError("基金服务未返回有效的比较结果")
        return self._snapshot(payload["data"])

    def _snapshot(self, data: dict[str, Any]) -> ComparisonSnapshot:
        try:
            start = date.fromisoformat(data["commonStartDate"])
            end = date.fromisoformat(data["commonEndDate"])
            funds = []
            for item in data["funds"]:
                # Java 的 FundCode 在 HTTP JSON 中是六码字符串，不是 {"value": ...}。
                code = item["fundCode"]
                metrics = {}
                for name in self.METRICS:
                    metric = item[name]
                    metrics[name] = {
                        "status": metric["status"],
                        "value": metric.get("value"),
                        "unavailableReason": metric.get("unavailableReason"),
                        # 证据 ID 绑定基金、区间、口径及数据/算法版本。
                        "evidenceId": f"{code}:{name}:{start}:{end}:{data['navBasis']}:{item['dataVersion']}:{item['algorithmVersion']}",
                    }
                funds.append({
                    "fundCode": code,
                    "observationCount": item["observationCount"],
                    "coverageStatus": item["coverage"]["status"],
                    "dataVersion": item["dataVersion"],
                    "algorithmVersion": item["algorithmVersion"],
                    "metrics": metrics,
                })
            if len(funds) != 2:
                raise ValueError("comparison must contain exactly two funds")
            return ComparisonSnapshot.model_validate({
                "commonStartDate": start,
                "commonEndDate": end,
                "navBasis": data["navBasis"],
                "funds": funds,
            })
        except (KeyError, TypeError, ValueError) as error:
            raise FundApiError("基金服务的比较结果字段不完整") from error
