'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import AppShell from '../../components/AppShell';
import { currentAccessToken, restoreSession } from '../../lib/session';
import { Claim, Decision, Opinion, readResearchStream, ResearchResult, Role, Snapshot, Trace } from '../../lib/research-stream';
import './arena.css';

type StepStatus = 'waiting' | 'running' | 'done' | 'failed';
const roles: Role[] = ['data', 'bull', 'bear', 'judge'];
const labels: Record<Role, string> = { data: '数据读取', bull: '看多分析师', bear: '看空分析师', judge: '独立裁决' };
const metricLabels: Record<string, string> = {
  cumulativeReturn: '累计收益', annualizedReturn: '年化收益',
  annualizedVolatility: '年化波动', maxDrawdown: '最大回撤', sharpeRatio: '夏普比率',
};
const percentMetrics = new Set(['cumulativeReturn', 'annualizedReturn', 'annualizedVolatility', 'maxDrawdown']);
const emptySteps = (): Record<Role, StepStatus> => ({ data: 'waiting', bull: 'waiting', bear: 'waiting', judge: 'waiting' });
const localDate = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

function Claims({ items }: { items: Claim[] }) {
  return <ul className="arena-claims">{items.map((claim, index) => <li key={index}>
    <span>{claim.statement}</span>
    <small>证据 {claim.evidenceIds.join('、')}</small>
  </li>)}</ul>;
}

function OpinionCard({ title, opinion }: { title: string; opinion: Opinion }) {
  return <article className="arena-opinion"><h3>{title}</h3><p>{opinion.summary}</p>
    <Claims items={opinion.claims} />
    {opinion.limitations.length > 0 && <p className="arena-muted">局限：{opinion.limitations.join('；')}</p>}
  </article>;
}

function SnapshotCard({ snapshot }: { snapshot: Snapshot }) {
  return <section className="arena-card"><div className="arena-section-head"><h2>同区间数据</h2>
    <span>{snapshot.commonStartDate} 至 {snapshot.commonEndDate}</span></div>
    <div className="arena-snapshot-grid">{snapshot.funds.map(fund => <article key={fund.fundCode}>
      <h3>基金 {fund.fundCode}</h3><p>{fund.observationCount} 个观测点 · {fund.coverageStatus}</p>
      <dl>{Object.entries(fund.metrics).map(([name, metric]) => <div key={name}>
        <dt>{metricLabels[name] || name}</dt><dd>{metric.status === 'AVAILABLE' && metric.value !== null
          ? percentMetrics.has(name) ? `${(Number(metric.value) * 100).toFixed(2)}%` : Number(metric.value).toFixed(2)
          : '暂无数据'}</dd>
      </div>)}</dl>
    </article>)}</div>
  </section>;
}

export default function ArenaPage() {
  const [first, setFirst] = useState('000001');
  const [second, setSecond] = useState('110022');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [steps, setSteps] = useState(emptySteps);
  const [traces, setTraces] = useState<Partial<Record<Role, Trace>>>({});
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [bull, setBull] = useState<Opinion | null>(null);
  const [bear, setBear] = useState<Opinion | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [runId, setRunId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    const today = new Date();
    const yearAgo = new Date(today);
    yearAgo.setFullYear(today.getFullYear() - 1);
    setStartDate(localDate(yearAgo));
    setEndDate(localDate(today));
    const previous = window.localStorage.getItem('fundpilot:lastArenaRun');
    if (!previous) return;
    // 只恢复已经保存的完整结果；中途断开的运行需重新开始。
    void fetch(`/api/research/runs/${encodeURIComponent(previous)}`).then(async response => {
      if (!response.ok) return;
      const result = await response.json() as ResearchResult;
      setRunId(result.runId); setSnapshot(result.snapshot); setBull(result.bull);
      setBear(result.bear); setDecision(result.decision);
      setFirst(result.snapshot.funds[0]?.fundCode ?? '');
      setSecond(result.snapshot.funds[1]?.fundCode ?? '');
      setStartDate(result.snapshot.commonStartDate);
      setEndDate(result.snapshot.commonEndDate);
      setTraces(Object.fromEntries(result.trace.map(trace => [trace.role, trace])) as Partial<Record<Role, Trace>>);
      setSteps({ data: 'done', bull: 'done', bear: 'done', judge: 'done' });
    }).catch(() => { /* 本地 Python 服务可能尚未启动。 */ });
    return () => controller.current?.abort();
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (first === second) return setError('请选择两只不同的基金');
    if (startDate > endDate) return setError('开始日期不能晚于结束日期');
    controller.current?.abort();
    const nextController = new AbortController();
    controller.current = nextController;
    setBusy(true); setError(''); setRunId(''); setSnapshot(null); setBull(null); setBear(null); setDecision(null);
    setTraces({}); setSteps({ data: 'running', bull: 'waiting', bear: 'waiting', judge: 'waiting' });
    try {
      // Java 的基金比较接口需要登录；先刷新过期会话，再把凭证传给 Python。
      const signedIn = await restoreSession().catch(() => false);
      const token = currentAccessToken();
      if (!signedIn || !token) throw new Error('请先登录，再使用多 Agent 研究');
      const response = await fetch('/api/research/compare/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        signal: nextController.signal,
        body: JSON.stringify({ fundCodes: [first, second], startDate, endDate }),
      });
      await readResearchStream(response, item => {
        if (item.type === 'started') setRunId(item.runId);
        if (item.type === 'node_completed') {
          setSteps(current => {
            const next = { ...current, [item.node]: 'done' as const };
            if (item.node === 'data') { next.bull = 'running'; next.bear = 'running'; }
            if ((item.node === 'bull' && next.bear === 'done') || (item.node === 'bear' && next.bull === 'done')) next.judge = 'running';
            return next;
          });
          setTraces(current => ({ ...current, [item.node]: item.trace }));
          if (item.node === 'data') setSnapshot(item.output as Snapshot);
          if (item.node === 'bull') setBull(item.output as Opinion);
          if (item.node === 'bear') setBear(item.output as Opinion);
          if (item.node === 'judge') setDecision(item.output as Decision);
        }
        if (item.type === 'completed') window.localStorage.setItem('fundpilot:lastArenaRun', item.result.runId);
        if (item.type === 'failed') throw new Error(item.message);
      });
    } catch (reason) {
      if (nextController.signal.aborted) return;
      setError(reason instanceof Error ? reason.message : '研究运行失败');
      setSteps(current => Object.fromEntries(roles.map(role => [role, current[role] === 'running' ? 'failed' : current[role]])) as Record<Role, StepStatus>);
    } finally {
      if (controller.current === nextController) { controller.current = null; setBusy(false); }
    }
  }

  return <AppShell title="多 Agent 研究" kicker="RESEARCH ARENA" notice={busy ? '研究正在运行，节点完成后会实时更新。' : undefined}>
    <div className="arena-page">
      <section className="arena-card arena-intro"><div><p className="arena-eyebrow">两只基金 · 一份共享数据 · 三种视角</p>
        <h2>看见每个 Agent 如何形成结论</h2><p>数据节点先建立证据；看多与看空并行分析；裁决节点在两者完成后运行。</p></div>
        <form className="arena-form" onSubmit={submit}>
          <label>基金 A<input value={first} onChange={e => setFirst(e.target.value.trim())} inputMode="numeric" pattern="[0-9]{6}" title="请输入 6 位基金代码" required /></label>
          <label>基金 B<input value={second} onChange={e => setSecond(e.target.value.trim())} inputMode="numeric" pattern="[0-9]{6}" title="请输入 6 位基金代码" required /></label>
          <label>开始日期<input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} required /></label>
          <label>结束日期<input type="date" value={endDate} max={localDate(new Date())} onChange={e => setEndDate(e.target.value)} required /></label>
          <button type="submit" disabled={busy || !startDate || !endDate}>{busy ? '研究中…' : '开始研究'}</button>
        </form>
      </section>

      {error && <div className="arena-error" role="alert">{error}</div>}
      <section className="arena-card"><div className="arena-section-head"><h2>执行路径</h2>{runId && <small>运行编号 {runId}</small>}</div>
        <div className="arena-steps">{roles.map((role, index) => <div className={`arena-step arena-${steps[role]}`} key={role}>
          <span className="arena-step-number">{steps[role] === 'done' ? '✓' : String(index + 1).padStart(2, '0')}</span>
          <div><b>{labels[role]}</b><small>{steps[role] === 'done' ? `完成 · ${traces[role]?.durationMs ?? 0} ms` :
            steps[role] === 'running' ? '正在执行' : steps[role] === 'failed' ? '执行失败' : '等待前置节点'}</small></div>
        </div>)}</div>
      </section>

      {snapshot && <SnapshotCard snapshot={snapshot} />}
      {(bull || bear) && <section className="arena-opinions">
        {bull && <OpinionCard title="看多分析师" opinion={bull} />}
        {bear && <OpinionCard title="看空分析师" opinion={bear} />}
      </section>}
      {decision && <section className="arena-card arena-decision"><p className="arena-eyebrow">独立裁决</p>
        <h2>{decision.preferredFundCode ? `倾向基金 ${decision.preferredFundCode}` : '证据不足，暂不选择'}</h2>
        <p>{decision.conclusion}</p><Claims items={decision.rationale} />
        {decision.disagreements.length > 0 && <p className="arena-muted">分歧：{decision.disagreements.join('；')}</p>}
        {decision.limitations.length > 0 && <p className="arena-muted">局限：{decision.limitations.join('；')}</p>}
      </section>}
    </div>
  </AppShell>;
}
