from datetime import date, timedelta
import os
from pathlib import Path

import httpx

def get_latest_nav(fund_code: str, end_date: date | None = None):
    """读取截至指定日期的最新净值，默认查询最近一年的数据。"""
    end = end_date or date.today()
    base_url = os.getenv("FUND_API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    url = f"{base_url}/api/v1/funds/{fund_code}/nav"
    params = {
        "startDate": (end - timedelta(days=365)).isoformat(),
        "endDate": end.isoformat(),
    }
    response = httpx.get(url, params=params, timeout=10, trust_env=False)
    response.raise_for_status()

    result = response.json()
    if result["code"] != "SUCCESS":
        raise RuntimeError(result["message"])

    items = result["data"]["items"]
    if not items:
        raise RuntimeError(f"基金{fund_code}：该日期范围没有净值数据")

    latest = max(items, key=lambda item: item["navDate"])
    return {**latest, "fundCode": result["data"]["fundCode"]}


if __name__ == "__main__":
    # IDE 直接运行 main.py 时启动 HTTP 服务。查询净值是在收到请求后才进行的，
    # 因此 Java 后端暂未启动也不会让 Python 服务在启动阶段崩溃。
    try:
        import uvicorn
        import app  # noqa: F401 先检查服务依赖，错误提示比 Uvicorn 导入异常更直观。
    except ModuleNotFoundError as error:
        interpreter = Path(__file__).resolve().parent / ".venv" / "Scripts" / "python.exe"
        raise SystemExit(
            f"当前 Python 缺少依赖 {error.name}。请先在 python-agent 目录执行 uv sync，"
            f"然后把 IDE 解释器设为 {interpreter}。"
        ) from error

    uvicorn.run("app:app", host="127.0.0.1", port=8001)
