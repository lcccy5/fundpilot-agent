"""ETF 申赎清单穿透：清单数量 × 场内报价估算股票篮子权重。"""

import asyncio
import math
import re
from datetime import date, timedelta


def parse_szse(text):
    if "组合信息内容" not in text:
        return []
    rows = re.findall(r"(?m)^\s*(\d{6})\s+(.+?)\s{2,}([\d,]+)\s+", text.split("组合信息内容", 1)[1])
    return [{"stockCode": code, "stockName": name.strip(), "quantity": int(quantity.replace(",", ""))}
            for code, name, quantity in rows if code != "159900" and int(quantity.replace(",", "")) > 0]


async def components(market, etf):
    if etf.startswith("5"):
        payload = (await market.get("https://query.sse.com.cn/commonQuery.do", {
            "isPagination": "false", "FUNDID2": etf,
            "sqlId": "COMMON_SSE_CP_JJLB_ETFJJGK_GGSGSHQD_COMPONENT_C",
        }, referer=f"https://www.sse.com.cn/disclosure/fund/etflist/detail.shtml?fundid={etf}")).json()
        rows = [{"stockCode": row["INSTRUMENT_ID"], "stockName": row.get("INSTRUMENT_NAME", row["INSTRUMENT_ID"]),
                 "quantity": int(float(row["QUANTITY"]))}
                for row in payload.get("result", []) if re.fullmatch(r"\d{6}", str(row.get("INSTRUMENT_ID", "")))
                and float(row.get("QUANTITY", 0)) > 0]
        # 该接口没有稳定的清单日期字段，不把请求日期冒充清单生效日期。
        return rows, None, "上交所公开申赎清单"
    for offset in range(8):
        target = date.today() - timedelta(days=offset)
        try:
            response = await market.get(f"https://reportdocs.static.szse.cn/files/text/etf/ETF{etf}{target:%Y%m%d}.txt",
                                        encoding="gbk", referer="https://www.szse.cn/")
            rows = parse_szse(response.text)
            if rows:
                return rows, str(target), "深交所公开申赎清单"
        except Exception:
            continue
    raise ValueError("最近八天没有可用申赎清单")


async def prices(market, rows):
    async def batch(chunk):
        response = await market.get("https://qt.gtimg.cn/",
                                     {"q": ",".join(market.symbol(row["stockCode"]) for row in chunk)}, "gbk")
        values = {}
        for code, text in re.findall(r'v_(?:sh|sz)(\d{6})="([^\"]*)"', response.text):
            fields = text.split("~")
            try:
                price = float(fields[3])
                if price > 0 and math.isfinite(price):
                    values[code] = {"price": price, "quoteTime": fields[30]}
            except (ValueError, IndexError):
                continue
        return values
    # 分批请求，避免把几百只股票拼进一个过长 URL。
    results = await asyncio.gather(*(batch(rows[index:index + 100]) for index in range(0, len(rows), 100)))
    return {code: value for result in results for code, value in result.items()}


async def portfolio(market, fund_code, etf, top_n):
    rows, effective, source = await components(market, etf)
    if not rows or len(rows) > 1000:
        raise ValueError("申赎清单数量无效")
    quotes = await prices(market, rows)
    valued = [{**row, "marketValue": row["quantity"] * quotes[row["stockCode"]]["price"]}
              for row in rows if row["stockCode"] in quotes]
    if len(valued) < max(min(8, len(rows)), math.ceil(len(rows) * 0.7)):
        raise ValueError("申赎清单行情覆盖不足")
    total = sum(row["marketValue"] for row in valued)
    if total <= 0:
        raise ValueError("申赎清单估值无效")
    ordered = sorted(valued, key=lambda row: row["marketValue"], reverse=True)[:top_n]
    holdings = [{"stockCode": row["stockCode"], "stockName": row["stockName"],
                 "weightPercent": row["marketValue"] / total * 100} for row in ordered]
    return {"fundCode": fund_code, "proxyEtfCode": etf, "holdingsAsOf": effective,
            "exposureBasis": "DAILY_PCF_ETF_BASKET" if etf == fund_code else "DAILY_PCF_UNDERLYING_ETF_PROXY",
            "holdings": holdings, "coveragePercent": sum(row["weightPercent"] for row in holdings),
            "quoteCoverageRatio": len(valued) / len(rows), "dataSource": source,
            "quoteTimes": sorted({quote["quoteTime"] for quote in quotes.values()}),
            "limitations": ["申赎清单与行情估算代表股票篮子，不是基金会计持仓；未计现金与无法估值部分",
                             "联接基金采用关联 ETF 代理，不能当作联接基金自身持仓",
                             "holdingsAsOf 为空表示源接口未提供可确认的清单日期"]}
