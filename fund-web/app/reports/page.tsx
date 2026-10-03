"use client";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import AppShell from "../../components/AppShell";
import { StatePanel } from "../../components/FinanceUI";
import { api } from "../../lib/session";

type Job = {
  jobId: string;
  runId: string;
  status: string;
  periodStart?: string;
  periodEnd?: string;
};
type Report = { artifactUri: string; content: string; evidenceCount: number };
type Portfolio = { portfolioId: { value: string }; displayName: string };
type Position = { fundCode: string | { value: string } };
type Tx = { confirmDate: string };
type Group = {
  groupId: string;
  displayName: string;
  items: { fundCode: string | { value: string } }[];
};
type Scope = {
  kind: "PORTFOLIO" | "WATCHLIST";
  id: string;
  label: string;
  funds: string[];
  earliest?: string;
};
const ACTIVE = new Set(["RUNNING", "PLAN_RUNNING", "WAITING_APPROVAL"]);
const label: Record<string, string> = {
  SUCCEEDED: "已完成",
  FAILED: "未完成",
  RUNNING: "正在生成",
  PLAN_RUNNING: "正在生成",
  WAITING_APPROVAL: "等待确认",
  CANCELLED: "已取消",
};
const keyOf = (job: Job) => job.jobId || job.runId;
const load = () => api<Job[]>("/api/agent/reports");
const codeOf = (value: string | { value: string }) =>
  typeof value === "string" ? value : value.value;
const monthOf = (date: Date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
const previousMonth = () => {
  const date = new Date();
  date.setDate(1);
  date.setMonth(date.getMonth() - 1);
  return monthOf(date);
};

function ReportBody({ content }: { content: string }) {
  return (
    <>
      {content
        .split("\n")
        .map((line, index) =>
          line.startsWith("# ") ? (
            <h4 key={index}>{line.slice(2)}</h4>
          ) : line.startsWith("## ") ? (
            <h5 key={index}>{line.slice(3)}</h5>
          ) : line.startsWith("- ") ? (
            <p key={index}>• {line.slice(2)}</p>
          ) : line ? (
            <p key={index}>{line}</p>
          ) : null,
        )}
    </>
  );
}

export default function ReportsPage() {
  const requestSeq = useRef(0);
  const activeReport = useRef("");
  const chosenReport = useRef(false);
  const [launching, setLaunching] = useState(false);
  const [jobsLoading, setJobsLoading] = useState(true);
  const [jobsFailed, setJobsFailed] = useState(false);
  const [scopeLoading, setScopeLoading] = useState(true);
  const [scopeFailed, setScopeFailed] = useState(false);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [notice, setNotice] = useState("先选择月份和范围，再生成月报。");
  const [activeId, setActiveId] = useState<string>();
  const [report, setReport] = useState<Report | null>(null);
  const [opening, setOpening] = useState(false);
  const [scopes, setScopes] = useState<Scope[]>([]);
  const [scopeKey, setScopeKey] = useState("");
  const [month, setMonth] = useState(previousMonth);
  const selected = scopes.find(
    (item) => `${item.kind}:${item.id}` === scopeKey,
  );
  const blocker = !month
    ? "请选择月份"
    : !selected
      ? "请选择一个组合或自选"
      : selected.funds.length === 0
        ? selected.kind === "WATCHLIST"
          ? `自选「${selected.label}」里还没有基金`
          : `组合「${selected.label}」还没有持仓`
        : selected.kind === "PORTFOLIO" &&
            selected.earliest &&
            selected.earliest.slice(0, 7) > month
          ? `组合「${selected.label}」在 ${month} 还没有确认交易`
          : "";
  const invalidate = useCallback(() => {
    requestSeq.current++;
  }, []);
  const open = useCallback(async (job: Job) => {
    const id = keyOf(job);
    const seq = ++requestSeq.current;
    activeReport.current = id;
    setReport(null);
    setActiveId(id);
    setOpening(true);
    try {
      const body = await api<Report>(`/api/agent/runs/${job.runId}/report`);
      if (seq !== requestSeq.current) return;
      setReport(body);
      setNotice(
        body.content?.trim()
          ? "月报已打开，就在列表下方。"
          : "这份月报已结束，但还没有可阅读的正文。",
      );
    } catch {
      if (seq !== requestSeq.current) return;
      setReport(null);
      setNotice(
        "这份月报已结束，但正文还没准备好。可以到研究任务里看这次运行的进度。",
      );
    } finally {
      if (seq === requestSeq.current) setOpening(false);
    }
  }, []);
  useEffect(() => {
    load()
      .then((next) => {
        setJobs(next);
        const done = next.find(
          (job) => job.status === "SUCCEEDED" && job.runId,
        );
        if (done && !chosenReport.current) void open(done);
        else if (next.some((job) => ACTIVE.has(job.status)))
          setNotice("月报还在生成，完成后会显示在这里。");
      })
      .catch((e) => {
        setJobsFailed(true);
        setNotice(e instanceof Error ? e.message : "请先登录");
      })
      .finally(() => setJobsLoading(false));
    // 范围分别加载，一份组合读取失败时仍可生成其他组合或自选的月报。
    Promise.allSettled([
      api<Portfolio[]>("/api/v1/portfolios"),
      api<Group[]>("/api/v1/watchlists"),
    ])
      .then(async ([portfolioResult, groupResult]) => {
        const next: Scope[] = [];
        let partial =
          portfolioResult.status === "rejected" ||
          groupResult.status === "rejected";
        if (portfolioResult.status === "fulfilled") {
          const results = await Promise.allSettled(
            portfolioResult.value.map(async (portfolio) => {
              const id = portfolio.portfolioId.value;
              const [positions, transactions] = await Promise.all([
                api<Position[]>(`/api/v1/portfolios/${id}/positions`),
                api<Tx[]>(`/api/v1/portfolios/${id}/transactions`),
              ]);
              return {
                kind: "PORTFOLIO" as const,
                id,
                label: portfolio.displayName,
                funds: positions.map((item) => codeOf(item.fundCode)),
                earliest: transactions
                  .map((item) => item.confirmDate)
                  .sort()[0],
              };
            }),
          );
          for (const result of results) {
            if (result.status === "fulfilled") next.push(result.value);
            else partial = true;
          }
        }
        if (groupResult.status === "fulfilled")
          for (const group of groupResult.value)
            next.push({
              kind: "WATCHLIST",
              id: group.groupId,
              label: group.displayName,
              funds: group.items.map((item) => codeOf(item.fundCode)),
            });
        setScopes(next);
        setScopeFailed(partial && !next.length);
        if (partial && next.length)
          setNotice("部分报告范围暂不可用，可以选择已加载的组合或自选");
        if (next[0]) setScopeKey(`${next[0].kind}:${next[0].id}`);
      })
      .catch(() => setScopeFailed(true))
      .finally(() => setScopeLoading(false));
    return invalidate;
  }, [open, invalidate]);
  useEffect(() => {
    if (!jobs.some((job) => ACTIVE.has(job.status))) return;
    const timer = window.setInterval(() => {
      void load()
        .then((next) => {
          setJobs(next);
          const finished = next.find(
            (job) =>
              ACTIVE.has(
                jobs.find((current) => keyOf(current) === keyOf(job))?.status ??
                  "",
              ) && job.status === "SUCCEEDED",
          );
          if (
            finished &&
            (!activeReport.current || activeReport.current === keyOf(finished))
          )
            void open(finished);
        })
        .catch(() => undefined);
    }, 3000);
    return () => window.clearInterval(timer);
  }, [jobs, open]);
  const launch = async (e: FormEvent) => {
    e.preventDefault();
    if (launching) return;
    if (!selected || blocker) {
      setNotice(blocker || "请选择月份和范围");
      return;
    }
    setLaunching(true);
    try {
      await api("/api/agent/reports/monthly", {
        method: "POST",
        body: JSON.stringify({
          month,
          scopeKind: selected.kind,
          scopeId: selected.id,
        }),
      });
      setNotice(
        `正在按${selected.kind === "WATCHLIST" ? "自选" : "组合"}「${selected.label}」生成 ${month} 的月报。`,
      );
      setJobs(await load());
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "创建月报任务失败");
    } finally {
      setLaunching(false);
    }
  };
  const shown = jobs.find((job) => keyOf(job) === activeId);
  const scopeText = selected
    ? `将使用${selected.kind === "WATCHLIST" ? "自选" : "组合"}「${selected.label}」${selected.funds.length ? `，包含 ${selected.funds.join("、")}` : ""}`
    : "还没有可选的组合或自选";
  return (
    <AppShell notice={notice} title="月度回顾" kicker="REPORTS">
      <section className="workspace reports-page">
        <p>月报</p>
        <h2>选择月份和范围</h2>
        <p className="page-intro">
          生成前先选定月份，以及一份组合或一个自选分组。缺数据时会说明原因，不会发起必然失败的任务。完成后仍在这页阅读正文。
        </p>
        <div className="task-layout">
          <aside>
            <form className="auth-form" onSubmit={launch}>
              <label>
                月份
                <input
                  type="month"
                  value={month}
                  max={monthOf(new Date())}
                  onChange={(e) => setMonth(e.target.value)}
                />
              </label>
              <label>
                范围
                <select
                  value={scopeKey}
                  onChange={(e) => setScopeKey(e.target.value)}
                >
                  <option value="">请选择组合或自选</option>
                  {scopes.map((item) => (
                    <option
                      key={`${item.kind}:${item.id}`}
                      value={`${item.kind}:${item.id}`}
                    >
                      {item.kind === "WATCHLIST" ? "自选" : "组合"} ·{" "}
                      {item.label}
                    </option>
                  ))}
                </select>
              </label>
              <p>
                {scopeText}
                {blocker ? `。${blocker}` : ""}
              </p>
              <button
                disabled={
                  Boolean(blocker) || launching || scopeLoading || scopeFailed
                }
              >
                {launching ? "正在创建…" : "生成月度回顾"}
              </button>
            </form>
            {scopeLoading && <StatePanel title="正在加载报告范围" loading />}
            {scopeFailed && (
              <StatePanel title="报告范围加载失败" error>
                暂时无法读取组合或自选，请稍后刷新。
              </StatePanel>
            )}
          </aside>
          <div>
            {jobsLoading && <StatePanel title="正在加载月报" loading />}
            {jobsFailed && (
              <StatePanel title="月报列表暂时加载失败" error>
                {notice}
              </StatePanel>
            )}
            {!jobsLoading && !jobsFailed && !jobs.length && (
              <StatePanel title="还没有月度回顾">
                选择月份和组合或自选，生成一份可回顾的报告。
              </StatePanel>
            )}
            <ol>
              {jobs.map((job) => (
                <li
                  key={keyOf(job)}
                  className={keyOf(job) === activeId ? "report-selected" : ""}
                >
                  <div className="report-row">
                    <span>
                      {label[job.status] ?? job.status}
                      {job.periodStart
                        ? ` · ${job.periodStart} 至 ${job.periodEnd ?? ""}`
                        : ""}
                    </span>
                    {job.status === "SUCCEEDED" && job.runId && (
                      <button
                        type="button"
                        className="secondary"
                        onClick={() => {
                          chosenReport.current = true;
                          void open(job);
                        }}
                        disabled={opening && activeId === keyOf(job)}
                      >
                        {opening && activeId === keyOf(job)
                          ? "正在打开…"
                          : "查看月报"}
                      </button>
                    )}
                  </div>
                </li>
              ))}
            </ol>
            {opening && <StatePanel title="正在读取月报正文" loading />}
            {shown && !opening && !report?.content?.trim() && (
              <StatePanel title="正文尚未准备好">
                任务已结束，可以稍后重新查看月报。
              </StatePanel>
            )}
            {shown && report?.content?.trim() && (
              <section className="research-report">
                <div>
                  <p>月报正文</p>
                  <h3>
                    {shown.periodStart
                      ? `${shown.periodStart} 至 ${shown.periodEnd ?? ""}`
                      : "月度回顾"}
                  </h3>
                  <span>已汇总 {report.evidenceCount} 项依据</span>
                </div>
                <article>
                  <ReportBody content={report.content} />
                </article>
              </section>
            )}
          </div>
        </div>
      </section>
    </AppShell>
  );
}
