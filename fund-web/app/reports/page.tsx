'use client';
import { FormEvent, useEffect, useState } from 'react';
import AppShell from '../../components/AppShell';
import { api } from '../../lib/session';

type Job = { jobId: string; runId: string; status: string; periodStart?: string; periodEnd?: string };

/**
 * 列出已有月报任务，并提交一次新的月报生成。
 * 列表请求失败时顶栏提示登录或错误，有序列表保持空白，和一台都没有的空列表看起来一样。创建失败时不改列表。
 */
export default function ReportsPage() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [notice, setNotice] = useState('月报复用 V4 Plan-and-Execute，不会绕过审批与隔离');
  /**
   * 重新读取月报任务列表。
   * 401/403、其它 4xx/5xx 或网络失败时只更新顶栏，不清空已经显示过的任务；没有加载中的占位。
   */
  const load = () =>
    api('/api/v1/reports')
      .then(setJobs)
      .catch((e) => setNotice(e instanceof Error ? e.message : '请先登录'));
  useEffect(() => {
    load();
  }, []);
  /**
   * 用空 JSON 创建一个月报任务，成功后再刷新列表。
   * 未登录、校验失败或 5xx 时顶栏显示接口文案或“创建月报任务失败”，已有列表不动。
   */
  const launch = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await api('/api/v1/reports/monthly', { method: 'POST', body: '{}' });
      setNotice('月报任务已创建');
      await load();
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '创建月报任务失败');
    }
  };
  return (
    <AppShell notice={notice}>
      <section className="workspace">
        <p>REPORTS</p>
        <h2>版本化月报</h2>
        <form className="auth-form" onSubmit={launch}>
          <button>生成月报任务</button>
        </form>
        <ol>
          {jobs.map((j) => (
            <li key={j.jobId || j.runId}>
              {j.status} · run {j.runId} · {j.periodStart} ~ {j.periodEnd}
            </li>
          ))}
        </ol>
      </section>
    </AppShell>
  );
}
