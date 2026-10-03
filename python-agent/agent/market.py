"""公开市场数据适配器；地址固定，数据失败时保留缺失状态。"""

import asyncio
import html
import math
import re
import statistics
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

import httpx
from pydantic import BaseModel, Field
from agent.industries import industry_for
from agent import pcf


class MarketArguments(BaseModel):
    fundCode: str = Field(pattern=r"^\d{6}$")


class CatalystArguments(MarketArguments):
    topN: int = Field(default=5, ge=1, le=8)
    days: int = Field(default=30, ge=1, le=90)


class SectorArguments(BaseModel):
    etfCode: str = Field(pattern=r"^(?:159\d{3}|5\d{5})$",
                         description="板块的场内 ETF 代码；先搜索确定，不能随意猜测")


class MarketData:
    def __init__(self, transport=None):
        self.transport = transport

    async def get(self, url, params=None, encoding=None, referer=None):
        # 市场请求不携带业务登录令牌。URL 仅由下面的固定适配器给出。
        async with httpx.AsyncClient(transport=self.transport, timeout=8, trust_env=False,
                                     headers={"User-Agent": "Mozilla/5.0 FundPilot/1.0",
                                              "Referer": "https://fund.eastmoney.com/"}) as client:
            response = await client.get(url, params=params,
                                        headers={"Referer": referer} if referer else None)
            response.raise_for_status()
            if encoding:
                response.encoding = encoding
            return response

    @staticmethod
    def symbol(code):
        return ("sh" if code.startswith(("5", "6")) else "sz") + code

    async def resolve_etf(self, fundCode):
        etf = fundCode if re.fullmatch(r"(?:159\d{3}|5\d{5})", fundCode) else None
        if etf is None:
            payload = (await self.get("https://fundmobapi.eastmoney.com/FundMNewApi/FundMNInverstPosition",
                                     {"FCODE": fundCode, "deviceid": "Wap", "plat": "Wap",
                                      "product": "EFund", "version": "2.0.0"})).json()
            etf = (payload.get("Datas") or {}).get("ETFCODE")
        return etf if isinstance(etf, str) and re.fullmatch(r"(?:159\d{3}|5\d{5})", etf) else None

    async def realtime(self, fundCode):
        etf = await self.resolve_etf(fundCode)
        if not etf:
            return {"status": "NO_EXCHANGE_PROXY", "fundCode": fundCode,
                    "limitations": ["未找到可靠的场内 ETF 代理，不能提供实时净值"]}
        response = await self.get("https://qt.gtimg.cn/", {"q": self.symbol(etf)}, "gbk")
        match = re.search(r'="([^\"]+)"', response.text)
        if not match:
            raise ValueError("行情格式不正确")
        fields = match[1].split("~")
        quote_time = datetime.strptime(fields[30], "%Y%m%d%H%M%S").replace(tzinfo=ZoneInfo("Asia/Shanghai"))
        price = float(fields[3])
        if price <= 0 or not math.isfinite(price):
            raise ValueError("行情价格无效")
        age = datetime.now(ZoneInfo("Asia/Shanghai")) - quote_time
        return {"status": "STALE" if age > timedelta(minutes=20) else "AVAILABLE",
                "fundCode": fundCode, "proxyEtfCode": etf, "price": price,
                "changePercent": float(fields[32]), "quoteTime": quote_time.isoformat(),
                "dataSource": "腾讯行情", "sourceUri": "https://qt.gtimg.cn/",
                "limitations": ["这是关联 ETF 的场内交易价，不是基金净值；收盘后报价会变旧"]}

    @staticmethod
    def sector_metrics(rows):
        # 算法留在 Python，模型只解释结果，不负责数值计算。
        closes = [float(row[2]) for row in rows]
        if len(closes) < 61 or any(price <= 0 or not math.isfinite(price) for price in closes):
            raise ValueError("需要至少 61 个有效交易日")
        returns = [math.log(current / previous) for previous, current in zip(closes, closes[1:])]
        peak = closes[0]
        drawdown = 0.0
        for close in closes:
            peak = max(peak, close)
            drawdown = min(drawdown, close / peak - 1)
        return {"return20dPercent": (closes[-1] / closes[-21] - 1) * 100,
                "return60dPercent": (closes[-1] / closes[-61] - 1) * 100,
                "movingAverage20d": statistics.mean(closes[-20:]),
                "annualizedVolatilityPercent": statistics.stdev(returns) * math.sqrt(252) * 100,
                "maxDrawdownPercent": drawdown * 100,
                "lastClose": closes[-1], "observationCount": len(rows)}

    async def sector(self, etfCode):
        symbol = self.symbol(etfCode)
        payload = (await self.get("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
                                  {"param": f"{symbol},day,,,90,qfq"})).json()
        data = payload["data"][symbol]
        rows = data.get("qfqday") or data.get("day") or []
        metrics = self.sector_metrics(rows)
        return {"etfCode": etfCode, "dataSource": "腾讯日线", "asOfDate": rows[-1][0],
                "priceBasis": "qfqday" if data.get("qfqday") else "day", "metrics": metrics,
                "limitations": ["仅代表所选 ETF，不代表板块全部公司；历史趋势不能保证未来涨跌"]}

    async def holdings(self, fundCode, topN):
        response = await self.get("https://fundf10.eastmoney.com/FundArchivesDatas.aspx",
                                  {"type": "jjcc", "code": fundCode, "topline": topN,
                                   "year": "", "month": "", "rt": "0.1"})
        # 接口是 JavaScript 包裹的 HTML，不执行它；只解析日期和持仓表格。
        body = response.text.replace('\\"', '"')
        disclosed = re.search(r'截止至：<font[^>]*>(\d{4}-\d{2}-\d{2})</font>', body)
        if not disclosed:
            raise ValueError("持仓披露日期缺失")
        rows = re.findall(r"<tr><td>\d+</td><td><a[^>]*>(\d{6})</a></td><td class='tol'><a[^>]*>([^<]+)</a></td>.*?<td class='xglj'>.*?</td><td class='tor'>([\d.]+)%</td>", body, re.S)
        positions = [{"stockCode": code, "stockName": html.unescape(name), "weightPercent": float(weight)}
                     for code, name, weight in rows[:topN]]
        if not positions:
            raise ValueError("没有可解析的股票持仓")
        return {"fundCode": fundCode, "holdingsAsOf": disclosed[1], "holdings": positions,
                "exposureBasis": "DISCLOSED_REPORT_HOLDINGS",
                "coveragePercent": sum(position["weightPercent"] for position in positions),
                "dataSource": "东方财富基金定期披露", "sourceUri": f"https://fundf10.eastmoney.com/ccmx_{fundCode}.html"}

    async def announcements(self, holding, days):
        try:
            payload = (await self.get("https://np-anotice-stock.eastmoney.com/api/security/ann",
                                      {"sr": -1, "page_size": 15, "page_index": 1, "ann_type": "A",
                                       "client_source": "web", "stock_list": holding["stockCode"]})).json()
            events = []
            cutoff = date.today() - timedelta(days=days)
            for item in payload["data"]["list"]:
                published = date.fromisoformat(item["notice_date"][:10])
                if not cutoff <= published <= date.today():
                    continue
                title = item["title"]
                code = item["art_code"]
                if not re.fullmatch(r"[A-Za-z0-9]+", code):
                    continue
                # 只标记标题关键词，不把公告标题当作已核验的业绩结论。
                direction = "NEGATIVE" if any(word in title for word in ("预亏", "预减", "减持", "立案", "处罚", "退市", "风险提示")) else "POSITIVE" if any(word in title for word in ("预增", "扭亏", "增持", "回购", "中标", "获批", "分红")) else "NEUTRAL"
                events.append({"eventId": code, "stockCode": holding["stockCode"], "title": title,
                               "publishedDate": str(published), "direction": direction,
                               "sourceUri": f"https://data.eastmoney.com/notices/detail/{holding['stockCode']}/{code}.html"})
            return {"stockCode": holding["stockCode"], "status": "AVAILABLE", "events": events}
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            return {"stockCode": holding["stockCode"], "status": "UNAVAILABLE", "events": []}

    async def catalysts(self, fundCode, topN=5, days=30):
        try:
            etf = await self.resolve_etf(fundCode)
            if not etf:
                raise ValueError("没有关联 ETF")
            portfolio = await pcf.portfolio(self, fundCode, etf, topN)
        except Exception:
            # 日频穿透不可用时显式退回最近披露持仓，不能仍标成实时持仓。
            portfolio = await self.holdings(fundCode, topN)
            portfolio["limitations"] = ["每日 ETF 申赎清单不可用，采用最近定期披露，可能与当前持仓不同"]
        results = await asyncio.gather(*(self.announcements(position, days) for position in portfolio["holdings"]))
        industries = await asyncio.gather(*(industry_for(self, position) for position in portfolio["holdings"]))
        events = {event["eventId"]: event for result in results for event in result["events"]}
        weights = {}
        for direction in ("POSITIVE", "NEGATIVE"):
            affected = {event["stockCode"] for event in events.values() if event["direction"] == direction}
            weights[direction] = sum(item["weightPercent"] for item in portfolio["holdings"] if item["stockCode"] in affected)
        return {**portfolio, "events": list(events.values()), "eventCoverage": results, "industryExposure": industries,
                "affectedWeightPercent": weights,
                "limitations": [*portfolio.get("limitations", []),
                                 "事件来自公告聚合元数据，未阅读全文；标题分类只供筛选，不能证明涨跌因果",
                                 "受影响权重按股票去重累计，正负方向可能同时覆盖同一股票",
                                 "检索失败标记 UNAVAILABLE，不能解释为没有公告"]}
