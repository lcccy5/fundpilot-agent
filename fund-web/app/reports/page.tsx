'use client';
import {FormEvent,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';
import {runStatusLabel} from '../../lib/polish.mjs';

type Job={jobId:string;runId:string;status:string;periodStart?:string;periodEnd?:string};

export default function ReportsPage(){
  const[jobs,setJobs]=useState<Job[]>([]);
  const[notice,setNotice]=useState('月报生成后会列在这里，状态用中文显示。');
  const[loading,setLoading]=useState(true);
  const[failed,setFailed]=useState(false);
  const[creating,setCreating]=useState(false);
  const load=()=>api<Job[]>('/api/v1/reports').then(next=>{setJobs(next);setFailed(false);});
  useEffect(()=>{load().catch(e=>{setFailed(true);setNotice(e instanceof Error?e.message:'请先登录');}).finally(()=>setLoading(false));},[]);
  const launch=async(e:FormEvent)=>{e.preventDefault();if(creating)return;setCreating(true);try{await api('/api/v1/reports/monthly',{method:'POST',body:'{}'});setNotice('月报任务已创建');await load();}catch(x){setNotice(x instanceof Error?x.message:'创建月报任务失败');}finally{setCreating(false);}};
  return <AppShell notice={notice}><section className="workspace"><p>月报</p><h2>月度回顾</h2><p className="page-intro">按已有数据生成一份回顾。列表只展示状态和区间，不把内部编号放在主行。</p>
    <form className="auth-form" onSubmit={launch}><button disabled={creating}>{creating?'正在创建…':'生成月报任务'}</button></form>
    {loading?<div className="empty-panel">正在加载月报…</div>:failed?<div className="empty-panel"><b>月报列表没有加载出来</b><span>请稍后重试。这不是一份空记录。</span></div>:jobs.length?<ol>{jobs.map(job=><li key={job.jobId||job.runId}>{runStatusLabel(job.status)}{job.periodStart?` · ${job.periodStart} 至 ${job.periodEnd??''}`:''}</li>)}</ol>:<div className="empty-panel"><b>还没有月报</b><span>生成后，完成状态和区间会显示在这里。</span></div>}
  </section></AppShell>;
}
