"use client";
import { FormEvent, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from "react";
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
  activity?: {
    status: "running" | "completed" | "failed";
    steps: { text: string; failed?: boolean }[];
  };
};
type Tab = "overview" | "watchlist" | "agent" | "insights" | "knowledge";
/**
 * 把一段文字里的加粗、行内代码和证据编号拆成可显示的片段。
 * 标记不成对时按原文留下；证据编号对不上时不报错，只显示原来的字。
 */
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
const evidencePattern = "ev-[a-z0-9-]{12,}";
/**
 * 判断一行是不是带可选证据编号的 Markdown 表格行。
 * 空行、普通段落和对不齐的竖线都会返回 false，调用方会把它当成普通段落，不会抛错。
 */
const isTableLine = (line: string) =>
  new RegExp(`^\\|.*\\|(?:\\s+${evidencePattern})?\\s*$`, "i").test(line.trim());
/**
 * 去掉行尾证据编号和两侧竖线，拆出单元格文本。
 * 没有竖线时得到只含整行的数组；空白单元格保留为空字符串，不视为校验失败。
 */
const tableCells = (line: string) =>
  line
    .trim()
    .replace(new RegExp(`\\s+${evidencePattern}\\s*$`, "i"), "")
    .replace(/^\||\|$/g, "")
    .split("|")
    .map((cell) => cell.trim());
/**
 * 把助手回答里的标题、列表、表格和引用渲染成块。
 * 空行会被跳过；表格不足一行时不画出空表。无法识别的行按段落显示，解析过程不抛错。
 */
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

/**
 * 把这一轮的工具步骤和运行摘要折在回答下面，默认收起。
 * 没有步骤也没有运行编号时不渲染。摘要接口还没回来时显示“正在同步”；摘要明确失败时显示“暂不可用”，不把整段回答清掉。
 */
function RunActivityPanel({ message }: { message: Msg }) {
  if (!message.activity && !message.runId) return null;
  const activity = message.activity,
    summary = message.runSummary,
    accounting = summary?.agentOps,
    lastStep = activity?.steps.at(-1)?.text,
    compactLabel =
      activity?.status === "running"
        ? `正在运行${lastStep ? ` · ${lastStep}` : ""}`
        : activity?.status === "failed"
          ? `运行未完成 · ${activity.steps.length} 个步骤`
          : `运行已完成 · ${activity?.steps.length ?? 0} 个步骤${summary ? ` · ${summary.evidenceCount} 条证据` : ""}`;
  return (
    <details className={`agent-activity ${activity?.status ?? "completed"}`}>
      <summary>
        <span>{compactLabel}</span>
      </summary>
      {activity && activity.steps.length > 0 && (
        <ol className="agent-trace">
          {activity.steps.map((step, i) => (
            <li
              key={`${step.text}-${i}`}
              className={
                step.failed
                  ? "failed"
                  : activity.status === "running" && i === activity.steps.length - 1
                    ? "running"
                    : ""
              }
            >
              <span>
                {i + 1}. {step.text}
              </span>
            </li>
          ))}
        </ol>
      )}
      {message.summaryUnavailable && <div className="run-summary pending">运行摘要暂不可用</div>}
      {message.runId && !message.summaryUnavailable && !summary && (
        <div className="run-summary pending">正在同步运行摘要…</div>
      )}
      {summary && accounting && (
        <div className="run-summary">
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
          {accounting.available ? (accounting.settled ? "已结算" : "处理中") : "暂不可用"}
          {summary.sources.length > 0 && ` · 来源 ${summary.sources.slice(0, 3).join("、")}`}
        </small>
        {summary.adminConsoleUrl && (
          <a href={summary.adminConsoleUrl} target="_blank" rel="noreferrer">
            管理员查看 AgentOps 详情 →
          </a>
        )}
        </div>
      )}
    </details>
  );
}

/**
 * 按本机日历从今天往回推一个月、三个月、半年或一年，得到净值查询的起止日期。
 * 不认识的区间不会抛错，起止都会落在今天，图表会因为点数不足显示空态。
 */
function currentNavWindow(range: NavRange = "1y") {
  const end = new Date();
  const start = new Date(end);
  if (range === "1m") start.setMonth(start.getMonth() - 1);
  if (range === "3m") start.setMonth(start.getMonth() - 3);
  if (range === "6m") start.setMonth(start.getMonth() - 6);
  if (range === "1y") start.setFullYear(start.getFullYear() - 1);
  /**
   * 把日期收成 YYYY-MM-DD。
   * 无效日期会带上 NaN，随后的净值请求会因参数不合法返回 4xx 或空结果，这里不单独拦截。
   */
  const format = (date: Date) =>
    `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  return { startDate: format(start), endDate: format(end) };
}

/**
 * 按净值日期从早到晚排序，避免数据源有时正序、有时倒序时把曲线画反。
 * 空数组得到空数组；日期缺失时按空字符串比较，不会中断绘图。
 */
function chronologicalNavPoints(points: Nav[]) {
  return [...points].sort((left, right) =>
    left.navDate.localeCompare(right.navDate),
  );
}

/**
 * 按基金代码和日期区间拉取单位净值。
 * HTTP 非 2xx 时抛出正文里的 message 或 msg，都没有时提示历史净值不可用；正文不是 JSON 时由读取函数抛错。没有 items 时返回空数组，交给图表显示空态。
 */
async function requestNavPoints(fundCode: string, startDate: string, endDate: string) {
  const response = await fetch(`${API}/api/v1/funds/${fundCode}/nav?startDate=${startDate}&endDate=${endDate}`);
  const history = await readJsonResponse<{message?:string;msg?:string;data?:{items?:Nav[]};items?:Nav[]}>(response);
  if (!response.ok) throw Error(history.message ?? history.msg ?? "历史净值暂不可用");
  return chronologicalNavPoints((history.data ?? history).items ?? []);
}

/**
 * 先取整段净值，若最新日期还没到区间终点，再补请求缺口。
 * 第一段为空时直接返回空数组。补段请求 4xx/5xx 或非 JSON 时整次失败，不会只返回已经拿到的前半段。
 */
async function requestFreshNavPoints(fundCode: string, startDate: string, endDate: string) {
  const points = await requestNavPoints(fundCode, startDate, endDate);
  const latestDate = points.at(-1)?.navDate;
  if (!latestDate || latestDate >= endDate) return points;
  const nextDate = new Date(`${latestDate}T00:00:00`);
  nextDate.setDate(nextDate.getDate() + 1);
  const tailStart = `${nextDate.getFullYear()}-${String(nextDate.getMonth() + 1).padStart(2, "0")}-${String(nextDate.getDate()).padStart(2, "0")}`;
  if (tailStart > endDate) return points;
  const tail = await requestNavPoints(fundCode, tailStart, endDate);
  return chronologicalNavPoints([
    ...new Map([...points, ...tail].map((point) => [point.navDate, point])).values(),
  ]);
}

/**
 * 画出近 1 个月到近 1 年的区间按钮。
 * 净值还在加载时按钮禁用，避免连点打出重叠请求；点击本身不处理接口错误。
 */
function NavRangeSelector({
  range,
  loading,
  onRangeChange,
}: {
  range: NavRange;
  loading: boolean;
  onRangeChange: (range: NavRange) => void;
}) {
  return (
    <div className="chart-ranges" aria-label="净值时间范围">
      {NAV_RANGES.map((option) => (
        <button
          type="button"
          key={option.value}
          className={range === option.value ? "active" : ""}
          disabled={loading}
          aria-pressed={range === option.value}
          onClick={() => onRangeChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/**
 * 用抽样后的单位净值画走势。点数不足两天时不画线。
 * 加载中和尚未查询共用同一块空图，只靠文案区分；接口 4xx/5xx 由外层顶栏承担，本组件没有单独的错误块。净值全相同或为 0 时涨跌幅可能得到 NaN 或无穷，仍会画平线。
 */
function Chart({
  points,
  range,
  loading,
  onRangeChange,
}: {
  points: Nav[];
  range: NavRange;
  loading: boolean;
  onRangeChange: (range: NavRange) => void;
}) {
  const p = useMemo(
    () =>
      points.filter(
        (_, i) =>
          i % Math.max(1, Math.ceil(points.length / 120)) === 0 ||
          i === points.length - 1,
      ),
    [points],
  );
  if (p.length < 2)
    return (
      <>
        <div className="empty-chart">
          {loading ? "正在加载区间净值…" : "查询基金后，这里会展示真实单位净值走势。"}
        </div>
        <NavRangeSelector range={range} loading={loading} onRangeChange={onRangeChange} />
      </>
    );
  const v = p.map((x) => +x.unitNav),
    min = Math.min(...v),
    max = Math.max(...v),
    r = max - min || 1,
    coordinates = p.map((x, i) => ({
      x: (i / (p.length - 1)) * 100,
      y: 90 - ((+x.unitNav - min) / r) * 72,
    })),
    d = coordinates.reduce((path, point, i, all) => {
      if (i === 0) return `M ${point.x.toFixed(2)} ${point.y.toFixed(2)}`;
      const previous = all[i - 1], before = all[i - 2] ?? previous, after = all[i + 1] ?? point;
      const c1x = previous.x + (point.x - before.x) / 6;
      const lowerY = Math.min(previous.y, point.y), upperY = Math.max(previous.y, point.y);
      const c1y = Math.max(lowerY, Math.min(upperY, previous.y + (point.y - before.y) / 6));
      const c2x = point.x - (after.x - previous.x) / 6;
      const c2y = Math.max(lowerY, Math.min(upperY, point.y - (after.y - previous.y) / 6));
      return `${path} C ${c1x.toFixed(2)} ${c1y.toFixed(2)}, ${c2x.toFixed(2)} ${c2y.toFixed(2)}, ${point.x.toFixed(2)} ${point.y.toFixed(2)}`;
    }, ""),
    change = ((v.at(-1)! - v[0]) / v[0]) * 100;
  return (
    <>
      <div className="chart-meta">
        <b>{v.at(-1)?.toFixed(4)}</b>
        <span className={change >= 0 ? "up" : "down"}>
          {change >= 0 ? "+" : ""}
          {change.toFixed(2)}% · 区间单位净值变化
        </span>
      </div>
      <svg
        className="real-chart"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
      >
        <defs>
          <linearGradient id="nav-area" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#2d78ed" stopOpacity=".22" />
            <stop offset="100%" stopColor="#2d78ed" stopOpacity=".025" />
          </linearGradient>
        </defs>
        {[18, 42, 66, 90].map((y) => (
          <line key={y} x1="0" x2="100" y1={y} y2={y} className="chart-grid-line" />
        ))}
        <path d={`${d} L 100 96 L 0 96 Z`} fill="url(#nav-area)" />
        <path
          d={d}
          fill="none"
          stroke="#286fda"
          strokeWidth="1.6"
          vectorEffect="non-scaling-stroke"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <div className="dates">
        <span>{p[0].navDate}</span>
        <span>{p[Math.floor(p.length / 2)].navDate}</span>
        <span>{p.at(-1)?.navDate}</span>
      </div>
      <NavRangeSelector range={range} loading={loading} onRangeChange={onRangeChange} />
    </>
  );
}
/**
 * 首页：按代码查基金和净值，并把当前基金交给研究助手。
 * 查询进行时按钮显示“查询中…”。资料或净值 4xx/5xx 只改顶栏，上一只基金的资料和曲线会留在页面上。没有独立的基金对比页，比较发生在助手工具或研究任务里。
 */
export default function Home() {
  const [tab, setTab] = useState<Tab>("overview"),
    [code, setCode] = useState(''),
    [fund, setFund] = useState<Fund | null>(null),
    [points, setPoints] = useState<Nav[]>([]),
    [navRange, setNavRange] = useState<NavRange>("1y"),
    [navLoading, setNavLoading] = useState(false),
    [notice, setNotice] = useState("实时研究环境已连接"),
    [loading, setLoading] = useState(false),
    [cid, setCid] = useState<string>(),
    [q, setQ] = useState("这只基金适合什么风险偏好的投资者？"),
    [messages, setMessages] = useState<Msg[]>([
      {
        id: "welcome",
        role: "assistant",
        content:
          "你好，我是 FundPilot。输入基金代码，我会结合净值、历史表现与知识资料为你解读。",
      },
    ]),
    [agentLoading, setAgentLoading] = useState(false),
    [watchSaving, setWatchSaving] = useState(false),
    [watchedCode, setWatchedCode] = useState<string>();
  const agentRef = useRef<HTMLElement>(null);
  /**
   * 同时读取基金资料和近一年净值，避免详情和曲线来自两只基金。
   * 资料非 2xx、净值 4xx/5xx、非 JSON 或网络失败时只改顶栏为接口文案或“查询失败”，不清空上一只基金。空代码也会发出请求。
   */
  const loadFund = useCallback(async (id: string) => {
    const { startDate, endDate } = currentNavWindow("1y");
    setCode(id);
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
      if (!a.ok)
        throw Error(profile.message ?? profile.msg ?? "基金资料暂不可用");
      const loadedFund = profile.data ?? (profile as Fund);
      setFund(loadedFund);
      setPoints(navPoints);
      setNavRange("1y");
      setTab("overview");
      setNotice("已加载公开基金资料 · 数据仅供研究参考");
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "查询失败");
    } finally {
      setLoading(false);
    }
  }, []);
  /**
   * 切换净值区间并重新请求。没有当前基金、区间没变或上一请求还在进行时直接返回。
   * 新区间 4xx/5xx 或网络失败时退回原来的区间和曲线，顶栏显示接口文案或“区间切换失败”。
   */
  const changeNavRange = async (nextRange: NavRange) => {
    if (!fund || nextRange === navRange || navLoading) return;
    const previousRange = navRange;
    const { startDate, endDate } = currentNavWindow(nextRange);
    setNavRange(nextRange);
    setNavLoading(true);
    setNotice(`正在加载${NAV_RANGES.find((option) => option.value === nextRange)?.label}净值…`);
    try {
      setPoints(await requestFreshNavPoints(fund.fundCode, startDate, endDate));
      setNotice(`已切换至${NAV_RANGES.find((option) => option.value === nextRange)?.label}走势`);
    } catch (x) {
      setNavRange(previousRange);
      setNotice(x instanceof Error ? x.message : "区间切换失败");
    } finally {
      setNavLoading(false);
    }
  };
  /**
   * 把输入框里的代码左侧补零到 6 位后查询。
   * 空输入会变成 000000 再请求，失败时由查询逻辑写顶栏，不会在这里做格式校验。
   */
  const sync = async (e?: FormEvent) => {
    e?.preventDefault();
    await loadFund(code.padStart(6, "0"));
  };
  /**
   * 地址上带了 code 时，打开页面就加载那只基金。
   * 参数里没有数字时不请求；加载失败的提示与手动查询相同，地址参数本身不会被清空。
   */
  useEffect(() => {
    const requestedCode = new URLSearchParams(window.location.search)
      .get("code")
      ?.replace(/\D/g, "")
      .slice(0, 6);
    if (!requestedCode) return;
    const timer = window.setTimeout(() => {
      void loadFund(requestedCode.padStart(6, "0"));
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadFund]);
  /**
   * 把问题和当前基金名称一起交给流式研究助手。
   * 问题为空白或上一轮还在进行时不发送。未登录、会话恢复失败、建会话 4xx/5xx、流不是 2xx、浏览器没有响应体、事件 JSON 损坏或运行失败事件，都会把这一轮标成失败并在气泡和顶栏写出原因。基金比较只作为工具步骤出现，没有单独的对比空态。
   */
  const ask = async (e: FormEvent) => {
    e.preventDefault();
    if (!q.trim() || agentLoading) return;
    const text = fund ? `研究对象：${fund.name}（${fund.fundCode}）\n问题：${q}` : q;
    const turnId = crypto.randomUUID();
    setMessages((x) => [
      ...x,
      { id: `${turnId}-user`, role: "user", content: text },
      { id: turnId, role: "assistant", content: "", activity: { status: "running", steps: [] } },
    ]);
    setQ("");
    setAgentLoading(true);
    setNotice("FundPilot 正在分析…");
    /**
     * 只改本轮助手消息，避免流式更新串到其它回合。
     * 本轮已经被移除时静默不更新，不抛错。
     */
    const updateTurn = (update: (message: Msg) => Msg) =>
      setMessages((current) =>
        current.map((message) => (message.id === turnId ? update(message) : message)),
      );
    /**
     * 往本轮步骤列表追加一行。failed 为真时把整轮标成未完成，已有回答文字保留。
     */
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
      /**
       * 把工具内部名称换成界面上的中文。
       * 表里没有的名称原样显示，不当成错误。
       */
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
            appendStep("Agent 运行已开始");
          else if (event.type === "tool.started")
            appendStep(`正在调用：${label(data.toolName)}`);
          else if (event.type === "tool.completed")
            appendStep(`${label(data.toolName)}完成 · ${data.durationMs}ms · ${data.evidenceIds?.length ?? 0} 条证据`);
          else if (event.type === "tool.failed")
            appendStep(`${label(data.toolName)}失败 · ${data.errorCode}`, true);
          else if (event.type === "evidence.verifying")
            appendStep("正在校验证据引用");
          else if (event.type === "run.failed")
            throw Error(`${data.message}（${data.errorCode}）`);
          else if (event.type === "answer.delta") {
            const chunk = String(data ?? "");
            updateTurn((message) => ({ ...message, content: streaming ? message.content + chunk : chunk }));
            streaming = true;
          } else if (event.type === "answer.completed") {
            const answer = data.answer,
              runId = String(event.runId ?? data.runId ?? "");
            updateTurn((message) => ({
              ...message,
              content: answer,
              runId,
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
      const detail = x instanceof Error ? x.message : "未知错误";
      updateTurn((message) => ({
        ...message,
        content: message.content || `本次请求未完成：${detail}`,
        activity: {
          status: "failed",
          steps: [...(message.activity?.steps ?? []), { text: `执行失败：${detail}`, failed: true }],
        },
      }));
      setNotice(`Agent 请求失败 · ${detail}`);
    } finally {
      setAgentLoading(false);
    }
  };
  /**
   * 把当前基金写入已登录用户的自选；云端失败时改存本机，登录后还能同步。
   * 还没查到基金时什么都不做。列表、建默认分组或加入接口返回 4xx/5xx 时不会把错误留在顶栏，而是写入本机并提示稍后同步，因此校验失败和未登录看起来一样。
   */
  const add = async () => {
    if (!fund) return;
    if (watchedCode === fund.fundCode) {
      setNotice(`${fund.name} 已经在你的自选中`);
      return;
    }
    setWatchSaving(true);
    try {
      type WatchGroup = {
        groupId: string;
        displayName: string;
        items?: { fundCode: string | { value: string } }[];
      };
      let groups = await api<WatchGroup[]>("/api/v1/watchlists");
      if (!groups.length) {
        const created = await api<WatchGroup>("/api/v1/watchlists", {
          method: "POST",
          body: JSON.stringify({ name: "默认分组" }),
        });
        groups = [created];
      }
      const existing = groups.find((group) =>
        group.items?.some(
          (item) =>
            (typeof item.fundCode === "string" ? item.fundCode : item.fundCode.value) === fund.fundCode,
        ),
      );
      if (existing) {
        setWatchedCode(fund.fundCode);
        setNotice(`${fund.name} 已经在“${existing.displayName}”中`);
        return;
      }
      await api(`/api/v1/watchlists/${groups[0].groupId}/items`, {
        method: "POST",
        body: JSON.stringify({ fundCode: fund.fundCode }),
      });
      setWatchedCode(fund.fundCode);
      setNotice(`${fund.name} 已加入“${groups[0].displayName}”`);
    } catch {
      const codes = guestWatch();
      saveGuestWatch([...codes, fund.fundCode]);
      setWatchedCode(fund.fundCode);
      setNotice(`${fund.name} 已保存在此设备；登录后可同步`);
    } finally {
      setWatchSaving(false);
    }
  };
  const overview = (
    <>
      <section className="hero">
        <div>
          <em>{fund ? '基金详情' : '从一只基金开始'}</em>
          <h2>{fund?.name ?? "查净值、看风险，再做决定"}</h2>
          <p>
            {fund?.fundType ??
              "输入基金名称或 6 位代码，查看公开净值与研究依据。"}
          </p>
          <mark>{fund?.fundCode ?? "基金代码"}</mark>
          {fund?.managementCompany && <mark>{fund.managementCompany}</mark>}
        </div>
        <div className="orb">
          <b>{fund ? '已加载\n公开资料' : '净值\n研究'}</b>
        </div>
      </section>
      <section className="stats">
        <article>
          <small>资料来源</small>
          <b>{fund?.dataSource ?? "第三方基金数据"}</b>
          <em>{fund ? '以详情数据为准' : '查询后展示'}</em>
        </article>
        <article>
          <small>最新单位净值</small>
          <b>{points.at(-1)?.unitNav?.toFixed(4) ?? "—"}</b>
          <em>{points.at(-1)?.navDate ?? "等待查询"}</em>
        </article>
        <article>
          <small>数据截至</small>
          <b>{points.at(-1)?.navDate ?? '—'}</b>
          <em>最新可用净值日</em>
        </article>
        <article>
          <small>基金经理</small>
          <b>{fund?.fundManager ?? '—'}</b>
          <em>{fund ? '产品资料' : '查询后展示'}</em>
        </article>
      </section>
      <section className="grid">
        <article className="card chart">
          <p>NET ASSET VALUE</p>
          <h3>单位净值走势</h3>
          <small className="data-caption">净值变化不等同于实际持有收益；区间以图表日期为准。</small>
          <Chart points={points} range={navRange} loading={navLoading} onRangeChange={changeNavRange} />
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
          <button onClick={() => sync()} disabled={loading}>{loading?'更新中…':'更新数据'}</button>
          <button className="text-button" onClick={() => void add()} disabled={watchSaving}>
            {watchSaving ? "正在加入…" : watchedCode === fund?.fundCode ? "✓ 已加入自选" : "加入自选"}
          </button>
        </article>
      </section>
    </>
  );
  let panel = overview;
  if (tab === "insights")
    panel = (
      <section className="workspace">
        <p>DATA INSIGHTS</p>
        <h2>数据洞察</h2>
        <div className="insight-grid">
          <div>
            <small>当前区间样本</small>
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
        <Chart points={points} range={navRange} loading={navLoading} onRangeChange={changeNavRange} />
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
          aria-label="基金代码"
          value={code}
          onChange={(e) =>
            setCode(e.target.value.replace(/\D/g, "").slice(0, 6))
          }
        />
        <button disabled={loading}>{loading ? "查询中…" : "查询基金"}</button>
      </form>
      <p className="search-help">目前支持 6 位基金代码，例如 000001。查询后可加入自选或继续研究。</p>
      {panel}
      <section className="agent" ref={agentRef}>
        <div className="agent-title">
          <i>✦</i>
          <div>
            <p>FUNDPILOT AGENT</p>
            <h3>和你的基金研究助手聊聊</h3>
          </div>
        </div>
        <div className="messages">
          {messages.map((m) => (
            <div key={m.id} className={m.role}>
              <i>{m.role === "assistant" ? "F" : "你"}</i>
              <div className="bubble">
                {m.role === "assistant" ? (
                  <>
                    {m.content ? <RichText text={m.content} /> : <div className="answer-placeholder">正在分析并组织回答…</div>}
                    <RunActivityPanel message={m} />
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
            placeholder={fund?`问问 ${fund.name} 的表现与风险`:'先查询基金，再开始研究'}
          />
          <button disabled={agentLoading}>{agentLoading ? "…" : "↑"}</button>
        </form>
        <small>
          {fund?`正在研究：${fund.name}（${fund.fundCode}）`:'请先查询一只基金。'} 回答仅供研究参考，数据日期以引用来源为准。
        </small>
      </section>
    </AppShell>
  );
}
