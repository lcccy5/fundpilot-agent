"use client";
import { FormEvent, PointerEvent, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
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
import { navChartSegments } from "../lib/nav-gaps.mjs";
import { acceptDelta, stopNotice } from "../lib/stop-generation.mjs";
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
type NavRange = "1m" | "3m" | "6m" | "1y";
const NAV_RANGES: { value: NavRange; label: string }[] = [
  { value: "1m", label: "近1个月" },
  { value: "3m", label: "近3个月" },
  { value: "6m", label: "近半年" },
  { value: "1y", label: "近1年" },
];
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
  id: string;
  role: "assistant" | "user";
  content: string;
  runId?: string;
  runSummary?: RunSummary;
  summaryUnavailable?: boolean;
  evidence?: Evidence[];
  evidenceChecked?: boolean;
  activity?: {
    status: "running" | "completed" | "failed";
    steps: { text: string; failed?: boolean }[];
  };
};
type Evidence = {
  evidenceId: string;
  evidenceType?: string;
  dataSource?: string;
  documentTitle?: string;
  publishedDate?: string;
  actualStartDate?: string;
  actualEndDate?: string;
  excerpt?: string;
  pageStart?: number | null;
  pageEnd?: number | null;
  sourceUri?: string;
};
const SUGGESTED = ["近一年回撤有多大", "区间波动和收益差在哪", "资料里怎么描述投资范围"];
const RESEARCH_DESK_KEY = "fundpilot.researchDesk";
const WELCOME: Msg = {
  id: "welcome",
  role: "assistant",
  content: "查到基金后，可以直接问回撤、波动，或资料里的投资范围。",
};
type WatchChip = { code: string; name: string };
type ResearchDesk = {
  code: string;
  fund: Fund | null;
  points: Nav[];
  navRange: NavRange;
  cid?: string;
  q: string;
  messages: Msg[];
  watchedCode?: string;
};
function readResearchDesk(): ResearchDesk | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = sessionStorage.getItem(RESEARCH_DESK_KEY);
    if (!raw) return null;
    const saved = JSON.parse(raw) as ResearchDesk;
    if (!saved || !Array.isArray(saved.messages) || saved.messages.length === 0) return null;
    return saved;
  } catch {
    return null;
  }
}
function writeResearchDesk(desk: ResearchDesk) {
  const save = (value: ResearchDesk) => sessionStorage.setItem(RESEARCH_DESK_KEY, JSON.stringify(value));
  try {
    save(desk);
  } catch {
    try {
      save({ ...desk, points: desk.points.slice(-30), messages: desk.messages.map((message) => ({ ...message, evidence: undefined, runSummary: undefined })) });
    } catch { /* The current view stays usable even if this browser refuses the snapshot. */ }
  }
}
function watchCodeOf(fundCode: string | { value: string }) {
  return typeof fundCode === "string" ? fundCode : fundCode.value;
}
const TYPE_LABEL: Record<string, string> = {
  FUND_PROFILE: "基金资料",
  FUND_NAV: "历史净值",
  FUND_METRICS: "收益风险指标",
  FUND_DOCUMENT: "研究资料",
  FUND_REALTIME_QUOTE: "实时行情",
};
const CODE_LABEL: Record<string, string> = {
  UNIT_NAV: "单位净值",
  ACCUMULATED_NAV: "累计净值",
  UNKNOWN: "暂无",
  DYNAMIC_REPRESENTATIVE_EXCHANGE_FUND: "动态代表份额",
};
const TECH_SPLIT = /([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|\bUNKNOWN\b|\b[a-z]+(?:-[a-z0-9]+){2,}|\bv\d+\b|\b\d{12,}\b)/;
const isTech = (value: string) => TECH_SPLIT.test(value) && value.length < 80;
function quietCodes(text: string, key: string): ReactNode[] {
  return text.split(TECH_SPLIT).filter(Boolean).map((part, index) => {
    if (!isTech(part)) return part;
    const label = CODE_LABEL[part] ?? part;
    return (
      <code className="tech-token" title={part} key={`${key}-${index}`}>
        {label}
      </code>
    );
  });
}
const isMetaLine = (trim: string) =>
  /^(?:#{1,4}\s+)?(?:\*\*)?(?:实际数据区间|净值口径|数据版本|数据区间|时效性|数据口径)/.test(trim);
function hideRepeatedCodes(text: string) {
  return text.replace(/[（(]\s*([A-Z][A-Z0-9_]*)\s*[）)]/g, (full, code: string) => {
    const label = CODE_LABEL[code];
    return label && text.includes(label) ? "" : full;
  });
}
function readableFailure(detail: string) {
  if (/EVIDENCE/i.test(detail)) return "引用没有通过核对";
  const stripped = detail.replace(/[（(][A-Z][A-Z0-9_]+[）)]/g, "").trim();
  return stripped || "这次没有完成";
}
const inline = (text: string, onEvidence?: (id: string) => void) =>
  hideRepeatedCodes(text)
    .split(/(\*\*.*?\*\*|`.*?`|\bev-[a-z0-9-]{12,}\b)/gi)
    .filter(Boolean)
    .map((part, i) => {
      const unquoted =
        part.startsWith("`") && part.endsWith("`") ? part.slice(1, -1) : part;
      if (/^(?:ev-[a-z0-9-]{12,}|DOC:[A-Za-z0-9_.:-]+)$/i.test(unquoted))
        return (
          <button type="button" className="evidence" key={i} onClick={() => onEvidence?.(unquoted)}>
            依据
          </button>
        );
      if (part.startsWith("**") && part.endsWith("**"))
        return <strong key={i}>{quietCodes(part.slice(2, -2), `b${i}`)}</strong>;
      if (part.startsWith("`") && part.endsWith("`"))
        return <code className="tech-token" key={i}>{unquoted}</code>;
      return <span key={i}>{quietCodes(part, `t${i}`)}</span>;
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
function RichText({ text, onEvidence }: { text: string; onEvidence?: (id: string) => void }) {
  const lines = text.replace(/\r\n?/g, "\n").split("\n"),
    nodes: ReactNode[] = [];
  for (let i = 0; i < lines.length; ) {
    const line = lines[i],
      trim = line.trim();
    if (!trim) {
      i++;
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
                    <th key={j}>{inline(cell, onEvidence)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.slice(1).map((row, j) => (
                  <tr key={j}>
                    {row.map((cell, k) => (
                      <td key={k}>{inline(cell, onEvidence)}</td>
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
            <li key={j}>{inline(item, onEvidence)}</li>
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
            <li key={j}>{inline(item, onEvidence)}</li>
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
      nodes.push(<h4 key={i++}>{inline(trim.slice(5), onEvidence)}</h4>);
      continue;
    }
    if (trim.startsWith("### ")) {
      nodes.push(<h3 key={i++}>{inline(trim.slice(4), onEvidence)}</h3>);
      continue;
    }
    if (trim.startsWith("## ")) {
      nodes.push(<h2 key={i++}>{inline(trim.slice(3), onEvidence)}</h2>);
      continue;
    }
    if (trim.startsWith("# ")) {
      nodes.push(<h1 key={i++}>{inline(trim.slice(2), onEvidence)}</h1>);
      continue;
    }
    if (trim.startsWith("> ")) {
      nodes.push(<blockquote key={i++}>{inline(trim.slice(2), onEvidence)}</blockquote>);
      continue;
    }
    if (isMetaLine(trim)) {
      const notes: string[] = [];
      while (i < lines.length && isMetaLine(lines[i].trim()))
        notes.push(lines[i++].trim().replace(/^#{1,4}\s+/, ""));
      nodes.push(
        <div className="meta-block" key={`m-${i}`}>
          {notes.map((note, j) => (
            <p className="meta-line" key={j}>{inline(note, onEvidence)}</p>
          ))}
        </div>,
      );
      continue;
    }
    nodes.push(<p key={i++}>{inline(trim, onEvidence)}</p>);
  }
  return <div className="rich-text">{nodes}</div>;
}

function EvidenceCard({ item, onClose }: { item?: Evidence; onClose: () => void }) {
  const title = item?.documentTitle || (item?.evidenceType && TYPE_LABEL[item.evidenceType]) || "研究依据";
  const date = item?.publishedDate || (item?.actualStartDate && item.actualEndDate ? `${item.actualStartDate} 至 ${item.actualEndDate}` : item?.actualEndDate);
  return (
    <aside className="evidence-card">
      <header><b>{title}</b><button type="button" onClick={onClose}>关闭</button></header>
      {date && <p>数据日期 {date}</p>}
      {item?.dataSource && <p>来源 {item.dataSource === "mock" ? "演示数据" : item.dataSource}</p>}
      {item?.pageStart != null && <p>页码 {item.pageStart}{item.pageEnd != null && item.pageEnd !== item.pageStart ? `–${item.pageEnd}` : ""}</p>}
      {item?.excerpt ? <blockquote>{item.excerpt}</blockquote> : <p>{item ? "这条依据来自基金数据，回答里没有附带原文摘录。" : "依据详情会在回答完成后显示。"}</p>}
      {item?.sourceUri && <p><a href={item.sourceUri} target="_blank" rel="noreferrer">打开原文</a></p>}
    </aside>
  );
}

/** Shows live research steps, then folds them into a source count. */
function RunActivityPanel({ message }: { message: Msg }) {
  if (!message.activity && !message.runId) return null;
  const activity = message.activity,
    summary = message.runSummary,
    accounting = summary?.agentOps,
    count = message.evidence?.length || summary?.evidenceCount || 0,
    lastStep = activity?.steps.at(-1)?.text,
    running = activity?.status === "running",
    failed = activity?.status === "failed";
  const steps = activity && activity.steps.length > 0 && (
    <ol className={running && !message.content ? "agent-live-steps" : "agent-trace"}>
      {activity.steps.map((step, i) => (
        <li key={`${step.text}-${i}`} className={step.failed ? "failed" : running && i === activity.steps.length - 1 ? "running" : ""}>
          <span>{i + 1}. {step.text}</span>
        </li>
      ))}
    </ol>
  );
  if (running && !message.content) return <div className="agent-live"><b>{lastStep ?? "正在准备研究"}</b>{steps}</div>;
  if (running) return <div className="agent-live"><b>{lastStep ?? "正在整理结论"}</b></div>;
  const label = failed ? (message.content ? "引用没有通过核对" : "这次没有完成") : message.evidenceChecked && count > 0 ? `已核对 ${count} 条依据` : "查看研究过程";
  return (
    <details className={`agent-activity ${failed ? "failed" : "completed"}`}>
      <summary><span>{label}</span></summary>
      {steps}
      {message.evidenceChecked && count > 0 && <div className="audit-note">引用依据已核对</div>}
      <details className="run-details">
        <summary>运行详情</summary>
        {message.summaryUnavailable && <div className="run-summary pending">运行摘要暂不可用</div>}
        {message.runId && !message.summaryUnavailable && !summary && <div className="run-summary pending">正在同步运行摘要…</div>}
        {summary && <div className="run-summary">
          <div className="run-facts">
            <span><b>数据截止</b>{summary.dataCutoff ?? "以各依据为准"}</span>
            <span><b>运行耗时</b>{summary.duration_ms == null ? "—" : `${summary.duration_ms} ms`}</span>
            <span><b>模型</b>{summary.model_name}</span>
            <span><b>Prompt</b>{summary.prompt_version}</span>
            <span><b>模型调用</b>{accounting?.available ? accounting.modelCallCount : "暂不可用"}</span>
            <span><b>Token</b>{accounting?.available ? (accounting.actualTokens ?? summary.total_tokens ?? "—") : (summary.total_tokens ?? "—")}</span>
          </div>
          {summary.sources.length > 0 && <small>来源 {summary.sources.slice(0, 3).join("、")}</small>}
          {summary.adminConsoleUrl && <a href={summary.adminConsoleUrl} target="_blank" rel="noreferrer">管理员查看详情</a>}
        </div>}
      </details>
    </details>
  );
}

/** Builds the selected NAV window from the user's local calendar date. */
function currentNavWindow(range: NavRange = "1y") {
  const end = new Date();
  const start = new Date(end);
  if (range === "1m") start.setMonth(start.getMonth() - 1);
  if (range === "3m") start.setMonth(start.getMonth() - 3);
  if (range === "6m") start.setMonth(start.getMonth() - 6);
  if (range === "1y") start.setFullYear(start.getFullYear() - 1);
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

async function requestNavPoints(fundCode: string, startDate: string, endDate: string) {
  const response = await fetch(`${API}/api/v1/funds/${fundCode}/nav?startDate=${startDate}&endDate=${endDate}`);
  const history = await readJsonResponse<{message?:string;msg?:string;data?:{items?:Nav[]};items?:Nav[]}>(response);
  if (!response.ok) throw Error(history.message ?? history.msg ?? "历史净值暂不可用");
  return chronologicalNavPoints((history.data ?? history).items ?? []);
}

/** Fills a missing current-period tail even when a broad historical query was cached earlier. */
async function requestFreshNavPoints(fundCode: string, startDate: string, endDate: string) {
  const points = await requestNavPoints(fundCode, startDate, endDate);
  const latestDate = points.at(-1)?.navDate;
  if (!latestDate || latestDate >= endDate) return points;
  const nextDate = new Date(`${latestDate}T00:00:00`);
  nextDate.setDate(nextDate.getDate() + 1);
  const tailStart = `${nextDate.getFullYear()}-${String(nextDate.getMonth()+1).padStart(2,"0")}-${String(nextDate.getDate()).padStart(2,"0")}`;
  if (tailStart > endDate) return points;
  const tail = await requestNavPoints(fundCode, tailStart, endDate);
  return chronologicalNavPoints([...new Map([...points,...tail].map(point=>[point.navDate,point])).values()]);
}

function NavRangeSelector({ range, loading, onRangeChange }: { range: NavRange; loading: boolean; onRangeChange: (range: NavRange) => void }) {
  return <div className="chart-ranges" aria-label="净值时间范围">{NAV_RANGES.map(option=><button type="button" key={option.value} className={range===option.value?'active':''} disabled={loading} aria-pressed={range===option.value} onClick={()=>onRangeChange(option.value)}>{option.label}</button>)}</div>;
}

function Chart({ points, range, loading, onRangeChange }: { points: Nav[]; range: NavRange; loading: boolean; onRangeChange: (range: NavRange) => void }) {
  const chart = useMemo(() => navChartSegments(points), [points]);
  const p = chart.points;
  const [hover, setHover] = useState<number | null>(null);
  if (p.length < 2)
    return (
      <><div className="empty-chart">{loading?'正在加载区间净值…':'查询基金后，这里会展示真实单位净值走势。'}</div><NavRangeSelector range={range} loading={loading} onRangeChange={onRangeChange}/></>
    );
  const last = Number(p.at(-1)?.unitNav), first = Number(p[0].unitNav);
  const change = ((last - first) / first) * 100;
  const locate = (event: PointerEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width)) * 100;
    let nearest = 0;
    for (let index = 1; index < p.length; index += 1) if (Math.abs(p[index].x - ratio) < Math.abs(p[nearest].x - ratio)) nearest = index;
    setHover(nearest);
  };
  return (
    <>
      <div className="chart-meta">
        <b>{last.toFixed(4)}</b>
        <span className={change >= 0 ? "up" : "down"}>
          {change >= 0 ? "+" : ""}
          {change.toFixed(2)}% · 区间单位净值变化
        </span>
      </div>
      <svg
        className="real-chart"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        onPointerMove={locate}
        onPointerLeave={() => setHover(null)}
      >
        {[18,42,66,90].map(y=><line key={y} x1="0" x2="100" y1={y} y2={y} className="chart-grid-line" />)}
        {chart.segments.map(segment => <path key={segment} d={segment} fill="none" stroke="#286fda" strokeWidth="1.6" vectorEffect="non-scaling-stroke" strokeLinecap="round" strokeLinejoin="round" />)}
        {hover != null && p[hover] && <circle cx={p[hover].x} cy={p[hover].y} r="1.4" fill="#286fda" />}
      </svg>
      <div className="chart-scale"><span>{chart.max.toFixed(4)}</span><span>{chart.min.toFixed(4)}</span></div>
      <div className="dates">
        <span>{p[0].navDate}</span>
        <span>{p[Math.floor(p.length / 2)].navDate}</span>
        <span>{p.at(-1)?.navDate}</span>
      </div>
      {hover != null && p[hover] && <div className="chart-readout">{p[hover].navDate} · 单位净值 {Number(p[hover].unitNav).toFixed(4)}</div>}
      <NavRangeSelector range={range} loading={loading} onRangeChange={onRangeChange}/>
    </>
  );
}
export default function Home() {
  const [code, setCode] = useState(''),
    [fund, setFund] = useState<Fund | null>(null),
    [points, setPoints] = useState<Nav[]>([]),
    [navRange, setNavRange] = useState<NavRange>("1y"),
    [navLoading, setNavLoading] = useState(false),
    [notice, setNotice] = useState(""),
    [loading, setLoading] = useState(false),
    [cid, setCid] = useState<string>(),
    [q, setQ] = useState(""),
    [pickedEvidence, setPickedEvidence] = useState<{ messageId: string; id: string } | null>(null),
    [messages, setMessages] = useState<Msg[]>([WELCOME]),
    [agentLoading, setAgentLoading] = useState(false),
    [watchSaving, setWatchSaving] = useState(false),
    [watchedCode, setWatchedCode] = useState<string>(),
    [deskReady, setDeskReady] = useState(false),
    [watchChips, setWatchChips] = useState<WatchChip[]>([]);
  const [nameMatches, setNameMatches] = useState<{ fundCode: string; name: string; fundType?: string }[]>([]);
  const agentRef = useRef<HTMLElement>(null);
  const requestSeq = useRef(0);
  const stopRef = useRef(false);
  const runIdRef = useRef("");
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null);
  const [cancelRunId, setCancelRunId] = useState("");
  /** Loads profile and NAV together so the detail view never mixes two funds. */
  const loadFund = useCallback(async (id: string) => {
    const seq = ++requestSeq.current;
    const { startDate, endDate } = currentNavWindow("1y");
    setCode(id);
    setFund(null);
    setPoints([]);
    setLoading(true);
    setNotice("正在读取基金资料与历史净值…");
    try {
      const [a, navPoints] = await Promise.all([
          fetch(`${API}/api/v1/funds/${id}`),
          requestFreshNavPoints(id, startDate, endDate),
        ]),
        profile = await readJsonResponse<{
          message?: string;
          msg?: string;
          data?: Fund;
        }>(a);
      if (seq !== requestSeq.current) return;
      if (!a.ok)
        throw Error(profile.message ?? profile.msg ?? "基金资料暂不可用");
      const loadedFund=profile.data ?? (profile as Fund);
      setFund(loadedFund);
      setPoints(navPoints);
      setNavRange("1y");
      setNotice(navPoints.at(-1) ? `已更新至 ${navPoints.at(-1)?.navDate}` : "已加载基金资料，这个区间没有净值");
    } catch (x) {
      if (seq !== requestSeq.current) return;
      setNotice(x instanceof Error ? x.message : "查询失败");
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);
  const changeNavRange = async (nextRange: NavRange) => {
    if (!fund || nextRange === navRange || navLoading) return;
    const seq = ++requestSeq.current;
    const fundCode = fund.fundCode;
    const previousRange = navRange;
    const { startDate, endDate } = currentNavWindow(nextRange);
    setNavRange(nextRange);
    setNavLoading(true);
    setNotice(`正在加载${NAV_RANGES.find(option=>option.value===nextRange)?.label}净值…`);
    try {
      const nextPoints = await requestFreshNavPoints(fundCode, startDate, endDate);
      if (seq !== requestSeq.current) return;
      setPoints(nextPoints);
      setNotice(`已切换至${NAV_RANGES.find(option=>option.value===nextRange)?.label}走势`);
    } catch (x) {
      if (seq !== requestSeq.current) return;
      setNavRange(previousRange);
      setNotice(x instanceof Error ? x.message : "区间切换失败");
    } finally {
      if (seq === requestSeq.current) setNavLoading(false);
    }
  };
  const sync = async (e?: FormEvent) => {
    e?.preventDefault();
    const query = code.trim();
    if (/^\d{1,6}$/.test(query)) {
      setNameMatches([]);
      await loadFund(query.padStart(6, "0"));
      return;
    }
    if (!query) return setNotice("请输入基金名称或 6 位代码");
    setLoading(true);
    setNotice("正在按名称查找…");
    try {
      const hits = await api<{ fundCode: string; name: string; fundType?: string }[]>(`/api/v1/funds?q=${encodeURIComponent(query)}`);
      setNameMatches(hits);
      setNotice(hits.length ? `找到 ${hits.length} 只基金，请选择一只` : "没有找到名称匹配的基金");
    } catch (x) {
      setNameMatches([]);
      setNotice(x instanceof Error ? x.message : "名称查找失败");
    } finally {
      setLoading(false);
    }
  };
  /** Restores the fund and the research thread after the page unmounts. */
  useEffect(() => {
    const saved = readResearchDesk();
    if (saved) {
      setCode(saved.code ?? "");
      setFund(saved.fund ?? null);
      setPoints(Array.isArray(saved.points) ? saved.points : []);
      setNavRange(saved.navRange ?? "1y");
      setCid(saved.cid);
      setQ(saved.q ?? "");
      setMessages(saved.messages);
      setWatchedCode(saved.watchedCode);
    }
    setDeskReady(true);
  }, []);
  useEffect(() => {
    if (!deskReady) return;
    writeResearchDesk({ code, fund, points, navRange, cid, q, messages, watchedCode });
  }, [deskReady, code, fund, points, navRange, cid, q, messages, watchedCode]);
  /** Uses the signed-in watchlist, then this device's guest list, as the start shortcuts. */
  useEffect(() => {
    let cancelled = false;
    const chipsFrom = async (codes: string[]) => {
      const unique = [...new Set(codes.filter((item) => /^\d{6}$/.test(item)))].slice(0, 12);
      const loaded = await Promise.all(unique.map(async (item) => {
        try {
          const profile = await api<Fund>(`/api/v1/funds/${item}`);
          return { code: item, name: profile.name || item };
        } catch {
          return { code: item, name: item };
        }
      }));
      if (!cancelled) setWatchChips(loaded);
    };
    void (async () => {
      try {
        const groups = await api<{ items?: { fundCode: string | { value: string } }[] }[]>("/api/v1/watchlists");
        const codes = groups.flatMap((group) => (group.items ?? []).map((item) => watchCodeOf(item.fundCode)));
        await chipsFrom(codes);
      } catch {
        await chipsFrom(guestWatch());
      }
    })();
    return () => { cancelled = true; };
  }, []);
  /** A watchlist research link opens with its fund details already loaded. */
  useEffect(() => {
    const pendingQuestion = sessionStorage.getItem("fundpilot.pendingQuestion");
    const pendingFund = sessionStorage.getItem("fundpilot.pendingFund");
    if (pendingQuestion) {
      setQ(pendingQuestion);
      sessionStorage.removeItem("fundpilot.pendingQuestion");
    }
    const requestedCode = (new URLSearchParams(window.location.search).get("code") || pendingFund || "")
      .replace(/\D/g, "")
      .slice(0, 6);
    if (pendingFund) sessionStorage.removeItem("fundpilot.pendingFund");
    if (!requestedCode) return;
    const timer = window.setTimeout(() => {
      void loadFund(requestedCode.padStart(6, "0"));
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadFund]);
  /** Sends the current fund identity with the question to avoid ambiguous AI research. */
  const cancelActiveRun = async (runId: string) => {
    try {
      await api(`/api/v1/agent/runs/${runId}/cancel`, { method: "POST" });
      setCancelRunId("");
      setNotice(stopNotice(false));
    } catch {
      setCancelRunId(runId);
      setNotice(stopNotice(true));
    }
  };
  const stopGeneration = async () => {
    stopRef.current = true;
    const runId = runIdRef.current;
    if (!runId) { setNotice(stopNotice(false)); return; }
    try { await readerRef.current?.cancel(); } catch { /* closing the stream is expected */ }
    await cancelActiveRun(runId);
  };
  const ask = async (e?: FormEvent, preset?: string) => {
    e?.preventDefault();
    const display = (preset ?? q).trim();
    if (!display || agentLoading) return;
    if (!hasFreshAccess() && !(await restoreSession())) {
      if (fund) sessionStorage.setItem("fundpilot.pendingFund", fund.fundCode);
      sessionStorage.setItem("fundpilot.pendingQuestion", display);
      setQ(display);
      const next = encodeURIComponent(`${window.location.pathname}${window.location.search}`);
      location.href = `/login?next=${next}`;
      return;
    }
    const text = fund ? `研究对象：${fund.name}（${fund.fundCode}）\n问题：${display}` : display;
    const turnId = crypto.randomUUID();
    setMessages((x) => [
      ...x,
      { id: `${turnId}-user`, role: "user", content: display },
      { id: turnId, role: "assistant", content: "", activity: { status: "running", steps: [] } },
    ]);
    setQ("");
    setAgentLoading(true);
    setCancelRunId("");
    stopRef.current = false;
    runIdRef.current = "";
    setNotice("FundPilot 正在分析…");
    const updateTurn = (update: (message: Msg) => Msg) =>
      setMessages((current) => current.map((message) => message.id === turnId ? update(message) : message));
    let streaming = false;
    const appendStep = (text: string, failed = false) =>
      updateTurn((message) => ({
        ...message,
        activity: {
          status: failed ? "failed" : (message.activity?.status ?? "running"),
          steps: [...(message.activity?.steps ?? []), { text, failed }],
        },
      }));
    try {
      // Refresh once before protected calls so an expired browser session can recover.
      if (!hasFreshAccess() && !(await restoreSession())) {
        const next = encodeURIComponent(`${window.location.pathname}${window.location.search}`);
        location.href = `/login?next=${next}`;
        return;
      }
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
      const reader = r.body.getReader();
      readerRef.current = reader;
      const decoder = new TextDecoder();
      let buffer = "",
        sawVerify = false;
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
          if (event.type === "run.started") {
            runIdRef.current = String(event.runId ?? "");
            if (stopRef.current && runIdRef.current) {
              void cancelActiveRun(runIdRef.current);
              void reader.cancel().catch(() => undefined);
            } else appendStep("开始整理资料");
          } else if (stopRef.current) {
            continue;
          } else if (event.type === "tool.started")
            appendStep(`正在调用：${label(data.toolName)}`);
          else if (event.type === "tool.completed")
            appendStep(`${label(data.toolName)}完成 · ${data.durationMs}ms · ${data.evidenceIds?.length ?? 0} 条证据`);
          else if (event.type === "tool.failed")
            appendStep(`${label(data.toolName)}失败 · ${data.errorCode}`, true);
          else if (event.type === "evidence.verifying") {
            sawVerify = true;
            appendStep("正在核对引用");
          }
          else if (event.type === "run.failed")
            throw Error(String(data.message ?? data.errorCode ?? "这次没有完成"));
          else if (event.type === "answer.delta") {
            const chunk = String(data ?? "");
            updateTurn((message) => {
              const next = acceptDelta(stopRef.current, streaming ? message.content : "", chunk);
              return { ...message, content: next };
            });
            if (!stopRef.current) streaming = true;
          } else if (stopRef.current) {
            continue;
          } else if (event.type === "answer.completed") {
            const answer = data.answer,
              runId = String(event.runId ?? data.runId ?? ""),
              evidence = Array.isArray(data.evidence) ? data.evidence as Evidence[] : [];
            updateTurn((message) => ({
              ...message,
              content: answer,
              runId,
              evidence,
              evidenceChecked: sawVerify && evidence.length > 0,
              activity: {
                status: "completed",
                steps: [...(message.activity?.steps ?? []), { text: "回答生成完成" }],
              },
            }));
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
            setNotice("分析完成 · 已进行证据校验");
          }
        }
      }
    } catch (x) {
      if (stopRef.current) {
        updateTurn((message) => ({
          ...message,
          activity: { status: "failed", steps: [...(message.activity?.steps ?? []), { text: "已停止" }] },
        }));
        return;
      }
      const readable = readableFailure(x instanceof Error ? x.message : "未知错误");
      updateTurn((message) => ({
        ...message,
        content: message.content || `这次没有完成：${readable}`,
        activity: {
          status: "failed",
          steps: [...(message.activity?.steps ?? []), { text: readable, failed: true }],
        },
      }));
      setNotice(streaming ? `回答已显示，但${readable}` : readable);
    } finally {
      readerRef.current = null;
      setAgentLoading(false);
    }
  };
  /** Saves directly to the signed-in watchlist, with device storage as a guest fallback. */
  const add = async () => {
    if (!fund) return;
    if(watchedCode===fund.fundCode){setNotice(`${fund.name} 已经在你的自选中`);return;}
    setWatchSaving(true);
    try{
      type WatchGroup={groupId:string;displayName:string;items?:{fundCode:string|{value:string}}[]};
      let groups=await api<WatchGroup[]>('/api/v1/watchlists');
      if(!groups.length){const created=await api<WatchGroup>('/api/v1/watchlists',{method:'POST',body:JSON.stringify({name:'默认分组'})});groups=[created];}
      const existing=groups.find(group=>group.items?.some(item=>(typeof item.fundCode==='string'?item.fundCode:item.fundCode.value)===fund.fundCode));
      if(existing){setWatchedCode(fund.fundCode);setNotice(`${fund.name} 已经在“${existing.displayName}”中`);return;}
      await api(`/api/v1/watchlists/${groups[0].groupId}/items`,{method:'POST',body:JSON.stringify({fundCode:fund.fundCode})});
      setWatchedCode(fund.fundCode);
      setNotice(`${fund.name} 已加入“${groups[0].displayName}”`);
    }catch{
      const codes=guestWatch();
      saveGuestWatch([...codes,fund.fundCode]);
      setWatchedCode(fund.fundCode);
      setNotice(`${fund.name} 已保存在此设备；登录后可同步`);
    }finally{setWatchSaving(false);}
  };
  const latest = points.at(-1);
  const change = points.length > 1 ? ((points.at(-1)!.unitNav - points[0].unitNav) / points[0].unitNav) * 100 : undefined;
  return (
    <AppShell notice={notice || undefined} title="开始研究" kicker="FUND RESEARCH">
      <form className="search" onSubmit={sync}>
        <span>⌕</span>
        <input
          aria-label="基金名称或代码"
          placeholder="输入基金名称或 6 位代码"
          value={code}
          onChange={(e) => {
            setCode(e.target.value.slice(0, 40));
            setNameMatches([]);
          }}
        />
        <button disabled={loading}>{loading ? "查询中…" : "查询基金"}</button>
      </form>
      {nameMatches.length > 0 && <div className="name-matches">{nameMatches.map(hit => <button type="button" key={hit.fundCode} onClick={() => { setNameMatches([]); setCode(hit.fundCode); void loadFund(hit.fundCode); }}><b>{hit.name}</b><span>{hit.fundCode}</span><small>{hit.fundType || "基金"}</small></button>)}</div>}
      <p className="search-help">{watchChips.length ? "可以输入名称，或点下面的自选直接查看。" : "可以输入基金名称或 6 位代码。"} <a href="/compare">对比 2 到 3 只基金</a></p>
      {deskReady && !fund && (
        <section className="start-panel">
          <h2>查一只基金，再问它的表现和风险</h2>
          <p>{watchChips.length ? "先从自选里选一只，看到净值和走势后，再向研究助手提问。" : "自选里还没有基金。可以先到「我的自选」添加，或直接输入 6 位代码。"}</p>
          {watchChips.length > 0 && (
            <div className="example-row">
              {watchChips.map((example) => (
                <button key={example.code} type="button" disabled={loading} onClick={() => void loadFund(example.code)}>
                  {example.code} {example.name}
                </button>
              ))}
            </div>
          )}
        </section>
      )}
      {fund && (
        <>
          <section className="fund-bar">
            <div>
              <h2>{fund.name}</h2>
              <div className="fund-meta">
                <span>{fund.fundCode}</span>
                {fund.fundType && <span>{fund.fundType}</span>}
                {fund.fundManager && <span>基金经理 {fund.fundManager}</span>}
              </div>
            </div>
            <div className="fund-quote">
              <b className={change == null ? "" : change >= 0 ? "up" : "down"}>{latest ? latest.unitNav.toFixed(4) : "—"}</b>
              <small>{latest ? `净值日期 ${latest.navDate}${change == null ? "" : ` · ${change >= 0 ? "+" : ""}${change.toFixed(2)}%`}` : "这个区间没有净值"}</small>
            </div>
            <div className="fund-actions">
              <button type="button" className="ghost" onClick={() => void add()} disabled={watchSaving}>{watchSaving ? "正在加入…" : watchedCode === fund.fundCode ? "已加入自选" : "加入自选"}</button>
              <button type="button" className="primary" onClick={() => agentRef.current?.scrollIntoView({ behavior: "smooth", block: "start" })}>问问这只基金</button>
            </div>
          </section>
          <section className="grid">
            <article className="card chart">
              <p>净值走势</p>
              <h3>单位净值</h3>
              <small className="data-caption">这是区间单位净值变化，不是持有收益。日期以图表为准。</small>
              <Chart points={points} range={navRange} loading={navLoading} onRangeChange={changeNavRange} />
            </article>
            <article className="card profile">
              <p>基金资料</p>
              <h3>{fund.name}</h3>
              <dl>
                <div><dt>基金代码</dt><dd>{fund.fundCode}</dd></div>
                <div><dt>基金类型</dt><dd>{fund.fundType ?? "—"}</dd></div>
                <div><dt>基金经理</dt><dd>{fund.fundManager ?? "—"}</dd></div>
                <div><dt>管理人</dt><dd>{fund.managementCompany ?? "—"}</dd></div>
                <div><dt>成立日期</dt><dd>{fund.establishedDate ?? "—"}</dd></div>
              </dl>
              <button onClick={() => sync()} disabled={loading}>{loading ? "更新中…" : "更新数据"}</button>
            </article>
          </section>
        </>
      )}
      <section className="agent" ref={agentRef}>
        <div className="agent-title">
          <i className="mascot" aria-hidden="true" />
          <div>
            <p>研究助手</p>
            <h3>{fund ? `问问 ${fund.name}` : "先查一只基金"}</h3>
          </div>
        </div>
        <div className="messages">
          {deskReady && messages.map((m) => {
            const picked = pickedEvidence?.messageId === m.id ? m.evidence?.find((item) => item.evidenceId.toLowerCase() === pickedEvidence.id.toLowerCase()) : undefined;
            return (
            <div key={m.id} className={m.role}>
              {m.role === "user" && <i className="you" aria-hidden="true">你</i>}
              <div className="bubble">
                {m.role === "assistant" ? (
                  <>
                    {m.content ? <RichText text={m.content} onEvidence={(id) => setPickedEvidence({ messageId: m.id, id })} /> : m.activity?.status === "running" ? null : <div className="answer-placeholder">正在分析并组织回答…</div>}
                    {pickedEvidence?.messageId === m.id && <EvidenceCard item={picked} onClose={() => setPickedEvidence(null)} />}
                    <RunActivityPanel message={m} />
                  </>
                ) : (
                  m.content
                )}
              </div>
            </div>
            );
          })}
        </div>
        {fund && (
          <div className="suggest-row">
            {SUGGESTED.map((prompt) => (
              <button key={prompt} type="button" disabled={agentLoading} onClick={() => void ask(undefined, prompt)}>{prompt}</button>
            ))}
          </div>
        )}
        <form onSubmit={ask}>
          <input
            disabled={agentLoading}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={fund ? `问问 ${fund.name} 的表现与风险` : "先查询基金，再开始研究"}
          />
          {agentLoading ? <button type="button" onClick={() => void stopGeneration()}>停止</button> : <button>↑</button>}
        </form>
        {cancelRunId && !agentLoading && <button type="button" className="secondary" onClick={() => void cancelActiveRun(cancelRunId)}>重试取消</button>}
        <small>
          {fund ? `正在研究：${fund.name}（${fund.fundCode}）` : "请先查询一只基金。"} 回答仅供研究参考，数据日期以依据为准。
        </small>
      </section>
    </AppShell>
  );
}
