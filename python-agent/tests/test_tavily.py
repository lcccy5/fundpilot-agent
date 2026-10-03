"""用真实 MCP SDK 跑协议交互，不依赖 Tavily 账号或外部网络。"""

import json

import httpx
import pytest
from mcp.types import CallToolResult, TextContent

from agent.tavily import TavilyMcp, TavilyError, TavilyExtractArguments
from agent.tools import BusinessTools
from client import IdentityClient
from agent.graph import AgentGraph
from langchain_core.messages import AIMessage, ToolMessage


def protocol_transport(requests, error=False, omit_tool=False):
    def respond(request):
        assert request.headers.get("Authorization") == "Bearer test-tavily-key"
        if request.method in {"GET", "DELETE"}:
            return httpx.Response(405)
        message = json.loads(request.content)
        requests.append(message)
        if "id" not in message:
            return httpx.Response(202)
        method = message["method"]
        if method == "initialize":
            result = {"protocolVersion": message["params"]["protocolVersion"], "capabilities": {"tools": {}},
                      "serverInfo": {"name": "test-tavily", "version": "1.0"}}
        elif method == "tools/list":
            result = {"tools": [] if omit_tool else [
                {"name": "tavily_search", "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}},
                {"name": "tavily_extract", "inputSchema": {"type": "object", "properties": {"urls": {"type": "array", "items": {"type": "string"}}}, "required": ["urls"]}},
            ]}
        elif method == "tools/call":
            payload = {"results": [{"url": "https://www.csrc.gov.cn/example", "title": "公开政策资料",
                                    "content": "公开摘要", "published_date": "2026-09-29"}]}
            result = {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}], "isError": error}
        else:
            pytest.fail(f"未预期的 MCP 方法 {method}")
        return httpx.Response(200, headers={"Content-Type": "application/json"},
                              json={"jsonrpc": "2.0", "id": message["id"], "result": result})
    return httpx.MockTransport(respond)


@pytest.mark.asyncio
async def test_real_mcp_discovery_call_and_sources():
    requests = []
    client = TavilyMcp("test-tavily-key", transport=protocol_transport(requests))
    data = await client.search("公募基金政策", "week")
    methods = [message["method"] for message in requests]
    assert "initialize" in methods and "tools/list" in methods and "tools/call" in methods
    call = next(message for message in requests if message["method"] == "tools/call")
    assert call["params"]["arguments"]["max_results"] == 5
    assert call["params"]["arguments"]["time_range"] == "week"
    assert data["dataSource"] == "Tavily MCP"
    assert data["sources"][0]["publishedDate"] == "2026-09-29"
    assert "test-tavily-key" not in json.dumps(data)


@pytest.mark.asyncio
@pytest.mark.parametrize("error,omit_tool", [(True, False), (False, True)])
async def test_protocol_failures_do_not_leak_key(error, omit_tool):
    client = TavilyMcp("test-tavily-key", transport=protocol_transport([], error, omit_tool))
    with pytest.raises(TavilyError) as caught:
        await client.search("公开资料")
    assert "test-tavily-key" not in str(caught.value)


def test_formatted_result_excerpts_dates_and_redaction():
    client = TavilyMcp("test-tavily-key")
    result = CallToolResult(content=[TextContent(type="text", text=(
        "Detailed Results:\n\nTitle: 标题\nURL: https://example.com/article\nContent: " + "正文" * 1200 + " test-tavily-key"
    ))])
    data = client.normalize("tavily_search", result)
    assert data["sources"][0]["truncated"]
    assert data["sources"][0]["publishedDate"] is None
    assert len(data["sources"][0]["content"]) == 1800
    assert "test-tavily-key" not in json.dumps(data)
    structured = CallToolResult(content=[], structuredContent={"results": [
        {"url": "https://example.com", "content": "test-tavily-key"}]})
    assert "test-tavily-key" not in json.dumps(client.normalize("tavily_search", structured))


def test_unrecognized_response_is_not_no_results():
    client = TavilyMcp("test-tavily-key")
    with pytest.raises(TavilyError):
        client.normalize("tavily_search", CallToolResult(content=[TextContent(type="text", text="Permission denied")]))


@pytest.mark.parametrize("url", ["http://127.0.0.1/api", "http://localhost", "file:///secret", "http://user:pass@example.com"])
def test_extract_rejects_nonpublic_addresses(url):
    with pytest.raises(ValueError):
        TavilyExtractArguments(urls=[url])


def test_unconfigured_and_monthly_tools_do_not_advertise_tavily(monkeypatch):
    monkeypatch.setattr("agent.tavily.load_local_environment", lambda: None)
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    monkeypatch.setenv("TAVILY_ENABLED", "true")
    assert "tavily_search" not in BusinessTools("Bearer java", IdentityClient()).registry
    monkeypatch.setenv("TAVILY_API_KEY", "test-tavily-key")
    assert "tavily_search" in BusinessTools("Bearer java", IdentityClient()).registry
    scope = {"fundCodes": ["000001"], "scopeKind": "WATCHLIST", "scopeId": "g1"}
    assert "tavily_search" not in BusinessTools("Bearer java", IdentityClient(), scope).registry


@pytest.mark.asyncio
async def test_langgraph_receives_mcp_data_and_preserves_sources(monkeypatch):
    client = TavilyMcp("test-tavily-key", transport=protocol_transport([]))
    monkeypatch.setattr("agent.tools.TavilyMcp", lambda: client)
    class Model:
        def __init__(self, analyst=False):
            self.analyst = analyst
        def bind_tools(self, tools):
            assert "tavily_search" in {tool.name for tool in tools}
            return self
        async def ainvoke(self, messages):
            if self.analyst and not any(isinstance(message, ToolMessage) for message in messages):
                return AIMessage(content="", tool_calls=[{"name": "tavily_search", "args": {"query": "公募基金政策"}, "id": "search1", "type": "tool_call"}])
            return AIMessage(content="搜索取得公开政策资料，摘要不能证明完整政策内容。[E01]")
    models = {"bull": Model(True), "bear": Model(), "judge": Model()}
    events = []
    graph = AgentGraph(BusinessTools("Bearer java-user-token", IdentityClient()),
                       lambda kind, data: events.append((kind, data)), models=models)
    state = await graph.run("搜索公募基金政策", [])
    evidence = state["evidence"][0]
    assert evidence["evidenceType"] == "WEB_SEARCH"
    assert evidence["sources"][0]["url"] == "https://www.csrc.gov.cn/example"
    assert evidence["dataSource"] == "Tavily MCP"
    assert state["calls"] == 1
    assert "test-tavily-key" not in json.dumps(evidence, ensure_ascii=False)
    assert any(kind == "tool.completed" and data["toolName"] == "tavily_search" for kind, data in events)
