"""模型只能调用这里登记的业务工具，不能自行构造 URL 或用户身份。"""

import json
import re
from datetime import date, timedelta
from uuid import UUID

import httpx
from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field, model_validator

from client import FundApiError, IdentityClient
from agent.market import CatalystArguments, MarketArguments, MarketData, SectorArguments
from agent.tavily import TavilyMcp, TavilySearchArguments, TavilyExtractArguments


class FundArguments(BaseModel):
    fundCode: str = Field(pattern=r"^\d{6}$", description="六位基金代码")
    startDate: date | None = None
    endDate: date | None = None

    @model_validator(mode="after")
    def dates_valid(self):
        if self.startDate and self.endDate and self.startDate > self.endDate:
            raise ValueError("开始日期不能晚于结束日期")
        if self.endDate and self.endDate > date.today():
            raise ValueError("结束日期不能在未来")
        return self


class CompareArguments(BaseModel):
    fundCodes: list[str] = Field(min_length=2, max_length=10)
    startDate: date
    endDate: date

    @model_validator(mode="after")
    def valid(self):
        if any(not re.fullmatch(r"\d{6}", code) for code in self.fundCodes):
            raise ValueError("基金代码必须为六位数字")
        if len(set(self.fundCodes)) != len(self.fundCodes):
            raise ValueError("不能重复比较同一基金")
        if self.startDate > self.endDate or self.endDate > date.today():
            raise ValueError("比较日期范围无效")
        return self


class PortfolioArguments(BaseModel):
    portfolioId: UUID | None = None
    asOfDate: date | None = None


class EmptyArguments(BaseModel):
    pass


class SearchArguments(BaseModel):
    query: str = Field(min_length=1, max_length=80)


class BusinessTools:
    def __init__(self, authorization: str, identity: IdentityClient, scope: dict | None = None):
        self.authorization = authorization
        self.identity = identity
        self.scope = scope
        self.registry: dict[str, StructuredTool] = {}
        self._register()
        market = MarketData()
        for name, description, schema, handler in [
            ("fund_realtime_quote", "读取基金关联 ETF 的场内报价，不是基金净值；必须说明时间与过期状态", MarketArguments, market.realtime),
            ("analyze_sector_outlook", "计算指定场内 ETF 的历史趋势与波动；不预测未来涨跌", SectorArguments, market.sector),
            ("research_fund_catalysts", "从最近披露持仓检索公告元数据，计算涉及持仓权重；不是全文核验或涨跌因果证明", CatalystArguments, market.catalysts),
        ]:
            self.registry[name] = StructuredTool.from_function(
                name=name, description=description, args_schema=schema, coroutine=handler)
        self.tavily = TavilyMcp()
        if self.tavily.configured and not scope:
            # 只为普通研究开放联网检索；月报继续使用服务器固定的历史基金范围。
            for name, description, schema, handler in [
                ("tavily_search", "通过 Tavily MCP 搜索公开财经新闻、政策和公告线索，返回摘要与来源；不替代净值或收益计算", TavilySearchArguments, self.tavily.search),
                ("tavily_extract", "通过 Tavily MCP 提取公开网页正文片段，用于核对搜索摘要；不能读取知识库或个人数据", TavilyExtractArguments, self.tavily.extract),
            ]:
                self.registry[name] = StructuredTool.from_function(
                    name=name, description=description, args_schema=schema, coroutine=handler)

    async def request(self, path: str, params: dict | None = None, body: dict | None = None):
        try:
            async with httpx.AsyncClient(base_url=self.identity.base_url,
                                         transport=self.identity.transport,
                                         trust_env=False, timeout=25) as client:
                headers = {"Authorization": self.authorization}
                response = await client.request("POST" if body else "GET", path,
                                                params=params, json=body, headers=headers)
                response.raise_for_status()
                payload = response.json()
            if payload.get("code") != "SUCCESS":
                raise FundApiError("业务服务没有返回成功数据")
            return payload["data"]
        except (httpx.HTTPError, ValueError, KeyError) as error:
            raise FundApiError("业务数据读取失败，请确认 Java 服务与基金数据可用") from error

    def _register(self):
        def add(name, description, schema, handler):
            self.registry[name] = StructuredTool.from_function(
                name=name, description=description, args_schema=schema, coroutine=handler)

        async def search(query):
            return await self.request("/api/v1/funds", {"q": query})

        async def watchlist():
            return await self.request("/api/v1/watchlists")

        async def portfolios():
            return await self.request("/api/v1/portfolios")

        async def compare(**arguments):
            request = CompareArguments.model_validate(arguments)
            return await self.request("/api/v1/fund-comparisons", body=request.model_dump(mode="json"))

        add("search_funds", "按基金名称搜索基金；不知道基金代码时先使用此工具", SearchArguments, search)
        add("get_my_watchlist", "读取当前登录用户的自选分组及基金", EmptyArguments, watchlist)
        add("list_my_portfolios", "读取当前登录用户的组合列表", EmptyArguments, portfolios)
        add("compare_fund_metrics", "用统一日期范围比较两到十只基金收益与风险", CompareArguments, compare)

        for name, suffix, description in [
            ("get_fund_profile", "", "读取基金基本资料"),
            ("get_fund_nav_history", "/nav", "读取基金历史净值，可指定开始和结束日期"),
            ("calculate_fund_metrics", "/metrics", "读取收益、回撤、波动等指标，必须依据实际数据回答"),
        ]:
            # 循环里的默认参数固定本次 suffix，避免所有闭包最后都请求 metrics。
            async def fund_handler(fundCode, startDate=None, endDate=None, _suffix=suffix):
                if _suffix:
                    endDate = endDate or date.today()
                    startDate = startDate or (date.fromisoformat(str(endDate)) - timedelta(days=365))
                params = {key: str(value) for key, value in
                          {"startDate": startDate, "endDate": endDate}.items() if value}
                return await self.request(f"/api/v1/funds/{fundCode}{_suffix}", params)
            add(name, description, FundArguments, fund_handler)

        for name, suffix, description in [
            ("get_my_portfolio", "/positions", "读取自己组合的基金持仓"),
            ("get_my_portfolio_valuation", "/valuation", "读取自己组合的估值与数据覆盖情况"),
            ("calculate_my_return", "/returns", "读取自己组合的成本、收益与 XIRR"),
            ("analyze_my_portfolio_risk", "/risk", "读取自己组合的风险与覆盖情况"),
        ]:
            async def portfolio_handler(portfolioId=None, asOfDate=None, _suffix=suffix):
                portfolios = await self.request("/api/v1/portfolios")
                owned = [str(item["portfolioId"].get("value")) if isinstance(item["portfolioId"], dict)
                         else str(item["portfolioId"]) for item in portfolios]
                chosen = str(portfolioId) if portfolioId else (owned[0] if owned else None)
                if chosen not in owned:
                    raise FundApiError("当前用户没有这个组合，或尚未创建组合", 404)
                params = {"asOfDate": str(asOfDate)} if asOfDate and _suffix != "/positions" else {}
                return await self.request(f"/api/v1/portfolios/{chosen}{_suffix}", params)
            add(name, description, PortfolioArguments, portfolio_handler)

    async def call(self, name: str, arguments: dict):
        if name not in self.registry:
            raise FundApiError("模型请求了未登记的工具")
        if self.scope:
            # 月报范围由服务端固定；不能只在提示词里“请求模型遵守”。
            arguments = dict(arguments)
            codes = self.scope["fundCodes"]
            if name in {"fund_realtime_quote", "analyze_sector_outlook", "research_fund_catalysts", "search_funds"}:
                raise FundApiError("月报使用已确认的基金和历史区间，不读取本期实时市场数据")
            if "fundCode" in arguments and arguments["fundCode"] not in codes:
                raise FundApiError("这只基金不在月报范围内")
            if "fundCodes" in arguments and not set(arguments["fundCodes"]).issubset(codes):
                raise FundApiError("比较包含月报范围以外的基金")
            if name in {"get_fund_nav_history", "calculate_fund_metrics", "compare_fund_metrics"}:
                arguments.update(startDate=self.scope["periodStart"], endDate=self.scope["periodEnd"])
            if "portfolio" in name or name == "calculate_my_return":
                if self.scope["scopeKind"] != "PORTFOLIO":
                    raise FundApiError("本次月报只研究所选自选分组")
                if name != "list_my_portfolios":
                    arguments.update(portfolioId=self.scope["scopeId"], asOfDate=self.scope["periodEnd"])
            if name == "get_my_watchlist" and self.scope["scopeKind"] != "WATCHLIST":
                raise FundApiError("本次月报只研究所选组合")
        data = await self.registry[name].ainvoke(arguments)
        if self.scope and name in {"get_my_watchlist", "list_my_portfolios"}:
            field = "groupId" if name == "get_my_watchlist" else "portfolioId"
            data = [item for item in data if (item[field].get("value") if isinstance(item[field], dict)
                                            else item[field]) == self.scope["scopeId"]]
        # 数据不做无声截断，避免模型看到半份组合或不完整的指标。
        if len(json.dumps(data, ensure_ascii=False)) > 24_000:
            raise FundApiError("数据过多，请缩小日期范围或基金数量")
        return data
