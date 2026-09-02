'use client';
import {FormEvent,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Job={jobId:string;runId:string;status:string;periodStart?:string;periodEnd?:string};

export default function ReportsPage(){
  const[jobs,setJobs]=useState<Job[]>([]);
  const[notice,setNotice]=useState('月报复用 V4 Plan-and-Execute，不会绕过审批与隔离');
  const load=()=>api('/api/v1/reports').then(setJobs).catch(e=>setNotice(e instanceof Error?e.message:'请先登录'));
  useEffect(()=>{load();},[]);
  const launch=async(e:FormEvent)=>{e.preventDefault();try{await api('/api/v1/reports/monthly',{method:'POST',body:'{}'});setNotice('月报任务已创建');await load();}catch(x){setNotice(x instanceof Error?x.message:'创建月报任务失败');}};
  return <AppShell notice={notice}><section className="workspace"><p>REPORTS</p><h2>版本化月报</h2>
    <form className="auth-form" onSubmit={launch}><button>生成月报任务</button></form>
    <ol>{jobs.map(j=><li key={j.jobId||j.runId}>{j.status} · run {j.runId} · {j.periodStart} ~ {j.periodEnd}</li>)}</ol>
  </section></AppShell>;
}
