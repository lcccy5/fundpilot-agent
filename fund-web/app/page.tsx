"use client";
import { FormEvent, ReactNode, useMemo, useRef, useState } from "react";
import AppShell from "../components/AppShell";
import {
  api,
  currentAccessToken,
  guestWatch,
  hasFreshAccess,
  readJsonResponse,
  restoreSession,
  saveGuestWatch,
} from "../lib/session";
const API = process.env.NEXT_PUBLIC_API_BASE ?? "";
type Fund = {
  fundCode: string;
  name: string;
  fundType?: string;
  managementCompany?: string;
  fundManager?: string;
  establishedDate?: string;
  dataSource?: string;
};
type Nav = { navDate: string; unitNav: number };
type RunSummary = {
  run_id: string;
  status: string;
  prompt_version: string;
  model_name: string;
  model_rounds: number;
  tool_call_count: number;
  total_tokens?: number;
  duration_ms?: number;
  evidenceCount: number;
  dataCutoff?: string;
  sources: string[];
  tools: {
    toolName: string;
    status: string;
    durationMs: number;
    evidenceCount: number;
  }[];
  agentOps: {
    available: boolean;
    modelCallCount?: number;
    actualTokens?: number;
    settled?: boolean;
    status?: string;
  };
  adminConsoleUrl?: string;
};
type Msg = {
  role: "assistant" | "user";
  content: string;
  runId?: string;
  runSummary?: RunSummary;
  summaryUnavailable?: boolean;
};
type Tab = "overview" | "watchlist" | "agent" | "insights" | "knowledge";
const inline = (text: string) =>
  text
    .split(/(\*\*.*?\*\*|`.*?`|\bev-[a-z0-9-]{12,}\b)/gi)
    .filter(Boolean)
    .map((part, i) => {
      const unquoted =
        part.startsWith("`") && part.endsWith("`") ? part.slice(1, -1) : part;
      if (/^ev-[a-z0-9-]{12,}$/i.test(unquoted))
        return (
          <span className="evidence" title={unquoted} key={i}>
            证据
          </span>
        );
      if (part.startsWith("**") && part.endsWith("**"))
        return <strong key={i}>{part.slice(2, -2)}</strong>;
      if (part.startsWith("`") && part.endsWith("`"))
        return <code key={i}>{unquoted}</code>;
      return part;
    });
const evidencePattern = "ev-[a-z0-9-]{12,}",
  isTableLine = (line: string) =>
    new RegExp(`^\\|.*\\|(?:\\s+${evidencePattern})?\\s*$`, "i").test(
      line.trim(),
    ),
  tableCells = (line: string) =>
    line
      .trim()
      .replace(new RegExp(`\\s+${evidencePattern}\\s*$`, "i"), "")
      .replace(/^\||\|$/g, "")
      .split("|")
      .map((cell) => cell.trim());
function RichText({ text }: { text: string }) {
  const lines = text.replace(/\r\n?/g, "\n").split("\n"),
    nodes: ReactNode[] = [];
  for (let i = 0; i < lines.length; ) {
    const line = lines[i],
      trim = line.trim();
    if (!trim) {
      i++;
      continue;
    }
    if (/数据来源\s*evidenceId/i.test(trim)) {
      nodes.push(
        <div className="audit-note" key={i++}>
          ✓ 引用证据已校验
        </div>,
      );
      continue;
    }
    if (isTableLine(trim)) {
      const rows: string[][] = [];
      while (i < lines.length && isTableLine(lines[i])) {
        const clean = lines[i]
          .trim()
          .replace(new RegExp(`\\s+${evidencePattern}\\s*$`, "i"), "");
        if (!/^\|?[\s:|-]+\|?$/.test(clean)) rows.push(tableCells(lines[i]));
        i++;
      }
      if (rows.length) {
        nodes.push(
          <div className="table-wrap" key={`t-${i}`}>
            <table>
              <thead>
                <tr>
                  {rows[0].map((cell, j) => (
                    <th key={j}>{inline(cell)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.slice(1).map((row, j) => (
                  <tr key={j}>
                    {row.map((cell, k) => (
                      <td key={k}>{inline(cell)}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>,
        );
      }
      continue;
    }
    if (/^[-*]\s/.test(trim)) {
      const items: string[] = [];
      while (i < lines.length && /^[-*]\s/.test(lines[i].trim()))
        items.push(lines[i++].trim().replace(/^[-*]\s/, ""));
      nodes.push(
        <ul key={`u-${i}`}>
          {items.map((item, j) => (
            <li key={j}>{inline(item)}</li>
          ))}
        </ul>,
      );
      continue;
    }
    if (/^\d+\.\s/.test(trim)) {
      const items: string[] = [];
      while (i < lines.length && /^\d+\.\s/.test(lines[i].trim()))
        items.push(lines[i++].trim().replace(/^\d+\.\s/, ""));
      nodes.push(
        <ol key={`o-${i}`}>
          {items.map((item, j) => (
            <li key={j}>{inline(item)}</li>
          ))}
        </ol>,
      );
      continue;
    }
    if (/^---+$/.test(trim)) {
      nodes.push(<hr key={i++} />);
      continue;
    }
    if (trim.startsWith("#### ")) {
      nodes.push(<h4 key={i++}>{inline(trim.slice(5))}</h4>);
      continue;
    }
    if (trim.startsWith("### ")) {
      nodes.push(<h3 key={i++}>{inline(trim.slice(4))}</h3>);
      continue;
    }
    if (trim.startsWith("## ")) {
      nodes.push(<h2 key={i++}>{inline(trim.slice(3))}</h2>);
      continue;
    }
    if (trim.startsWith("# ")) {
      nodes.push(<h1 key={i++}>{inline(trim.slice(2))}</h1>);
      continue;
    }
    if (trim.startsWith("> ")) {
      nodes.push(<blockquote key={i++}>{inline(trim.slice(2))}</blockquote>);
      continue;
    }
    nodes.push(<p key={i++}>{inline(trim)}</p>);
  }
  return <div className="rich-text">{nodes}</div>;
}

/** Presents user-relevant trace facts while leaving accounting internals in the separate operator console. */
function RunSummaryPanel({ message }: { message: Msg }) {
  if (!message.runId) return null;
  if (message.summaryUnavailable)
    return <div className="run-summary pending">运行摘要暂不可用</div>;
  if (!message.runSummary)
    return <div className="run-summary pending">正在同步运行摘要…</div>;
  const summary = message.runSummary,
    accounting = summary.agentOps;
  return (
    <details className="run-summary">
      <summary>
        本次运行 · {summary.tool_call_count} 个工具 ·{" "}
        {summary.evidenceCount} 条证据
      </summary>
      <div className="run-facts">
        <span>
          <b>模型</b>
          {summary.model_name}
        </span>
        <span>
          <b>Prompt</b>
          {summary.prompt_version}
        </span>
        <span>
          <b>数据截止</b>
          {summary.dataCutoff ?? "以各证据为准"}
        </span>
        <span>
          <b>运行耗时</b>
          {summary.duration_ms == null ? "—" : `${summary.duration_ms} ms`}
        </span>
        <span>
          <b>模型调用</b>
          {accounting.available ? accounting.modelCallCount : "暂不可用"}
        </span>
        <span>
          <b>实际 Token</b>
          {accounting.available
            ? (accounting.actualTokens ?? summary.total_tokens ?? "—")
            : (summary.total_tokens ?? "—")}
        </span>
      </div>
      {summary.tools.length > 0 && (
        <div className="run-tools">
          {summary.tools.map((tool, i) => (
            <span key={`${tool.toolName}-${i}`}>
              {tool.toolName} · {tool.status} · {tool.evidenceCount} 条证据
            </span>
          ))}
        </div>
      )}
      <small>
        AgentOps 账本：
        {accounting.available
          ? accounting.settled
            ? "已结算"
            : "处理中"
          : "暂不可用"}
        {summary.sources.length > 0 &&
          ` · 来源 ${summary.sources.slice(0, 3).join("、")}`}
      </small>
      {summary.adminConsoleUrl && (
        <a href={summary.adminConsoleUrl} target="_blank" rel="noreferrer">
          管理员查看 AgentOps 详情 →
        </a>
      )}
    </details>
  );
}

/** Builds a rolling one-year NAV window from the user''s local calendar date. */
function currentNavWindow() {
  const end = new Date();
  const start = new Date(end);
  start.setFullYear(start.getFullYear() - 1);
  const format = (date: Date) =>
    `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  return { startDate: format(start), endDate: format(end) };
}

/** Normalizes providers with ascending or descending NAV payloads for chart rendering. */
function chronologicalNavPoints(points: Nav[]) {
  return [...points].sort((left, right) =>
    left.navDate.localeCompare(right.navDate),
  );
}

function Chart({ points }: { points: Nav[] }) {
  const p = useMemo(
    () =>
      points.filter(
        (_, i) =>
          i % Math.max(1, Math.ceil(points.length / 80)) === 0 ||
          i === points.length - 1,
      ),
    [points],
  );
  if (p.length < 2)
    return (
      <div className="empty-chart">
        查询基金后，这里会展示真实单位净值走势。
      </div>
    );
  const v = p.map((x) => +x.unitNav),
    min = Math.min(...v),
    max = Math.max(...v),
    r = max - min || 1,
    d = p
      .map(
        (x, i) =>
          `${i ? "L" : "M"} ${((i / (p.length - 1)) * 100).toFixed(2)} ${(90 - ((+x.unitNav - min) / r) * 72).toFixed(2)}`,
      )
      .join(" "),
    change = ((v.at(-1)! - v[0]) / v[0]) * 100;
  return (
    <>
      <div className="chart-meta">
        <b>{v.at(-1)?.toFixed(4)}</b>
        <span className={change >= 0 ? "up" : "down"}>
          {change >= 0 ? "+" : ""}
          {change.toFixed(2)}% · 近一年
        </span>
      </div>
      <svg
        className="real-chart"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
      >
        <path d={`M 0 100 ${d} L 100 100 Z`} fill="#2aa57e" opacity=".15" />
        <path
          d={d}
          fill="none"
          stroke="#08745e"
          strokeWidth="1.3"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
      <div className="dates">
        <span>{p[0].navDate}</span>
        <span>{p[Math.floor(p.length / 2)].navDate}</span>
        <span>{p.at(-1)?.navDate}</span>
      </div>
    </>
  );
}
export default function Home() {
  const [tab, setTab] = useState<Tab>("overview"),
    [code, setCode] = useState("000001"),
    [fund, setFund] = useState<Fund | null>(null),
    [points, setPoints] = useState<Nav[]>([]),
    [notice, setNotice] = useState("实时研究环境已连接"),
    [loading, setLoading] = useState(false),
    [watch, setWatch] = useState<Fund[]>([]),
    [cid, setCid] = useState<string>(),
    [q, setQ] = useState("这只基金适合什么风险偏好的投资者？"),
    [messages, setMessages] = useState<Msg[]>([
      {
        role: "assistant",
        content:
          "你好，我是 FundPilot。输入基金代码，我会结合净值、历史表现与知识资料为你解读。",
      },
    ]),
    [agentLoading, setAgentLoading] = useState(false),
    [steps, setSteps] = useState<string[]>([]);
  const agentRef = useRef<HTMLElement>(null);
  const sync = async (e?: FormEvent) => {
    e?.preventDefault();
    const id = code.padStart(6, "0");
    const { startDate, endDate } = currentNavWindow();
    setLoading(true);
    setNotice("正在读取基金资料与历史净值…");
    try {
      const [a, b] = await Promise.all([
          fetch(`${API}/api/v1/funds/${id}`),
          fetch(
            `${API}/api/v1/funds/${id}/nav?startDate=${startDate}&endDate=${endDate}`,
          ),
        ]),
        profile = await readJsonResponse<{
          message?: string;
          msg?: string;
          data?: Fund;
        }>(a),
        history = await readJsonResponse<{
          message?: string;
          msg?: string;
          data?: { items?: Nav[] };
          items?: Nav[];
        }>(b);
      if (!a.ok)
        throw Error(profile.message ?? profile.msg ?? "基金资料暂不可用");
      if (!b.ok)
        throw Error(history.message ?? history.msg ?? "历史净值暂不可用");
      setFund(profile.data ?? (profile as Fund));
      setPoints(chronologicalNavPoints((history.data ?? history).items ?? []));
      setTab("overview");
      setNotice("已加载公开基金资料 · 数据仅供研究参考");
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "查询失败");
    } finally {
      setLoading(false);
    }
  };
  const ask = async (e: FormEvent) => {
    e.preventDefault();
    if (!q.trim() || agentLoading) return;
    const text = q;
    setMessages((x) => [...x, { role: "user", content: text }]);
    setQ("");
    setAgentLoading(true);
    setSteps([]);
    setNotice("FundPilot 正在分析…");
    try {
      // Refresh once before protected calls so an expired browser session can recover.
      if (!hasFreshAccess() && !(await restoreSession()))
        throw Error("请先登录后再使用 AI 研究助手");
      let id = cid;
      if (!id) {
        // Conversation creation returns JSON; SSE is negotiated only for the chat stream.
        const payload = await api<{ conversationId: string }>(
          "/api/v1/agent/conversations",
          { method: "POST", headers: { Accept: "application/json" } },
        );
        id = payload.conversationId;
        setCid(id);
      }
      const authHeaders: Record<string, string> = {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        Authorization: `Bearer ${currentAccessToken()}`,
      };
      const r = await fetch(`${API}/api/v1/agent/chat/stream`, {
        method: "POST",
        headers: authHeaders,
        credentials: "include",
        body: JSON.stringify({ conversationId: id, message: text }),
      });
      if (!r.ok) throw Error((await r.text()) || "Agent 流式调用失败");
      if (!r.body) throw Error("浏览器不支持流式响应");
      const reader = r.body.getReader(),
        decoder = new TextDecoder();
      let buffer = "",
        streaming = false;
      const label = (name: string) =>
        (
          ({
            fund_realtime_quote: "关联ETF实时行情",
            analyze_sector_outlook: "板块实时情景分析",
            research_fund_catalysts: "基金利好利空研究",
            get_fund_portfolio_exposure: "穿透最近披露持仓",
            map_industry_chain_exposure: "映射重仓股产业链",
            search_verified_market_events: "检索并核验公司公告",
            assess_event_fund_impact: "计算事件持仓影响",
            get_fund_profile: "基金基本资料",
            get_fund_nav_history: "历史净值",
            calculate_fund_metrics: "收益风险指标",
            compare_fund_metrics: "基金比较",
            search_fund_documents: "知识库检索",
          }) as Record<string, string>
        )[name] ?? name;
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder
          .decode(value, { stream: true })
          .replace(/\r\n/g, "\n");
        const blocks = buffer.split("\n\n");
        buffer = blocks.pop() ?? "";
        for (const block of blocks) {
          const raw = block
            .split("\n")
            .filter((x) => x.startsWith("data:"))
            .map((x) => x.slice(5).trim())
            .join("");
          if (!raw) continue;
          const event = JSON.parse(raw),
            data = event.data;
          if (event.type === "run.started")
            setSteps((x) => [...x, "Agent 运行已开始"]);
          else if (event.type === "tool.started")
            setSteps((x) => [...x, `正在调用：${label(data.toolName)}`]);
          else if (event.type === "tool.completed")
            setSteps((x) => [
              ...x,
              `${label(data.toolName)}完成 · ${data.durationMs}ms · ${data.evidenceIds?.length ?? 0} 条证据`,
            ]);
          else if (event.type === "tool.failed")
            setSteps((x) => [
              ...x,
              `${label(data.toolName)}失败 · ${data.errorCode}`,
            ]);
          else if (event.type === "evidence.verifying")
            setSteps((x) => [...x, "正在校验证据引用"]);
          else if (event.type === "run.failed")
            throw Error(`${data.message}（${data.errorCode}）`);
          else if (event.type === "answer.delta") {
            const chunk = String(data ?? "");
            setMessages((x) => {
              if (streaming) {
                const copy = [...x],
                  last = copy.at(-1)!;
                copy[copy.length - 1] = {
                  role: "assistant",
                  content: last.content + chunk,
                };
                return copy;
              }
              streaming = true;
              return [...x, { role: "assistant", content: chunk }];
            });
          } else if (event.type === "answer.completed") {
            const answer = data.answer,
              runId = String(event.runId ?? data.runId ?? "");
            setMessages((x) => {
              if (streaming) {
                const copy = [...x];
                copy[copy.length - 1] = {
                  role: "assistant",
                  content: answer,
                  runId,
                };
                return copy;
              }
              return [...x, { role: "assistant", content: answer, runId }];
            });
            // Load the owner-scoped BFF projection after the answer is durable and accounting can settle.
            if (runId)
              void api<RunSummary>(
                `/api/v1/agent/runs/${runId}/queryExecutionSummary`,
              )
                .then((runSummary) =>
                  setMessages((current) =>
                    current.map((message) =>
                      message.runId === runId
                        ? { ...message, runSummary }
                        : message,
                    ),
                  ),
                )
                .catch(() =>
                  setMessages((current) =>
                    current.map((message) =>
                      message.runId === runId
                        ? { ...message, summaryUnavailable: true }
                        : message,
                    ),
                  ),
                );
            setSteps((x) => [...x, "回答生成完成"]);
            setNotice("分析完成 · 已进行证据校验");
          }
        }
      }
    } catch (x) {
      const detail = x instanceof Error ? x.message : "未知错误";
      setSteps((y) => [...y, `执行失败：${detail}`]);
      setMessages((y) => [
        ...y,
        { role: "assistant", content: `本次请求未完成：${detail}` },
      ]);
      setNotice(`Agent 请求失败 · ${detail}`);
    } finally {
      setAgentLoading(false);
    }
  };
  const add = () => {
    if (fund && !watch.some((x) => x.fundCode === fund.fundCode)) {
      setWatch((x) => [...x, fund]);
      saveGuestWatch([...guestWatch(), fund.fundCode]);
    }
    setTab("watchlist");
  };
  const overview = (
    <>
      <section className="hero">
        <div>
          <em>实时研究工作台</em>
          <h2>{fund?.name ?? "输入一只基金，开始你的研究"}</h2>
          <p>
            {fund?.fundType ??
              "查看真实净值、历史收益和回撤，再由 AI 给出可追溯的研究解读。"}
          </p>
          <mark>{fund?.fundCode ?? "基金代码"}</mark>
          {fund?.managementCompany && <mark>{fund.managementCompany}</mark>}
        </div>
        <div className="orb">
          <b>
            AI
            <br />
            INSIGHT
          </b>
        </div>
      </section>
      <section className="stats">
        <article>
          <small>数据来源</small>
          <b>{fund?.dataSource ?? "第三方基金数据"}</b>
          <em>公开数据同步</em>
        </article>
        <article>
          <small>最新单位净值</small>
          <b>{points.at(-1)?.unitNav?.toFixed(4) ?? "—"}</b>
          <em>{points.at(-1)?.navDate ?? "等待查询"}</em>
        </article>
        <article>
          <small>基金类型</small>
          <b>{fund?.fundType ?? "—"}</b>
          <em>产品资料</em>
        </article>
        <article>
          <small>管理人</small>
          <b>{fund?.managementCompany ?? "—"}</b>
          <em>产品资料</em>
        </article>
      </section>
      <section className="grid">
        <article className="card chart">
          <p>PERFORMANCE · REAL NAV</p>
          <h3>单位净值与趋势</h3>
          <Chart points={points} />
        </article>
        <article className="card profile">
          <p>FUND PROFILE</p>
          <h3>{fund?.name ?? "等待基金查询"}</h3>
          <dl>
            <div>
              <dt>基金代码</dt>
              <dd>{fund?.fundCode ?? "—"}</dd>
            </div>
            <div>
              <dt>基金类型</dt>
              <dd>{fund?.fundType ?? "—"}</dd>
            </div>
            <div>
              <dt>基金经理</dt>
              <dd>{fund?.fundManager ?? "资料同步后展示"}</dd>
            </div>
            <div>
              <dt>管理人</dt>
              <dd>{fund?.managementCompany ?? "资料同步后展示"}</dd>
            </div>
            <div>
              <dt>成立日期</dt>
              <dd>{fund?.establishedDate ?? "—"}</dd>
            </div>
          </dl>
          <button onClick={() => sync()}>更新数据 →</button>
          <button className="text-button" onClick={add}>
            加入自选 ＋
          </button>
        </article>
      </section>
    </>
  );
  let panel = overview;
  if (tab === "watchlist")
    panel = (
      <section className="workspace">
        <p>WATCHLIST</p>
        <h2>我的自选</h2>
        {watch.length ? (
          <div className="watch-list">
            {watch.map((x) => (
              <button
                key={x.fundCode}
                onClick={() => {
                  setCode(x.fundCode);
                  setFund(x);
                  setTab("overview");
                }}
              >
                {x.name}
                <small>
                  {x.fundCode} · {x.fundType ?? "基金"}
                </small>
              </button>
            ))}
          </div>
        ) : (
          <div className="empty-panel">
            还没有自选基金。查询基金后可在资料卡中加入自选。
          </div>
        )}
      </section>
    );
  if (tab === "insights")
    panel = (
      <section className="workspace">
        <p>DATA INSIGHTS</p>
        <h2>数据洞察</h2>
        <div className="insight-grid">
          <div>
            <small>近一年样本</small>
            <b>{points.length || "—"} 个交易日</b>
          </div>
          <div>
            <small>区间最高净值</small>
            <b>
              {points.length
                ? Math.max(...points.map((x) => x.unitNav)).toFixed(4)
                : "—"}
            </b>
          </div>
          <div>
            <small>区间最低净值</small>
            <b>
              {points.length
                ? Math.min(...points.map((x) => x.unitNav)).toFixed(4)
                : "—"}
            </b>
          </div>
        </div>
        <Chart points={points} />
      </section>
    );
  if (tab === "knowledge")
    panel = (
      <section className="workspace">
        <p>KNOWLEDGE BASE</p>
        <h2>研究知识库</h2>
        <div className="empty-panel">
          ES 向量检索已接入后端。上传基金公告、定期报告后，Agent
          会引用相应原文。
        </div>
      </section>
    );
  return (
    <AppShell notice={notice}>
      <form className="search" onSubmit={sync}>
        <span>⌕</span>
        <input
          value={code}
          onChange={(e) =>
            setCode(e.target.value.replace(/\D/g, "").slice(0, 6))
          }
        />
        <button>{loading ? "同步中" : "查询基金"}</button>
      </form>
      {panel}
      <section className="agent" ref={agentRef}>
        <div className="agent-title">
          <i>✦</i>
          <div>
            <p>FUNDPILOT AGENT</p>
            <h3>和你的基金研究助手聊聊</h3>
          </div>
        </div>
        {steps.length > 0 && (
          <ol className="agent-trace">
            {steps.map((step, i) => (
              <li
                key={`${step}-${i}`}
                className={
                  step.startsWith("执行失败")
                    ? "failed"
                    : agentLoading && i === steps.length - 1
                      ? "running"
                      : ""
                }
              >
                {i + 1}. {step}
              </li>
            ))}
          </ol>
        )}
        <div className="messages">
          {messages.slice(-4).map((m, i) => (
            <div key={i} className={m.role}>
              <i>{m.role === "assistant" ? "F" : "你"}</i>
              <div className="bubble">
                {m.role === "assistant" ? (
                  <>
                    <RichText text={m.content} />
                    <RunSummaryPanel message={m} />
                  </>
                ) : (
                  m.content
                )}
              </div>
            </div>
          ))}
        </div>
        <form onSubmit={ask}>
          <input
            disabled={agentLoading}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="例如：这只基金近一年回撤如何？"
          />
          <button disabled={agentLoading}>{agentLoading ? "…" : "↑"}</button>
        </form>
        <small>
          显示的是可审计的执行轨迹，不展示模型隐藏思维链。回答仅供研究参考。个性化问题请先登录。
        </small>
      </section>
    </AppShell>
  );
}
