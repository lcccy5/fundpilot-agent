"use client";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import AppShell from "../../components/AppShell";
import { StatePanel } from "../../components/FinanceUI";
import { api } from "../../lib/session";

type Task = { taskKey: string; capabilityType: string; status: string };
type Plan = { planId: string; status: string; tasks: Task[] };
type Run = {
  runId: string;
  status: string;
  executionMode: string;
  routeReason: string;
  planId?: string;
  lastEventSequence: number;
};
type Ev = { sequence: number; eventType: string; payloadJson: string };
type ResearchReport = {
  artifactUri: string;
  content: string;
  evidenceCount: number;
};
type HistoryItem = {
  runId: string;
  status: string;
  message?: string;
  fundCode?: string;
  startedAt?: string;
};
const terminal = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "REJECTED"]);
const runLabel: Record<string, string> = {
  REJECTED: "已拒绝",
  PLAN_RUNNING: "正在分析",
  RUNNING: "正在分析",
  SUCCEEDED: "已完成",
  FAILED: "未完成",
  CANCELLED: "已取消",
  WAITING_APPROVAL: "等待确认",
};
const taskLabel: Record<string, string> = {
  REJECTED: "已拒绝",
  READY: "等待执行",
  RUNNING: "正在执行",
  SUCCEEDED: "已完成",
  FAILED: "未完成",
  PENDING: "等待前置分析",
  CANCELLED: "已停止",
  WAITING_APPROVAL: "等待确认",
};
const taskName: Record<string, string> = {
  ANALYST: "分析与取数",
  RISK_REVIEW: "风险复核",
  SYNTHESIS: "综合回答",
  FUND_METRICS_QUERY: "计算指标",
  FUND_COMPARE: "基金比较",
  PORTFOLIO_SNAPSHOT: "读取组合",
  REPORT_VERIFY: "核对引用",
  REPORT_WRITE: "生成报告",
};

/** 将长任务展示为可读的进度，持续同步服务端状态。 */
export default function RunsPage() {
  const [message, setMessage] = useState("结合我的持仓做一份分析与报告");
  const [run, setRun] = useState<Run | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [events, setEvents] = useState<Ev[]>([]);
  const [report, setReport] = useState<ResearchReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState("提交研究后会自动更新进度。");
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const [question, setQuestion] = useState("");
  const requestSeq = useRef(0);
  const currentRun = useRef("");
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyFailed, setHistoryFailed] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionBusy, setActionBusy] = useState(false);
  /** 读取任务、计划、事件与正文，旧响应不能覆盖新选择。 */
  const refresh = useCallback(async (id: string) => {
    const seq = ++requestSeq.current;
    if (currentRun.current !== id) {
      currentRun.current = id;
      setRun(null);
      setPlan(null);
      setEvents([]);
      setReport(null);
      setDetailLoading(true);
    }
    try {
      const nextRun = await api<Run>(`/api/agent/runs/${id}`);
      const [nextPlan, nextEvents, nextReport] = await Promise.allSettled([
        nextRun.planId
          ? api<Plan>(`/api/agent/runs/${id}/plan`)
          : Promise.resolve(null),
        api<Ev[]>(`/api/agent/runs/${id}/events`, {
          headers: { "Last-Event-ID": "0" },
        }),
        nextRun.status === "SUCCEEDED"
          ? api<ResearchReport>(`/api/agent/runs/${id}/report`)
          : Promise.resolve(null),
      ]);
      // 列表点击和轮询共用序号：旧任务的正文和审批事件不能写入新任务。
      if (seq !== requestSeq.current) return;
      setRun(nextRun);
      setPlan(nextPlan.status === "fulfilled" ? nextPlan.value : null);
      setEvents(nextEvents.status === "fulfilled" ? nextEvents.value : []);
      setReport(nextReport.status === "fulfilled" ? nextReport.value : null);
      if (nextPlan.status === "rejected" || nextEvents.status === "rejected")
        setNotice("部分任务过程暂时无法读取，请刷新进度重试");
    } catch (error) {
      if (seq === requestSeq.current)
        setNotice(
          error instanceof Error ? error.message : "任务详情暂时无法读取",
        );
    } finally {
      if (seq === requestSeq.current) setDetailLoading(false);
    }
  }, []);
  /** 从本地恢复上次选择的任务，刷新后仍可继续查看。 */
  const loadHistory = useCallback(async () => {
    try {
      setHistory(await api<HistoryItem[]>("/api/agent/runs"));
      setHistoryFailed(false);
    } catch {
      setHistoryFailed(true);
    } finally {
      setHistoryLoading(false);
    }
  }, []);
  const invalidate = useCallback(() => {
    requestSeq.current++;
  }, []);
  useEffect(() => {
    api<HistoryItem[]>("/api/agent/runs")
      .then(setHistory)
      .catch(() => setHistoryFailed(true))
      .finally(() => setHistoryLoading(false));
    return invalidate;
  }, [invalidate]);
  useEffect(() => {
    const saved = window.localStorage.getItem(
      "fundpilot:python:lastResearchRun",
    );
    const legacy = window.localStorage.getItem(
      "fundpilot:python:lastResearchRunId",
    );
    let parsed: { runId?: string; question?: string } | null = null;
    try {
      parsed = saved ? JSON.parse(saved) : null;
    } catch {
      parsed = null;
    }
    const id = parsed?.runId || legacy;
    if (!id) return;
    const timer = window.setTimeout(() => {
      if (parsed?.question) setQuestion(parsed.question);
      void refresh(id).catch(() =>
        setNotice("暂时无法恢复上次研究，请重新登录后刷新进度"),
      );
    }, 0);
    return () => window.clearTimeout(timer);
  }, [refresh]);
  /** 只轮询运行中的任务，结束后停止自动请求。 */
  useEffect(() => {
    if (!run || terminal.has(run.status)) return;
    const timer = window.setInterval(() => {
      void refresh(run.runId).catch(() =>
        setNotice("暂时无法更新进度，请稍后刷新"),
      );
    }, 2000);
    return () => window.clearInterval(timer);
  }, [refresh, run]);
  /** 创建异步研究任务，再按实际状态显示进度。 */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (loading) return;
    if (!message.trim()) return setNotice("请描述希望研究的问题");
    setLoading(true);
    setReport(null);
    try {
      const created = await api<Run>("/api/agent/runs", {
        method: "POST",
        body: JSON.stringify({ message }),
      });
      const asked = message.trim();
      setQuestion(asked);
      window.localStorage.setItem(
        "fundpilot:python:lastResearchRun",
        JSON.stringify({ runId: created.runId, question: asked }),
      );
      setNotice("研究任务已创建，正在获取数据与分析。");
      await refresh(created.runId);
      await loadHistory();
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "提交研究任务失败");
    } finally {
      setLoading(false);
    }
  };
  /** 取消当前任务后重新读取最终状态。 */
  const cancel = async () => {
    if (!run || actionBusy) return;
    setActionBusy(true);
    try {
      await api(`/api/agent/runs/${run.runId}/cancel`, { method: "POST" });
      setNotice("研究任务已取消");
      await refresh(run.runId);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "取消任务失败");
    } finally {
      setActionBusy(false);
    }
  };
  /** 按服务端保存的审批编号确认或拒绝，确认后继续执行。 */
  const pendingApproval = () => {
    const requested = events.findLast(
      (ev) => ev.eventType === "approval.requested",
    );
    if (!requested) return null;
    try {
      const payload = JSON.parse(requested.payloadJson) as {
        approvalId?: string;
        parameters?: string;
      };
      return payload.approvalId ? payload : null;
    } catch {
      return null;
    }
  };
  const approve = async () => {
    if (!run || actionBusy) return;
    const pending = pendingApproval();
    if (!pending?.approvalId) return setNotice("当前没有待确认的操作");
    setActionBusy(true);
    try {
      await api(
        `/api/agent/runs/${run.runId}/approvals/${pending.approvalId}`,
        {
          method: "POST",
          body: JSON.stringify({ parameters: pending.parameters ?? "" }),
        },
      );
      setNotice("已确认，研究将继续执行。");
      await refresh(run.runId);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "确认失败");
    } finally {
      setActionBusy(false);
    }
  };
  const reject = async () => {
    if (!run || actionBusy) return;
    const pending = pendingApproval();
    if (!pending?.approvalId) return setNotice("当前没有待确认的操作");
    setActionBusy(true);
    try {
      await api(
        `/api/agent/runs/${run.runId}/approvals/${pending.approvalId}/reject`,
        { method: "POST" },
      );
      setNotice("已拒绝，本次研究不会继续生成报告。");
      await refresh(run.runId);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "拒绝失败");
    } finally {
      setActionBusy(false);
    }
  };
  const download = async () => {
    if (!run) return;
    try {
      const file = await api<{ filename: string; content: string }>(
        `/api/agent/runs/${run.runId}/export`,
      );
      const url = URL.createObjectURL(
        new Blob([file.content], { type: "text/markdown;charset=utf-8" }),
      );
      const link = document.createElement("a");
      link.href = url;
      link.download = file.filename;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "下载失败");
    }
  };
  const failure = events.findLast(
    (event) =>
      event.eventType === "task.failed" || event.eventType === "run.failed",
  );
  const reason = (() => {
    if (!failure) return undefined;
    try {
      return (JSON.parse(failure.payloadJson) as { reason?: string }).reason;
    } catch {
      return undefined;
    }
  })();
  return (
    <AppShell notice={notice} title="研究任务" kicker="RESEARCH">
      <section className="workspace research-page">
        <p>深度研究</p>
        <h2>可以离开再回来的分析</h2>
        <p className="page-intro">
          马上问一句，用首页的研究助手。这里适合生成一份稍后查看的分析，进行中每
          2 秒自动更新。
        </p>
        <div className="task-layout">
          <aside>
            {historyLoading && <StatePanel title="正在加载研究任务" loading />}
            {historyFailed && (
              <StatePanel
                title="任务列表暂时加载失败"
                error
                onRetry={() => void loadHistory()}
              />
            )}
            {history.length > 0 && (
              <section className="task-progress">
                <h3>以往研究</h3>
                {history.map((item) => (
                  <button
                    type="button"
                    className="task-row"
                    disabled={actionBusy}
                    aria-pressed={run?.runId === item.runId}
                    key={item.runId}
                    onClick={() => {
                      setQuestion(item.message || "");
                      void refresh(item.runId);
                    }}
                  >
                    <i>○</i>
                    <div>
                      <b>{item.message || "未命名研究"}</b>
                      <small>
                        {item.fundCode ? `基金 ${item.fundCode} · ` : ""}
                        {item.startedAt
                          ? item.startedAt.replace("T", " ").slice(0, 16)
                          : ""}
                      </small>
                    </div>
                    <span>{runLabel[item.status] ?? item.status}</span>
                  </button>
                ))}
              </section>
            )}
            {!historyLoading && !historyFailed && !history.length && (
              <StatePanel title="还没有研究任务">
                提交问题后，分析与报告会保存在这里。
              </StatePanel>
            )}
          </aside>
          <div>
            <form className="research-form" onSubmit={submit}>
              <label>
                研究内容
                <textarea
                  value={message}
                  onChange={(e) => setMessage(e.target.value)}
                  rows={3}
                  placeholder="例如：比较两只基金近一年的风险与表现；需要文件时加上“导出报告”"
                />
              </label>
              <button disabled={loading}>
                {loading ? "正在创建…" : "开始研究"}
              </button>
            </form>
            {detailLoading && <StatePanel title="正在读取任务详情" loading />}
            {run && (
              <>
                {report && (
                  <section className="research-report">
                    <div>
                      <p>研究报告</p>
                      <h3>{question || "本次研究报告"}</h3>
                      <span>已汇总 {report.evidenceCount} 项依据</span>
                    </div>
                    <article>
                      {report.content
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
                    </article>
                  </section>
                )}
                {run.status === "SUCCEEDED" && !report?.content?.trim() && (
                  <StatePanel
                    title="任务已结束，正文尚未准备好"
                    onRetry={() => void refresh(run.runId)}
                  >
                    可以刷新进度重新读取，任务完成状态不代表已有可阅读正文。
                  </StatePanel>
                )}
                <div
                  className={`run-status ${run.status === "FAILED" ? "failed" : ""}`}
                >
                  <div>
                    <small>当前状态</small>
                    <b>{runLabel[run.status] ?? run.status}</b>
                    <span>{question || "本次研究"}</span>
                    <span>
                      {terminal.has(run.status)
                        ? "本次任务已结束。"
                        : "正在更新进度，可以离开页面，回来后仍能看到。"}
                    </span>
                  </div>
                  <div className="run-actions">
                    <button
                      disabled={actionBusy}
                      className="secondary"
                      onClick={() => void refresh(run.runId)}
                    >
                      刷新进度
                    </button>
                    {!terminal.has(run.status) && (
                      <button
                        className="text-button"
                        disabled={actionBusy}
                        onClick={cancel}
                      >
                        取消任务
                      </button>
                    )}
                    {run.status === "WAITING_APPROVAL" && (
                      <>
                        <button
                          disabled={actionBusy}
                          onClick={() => void approve()}
                        >
                          确认继续
                        </button>
                        <button
                          className="secondary"
                          disabled={actionBusy}
                          onClick={() => void reject()}
                        >
                          拒绝
                        </button>
                      </>
                    )}
                  </div>
                </div>
                {run.status === "WAITING_APPROVAL" && (
                  <p className="page-intro">
                    研究已完成引用检查，报告导出需要你确认。确认后会从保存的检查点继续。
                  </p>
                )}
                {run.status === "SUCCEEDED" &&
                  events.some(
                    (event) => event.eventType === "approval.approved",
                  ) && (
                    <button
                      className="secondary"
                      onClick={() => void download()}
                    >
                      下载研究报告
                    </button>
                  )}
                {reason && (
                  <div className="research-error">
                    <b>本次研究未完成</b>
                    <span>{reason}</span>
                    <small>请调整研究范围后重新提交，或等待数据补齐。</small>
                  </div>
                )}
                {plan && (
                  <section className="task-progress">
                    <h3>研究进度</h3>
                    {plan.tasks.map((t) => (
                      <div
                        key={t.taskKey}
                        className={`task-row ${t.status === "FAILED" ? "failed" : ""}`}
                      >
                        <i>
                          {t.status === "SUCCEEDED"
                            ? "✓"
                            : t.status === "FAILED"
                              ? "!"
                              : t.status === "RUNNING"
                                ? "…"
                                : "○"}
                        </i>
                        <div>
                          <b>
                            {taskName[t.capabilityType] ?? t.capabilityType}
                          </b>
                          <small>{t.taskKey}</small>
                        </div>
                        <span>{taskLabel[t.status] ?? t.status}</span>
                      </div>
                    ))}
                  </section>
                )}
                <details className="event-panel">
                  <summary>查看任务事件</summary>
                  <ol>
                    {events.map((event) => (
                      <li key={event.sequence}>
                        {event.sequence}. {event.eventType}
                      </li>
                    ))}
                  </ol>
                </details>
              </>
            )}
          </div>
        </div>
      </section>
    </AppShell>
  );
}
