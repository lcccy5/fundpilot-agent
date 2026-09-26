'use client';
import { FormEvent, useCallback, useEffect, useState } from 'react';
import AppShell from '../../components/AppShell';
import { api } from '../../lib/session';

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
type ResearchReport = { artifactUri: string; content: string; evidenceCount: number };
const terminal = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED']);
const runLabel: Record<string, string> = {
  PLAN_RUNNING: '正在分析',
  RUNNING: '正在分析',
  SUCCEEDED: '已完成',
  FAILED: '未完成',
  CANCELLED: '已取消',
  WAITING_APPROVAL: '等待确认',
};
const taskLabel: Record<string, string> = {
  READY: '等待执行',
  RUNNING: '正在执行',
  SUCCEEDED: '已完成',
  FAILED: '未完成',
  PENDING: '等待前置分析',
  CANCELLED: '已停止',
  WAITING_APPROVAL: '等待确认',
};
const taskName: Record<string, string> = {
  FUND_METRICS_QUERY: '计算指标',
  FUND_COMPARE: '基金比较',
  PORTFOLIO_SNAPSHOT: '读取组合',
  REPORT_VERIFY: '核对结果',
  REPORT_WRITE: '生成报告',
};

/**
 * 提交异步研究，并在页面上轮询进度、审批和完成后的报告。
 * 没有任务时只显示表单。内容为空时不提交。运行中的 4xx/5xx 只改顶栏，已显示的进度保留。基金比较没有单独页面，失败时只作为任务状态“未完成”和事件原因出现。
 */
export default function RunsPage() {
  const [message, setMessage] = useState('结合我的持仓做一份分析与报告');
  const [run, setRun] = useState<Run | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [events, setEvents] = useState<Ev[]>([]);
  const [report, setReport] = useState<ResearchReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState('提交研究后会自动更新进度。');
  /**
   * 一次读回运行、计划和事件；成功状态再尝试读报告。
   * 运行或计划接口失败时整次刷新抛出，由调用方写顶栏。报告 4xx/5xx 时只把报告区清空，不把整次进度当成失败。
   */
  const refresh = useCallback(async (id: string) => {
    const nextRun = await api<Run>(`/api/v1/agent/runs/${id}`);
    setRun(nextRun);
    if (nextRun.planId) setPlan(await api<Plan>(`/api/v1/agent/runs/${id}/plan`));
    setEvents(await api<Ev[]>(`/api/v1/agent/runs/${id}/events`, { headers: { 'Last-Event-ID': '0' } }));
    if (nextRun.status === 'SUCCEEDED') {
      try {
        setReport(await api<ResearchReport>(`/api/v1/agent/runs/${id}/report`));
      } catch {
        setReport(null);
      }
    } else setReport(null);
  }, []);
  /**
   * 若本机记过上次任务编号，延迟拉一次进度，避免登录刷新后丢掉已完成报告。
   * 没有记录时什么都不做；接口 401/404/5xx 时顶栏提示重新登录，页面保持空表单。
   */
  useEffect(() => {
    const saved = window.localStorage.getItem('fundpilot:lastResearchRunId');
    if (!saved) return;
    const timer = window.setTimeout(() => {
      void refresh(saved).catch(() => setNotice('暂时无法恢复上次研究，请重新登录后刷新进度'));
    }, 0);
    return () => window.clearTimeout(timer);
  }, [refresh]);
  /**
   * 任务还没进入终态时每 2 秒拉一次进度。
   * 轮询失败只改顶栏，不把当前进度清掉；终态任务不再发请求。
   */
  useEffect(() => {
    if (!run || terminal.has(run.status)) return;
    const timer = window.setInterval(() => {
      void refresh(run.runId).catch(() => setNotice('暂时无法更新进度，请稍后刷新'));
    }, 2000);
    return () => window.clearInterval(timer);
  }, [refresh, run]);
  /**
   * 用当前描述创建一次研究并立刻拉进度。
   * 描述为空白时不发请求；未登录、校验失败或 5xx 时停在表单，顶栏显示接口文案或“提交研究任务失败”。
   */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!message.trim()) return setNotice('请描述希望研究的问题');
    setLoading(true);
    setReport(null);
    try {
      const created = await api<Run>('/api/v1/agent/runs', {
        method: 'POST',
        body: JSON.stringify({ message }),
      });
      window.localStorage.setItem('fundpilot:lastResearchRunId', created.runId);
      setNotice('研究任务已创建，正在获取数据与分析。');
      await refresh(created.runId);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '提交研究任务失败');
    } finally {
      setLoading(false);
    }
  };
  /**
   * 取消当前还在跑的任务并重新读取终态。
   * 没有任务时直接返回；取消接口 4xx/5xx 时顶栏报错，进度区保持取消前的状态。
   */
  const cancel = async () => {
    if (!run) return;
    try {
      await api(`/api/v1/agent/runs/${run.runId}/cancel`, { method: 'POST' });
      setNotice('研究任务已取消');
      await refresh(run.runId);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '取消任务失败');
    }
  };
  /**
   * 从事件里找出待确认编号并提交固定参数，让任务继续。
   * 事件里没有 approvalId 时不发请求；确认接口校验失败或 5xx 时顶栏显示“确认失败”或接口文案。
   */
  const approve = async () => {
    if (!run) return;
    const requested = events.find((ev) => ev.eventType === 'approval.requested');
    const id = requested?.payloadJson.match(/"approvalId":"([^"]+)"/)?.[1];
    if (!id) return setNotice('当前没有待确认的操作');
    try {
      await api(`/api/v1/agent/runs/${run.runId}/approvals/${id}`, {
        method: 'POST',
        body: JSON.stringify({ parameters: '{format=markdown}' }),
      });
      setNotice('已确认，研究将继续执行。');
      await refresh(run.runId);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '确认失败');
    }
  };
  const failure = events.findLast((event) => event.eventType === 'task.failed' || event.eventType === 'run.failed');
  const reason = failure?.payloadJson.match(/"reason":"([^"]*)"/)?.[1];
  return (
    <AppShell notice={notice}>
      <section className="workspace research-page">
        <p>RESEARCH</p>
        <h2>研究任务</h2>
        <p className="page-intro">
          系统会依次读取数据、分析指标、比较基金，并生成结果。执行中的任务每 2 秒自动更新。
        </p>
        <form className="research-form" onSubmit={submit}>
          <label>
            研究内容
            <textarea
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              rows={3}
              placeholder="例如：比较两只基金近一年的风险与表现"
            />
          </label>
          <button disabled={loading}>{loading ? '正在创建…' : '开始研究'}</button>
        </form>
        {run && (
          <>
            <div className={`run-status ${run.status === 'FAILED' ? 'failed' : ''}`}>
              <div>
                <small>当前状态</small>
                <b>{runLabel[run.status] ?? run.status}</b>
                <span>{terminal.has(run.status) ? '本次任务已结束。' : '正在更新进度，请保持页面打开。'}</span>
              </div>
              <div className="run-actions">
                <button className="secondary" onClick={() => void refresh(run.runId)}>
                  刷新进度
                </button>
                {!terminal.has(run.status) && (
                  <button className="text-button" onClick={cancel}>
                    取消任务
                  </button>
                )}
                {run.status === 'WAITING_APPROVAL' && <button onClick={approve}>确认继续</button>}
              </div>
            </div>
            {report && (
              <section className="research-report">
                <div>
                  <p>RESEARCH REPORT</p>
                  <h3>本次研究报告</h3>
                  <span>已汇总 {report.evidenceCount} 项研究产出</span>
                </div>
                <article>
                  {report.content.split('\n').map((line, index) =>
                    line.startsWith('# ') ? (
                      <h4 key={index}>{line.slice(2)}</h4>
                    ) : line.startsWith('## ') ? (
                      <h5 key={index}>{line.slice(3)}</h5>
                    ) : line.startsWith('- ') ? (
                      <p key={index}>• {line.slice(2)}</p>
                    ) : line ? (
                      <p key={index}>{line}</p>
                    ) : null,
                  )}
                </article>
              </section>
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
                  <div key={t.taskKey} className={`task-row ${t.status === 'FAILED' ? 'failed' : ''}`}>
                    <i>{t.status === 'SUCCEEDED' ? '✓' : t.status === 'FAILED' ? '!' : t.status === 'RUNNING' ? '…' : '○'}</i>
                    <div>
                      <b>{taskName[t.capabilityType] ?? t.capabilityType}</b>
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
      </section>
    </AppShell>
  );
}
