"""配置密钥后，验证一次真实 Tavily MCP 搜索；不输出密钥或完整网页内容。"""

import asyncio
from pathlib import Path
import sys

# 从 scripts 目录直接运行时，让 Python 找到上一级的 agent 包。
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from agent.tavily import TavilyMcp, TavilyError


async def main():
    client = TavilyMcp()
    if not client.configured:
        print("Tavily 未启用：请在 python-agent/.env 填写 TAVILY_API_KEY，并确认 TAVILY_ENABLED=true。")
        return 2
    try:
        data = await client.search("中国证监会 公募基金 政策")
    except TavilyError as error:
        print(str(error))
        return 1
    print(f"MCP 初始化、工具发现和调用成功；状态 {data['status']}，取得 {len(data['sources'])} 个网页来源。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
