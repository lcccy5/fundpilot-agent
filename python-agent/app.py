"""基金研究 Agent 的 HTTP 入口。"""

import json
import os
from contextlib import asynccontextmanager
from datetime import date
from decimal import Decimal
from uuid import uuid4

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from client import FundApiError, FundClient, IdentityClient
from main import get_latest_nav
from models import CompareRequest, ResearchResult
from research import ModelError, ModelUnavailable, OpenAIResearchModel, ResearchWorkflow
from store import RunStore
from agent.api import routes
from agent.repository import AgentRepository
from agent.runtime import AgentRuntime
from agent.events import EventService
from agent.config import load_local_environment


class FundNav(BaseModel):
    fundCode: str
    navDate: date
    unitNav: Decimal


def create_app(
    workflow: ResearchWorkflow | None = None,
    store: RunStore | None = None,
    identity: IdentityClient | None = None,
) -> FastAPI:
    load_local_environment()
    run_store = store or RunStore()
    login = identity or IdentityClient()
    repository = AgentRepository(run_store.db_path)
    runtime = AgentRuntime(repository, login)

    @asynccontextmanager
    async def lifespan(app):
        # 当前使用单进程运行：重启后显式标记中断任务，不能把它们永久显示为运行中。
        repository.recover()
        yield
        await runtime.shutdown()

    app = FastAPI(title="FundPilot Research Agent", version="0.2.0", lifespan=lifespan)
    origins = [value.strip() for value in os.getenv("AGENT_CORS_ORIGINS", "").split(",") if value.strip()]
    if origins:
        app.add_middleware(CORSMiddleware, allow_origins=origins,
                           allow_methods=["GET", "POST"], allow_headers=["Authorization", "Content-Type", "Last-Event-ID"],
                           allow_credentials=True)
    app.state.workflow = workflow or ResearchWorkflow(FundClient(), OpenAIResearchModel())
    app.state.store = run_store
    app.state.identity = login
    app.state.agent_runtime = runtime
    app.state.agent_repository = repository
    app.include_router(routes(repository, runtime, login))
    app.state.event_service = EventService(repository)
    app.include_router(app.state.event_service.router())

    @app.get("/api/nav/{fund_code}", response_model=FundNav)
    def latest_nav(fund_code: str) -> FundNav:
        # 保留已有的练习接口，研究图使用独立的异步比较客户端。
        return FundNav.model_validate(get_latest_nav(fund_code))

    @app.post("/api/research/compare", response_model=ResearchResult)
    async def compare_funds(
        request: CompareRequest, authorization: str | None = Header(default=None)
    ) -> ResearchResult:
        try:
            owner_id = await app.state.identity.user_id(authorization)
            result = await app.state.workflow.run(request, authorization)
            app.state.store.save(result, owner_id)
            return result
        except ModelUnavailable as error:
            raise HTTPException(status_code=503, detail=str(error)) from error
        except FundApiError as error:
            raise HTTPException(status_code=error.status_code, detail=str(error)) from error
        except ModelError as error:
            raise HTTPException(status_code=502, detail=str(error)) from error

    @app.post("/api/research/compare/stream")
    async def stream_compare_funds(
        request: CompareRequest, authorization: str | None = Header(default=None)
    ) -> StreamingResponse:
        # 在响应头发出前检查配置，前端能收到正常的 HTTP 503。
        try:
            owner_id = await app.state.identity.user_id(authorization)
            app.state.workflow.model.ensure_available()
        except FundApiError as error:
            raise HTTPException(status_code=error.status_code, detail=str(error)) from error
        except ModelUnavailable as error:
            raise HTTPException(status_code=503, detail=str(error)) from error

        async def events():
            def event(name: str, data: dict) -> bytes:
                # SSE 用空行分隔事件；JSON 把文本中的换行安全地转义。
                payload = json.dumps(data, ensure_ascii=False)
                return f"event: {name}\ndata: {payload}\n\n".encode("utf-8")

            run_id = str(uuid4())
            state = {"request": request}
            yield event("started", {"runId": run_id})
            try:
                async for role, changes in app.state.workflow.updates(request, authorization):
                    state.update(changes)
                    output_key = "snapshot" if role == "data" else "decision" if role == "judge" else role
                    yield event("node_completed", {
                        "node": role,
                        "output": changes[output_key].model_dump(mode="json", by_alias=True),
                        "trace": changes[f"{role}_trace"].model_dump(mode="json", by_alias=True),
                    })
                result = app.state.workflow.result_from_state(state, run_id)
                app.state.store.save(result, owner_id)
                yield event("completed", {
                    "result": result.model_dump(mode="json", by_alias=True),
                })
            except (FundApiError, ModelError, ModelUnavailable) as error:
                yield event("failed", {"message": str(error)})
            except Exception:
                # 流已经开始后不能再改 HTTP 状态码，只发送不含内部细节的失败事件。
                yield event("failed", {"message": "研究运行失败，请稍后重试"})

        return StreamingResponse(events(), media_type="text/event-stream", headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        })

    @app.get("/api/research/runs/{run_id}", response_model=ResearchResult)
    async def get_research_run(
        run_id: str, authorization: str | None = Header(default=None)
    ) -> ResearchResult:
        try:
            owner_id = await app.state.identity.user_id(authorization)
        except FundApiError as error:
            raise HTTPException(status_code=error.status_code, detail=str(error)) from error
        result = app.state.store.load(run_id, owner_id)
        if result is None:
            raise HTTPException(status_code=404, detail="研究运行不存在")
        return result

    return app


app = create_app()
