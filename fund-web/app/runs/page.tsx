'use client';
import {FormEvent,useCallback,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Task={taskKey:string;capabilityType:string;status:string};
type Plan={planId:string;status:string;tasks:Task[]};
type Run={runId:string;status:string;executionMode:string;routeReason:string;planId?:string;lastEventSequence:number};
type Ev={sequence:number;eventType:string;payloadJson:string};
type ResearchReport={artifactUri:string;content:string;evidenceCount:number};
const terminal=new Set(['SUCCEEDED','FAILED','CANCELLED','REJECTED']);
const runLabel:Record<string,string>={PLAN_RUNNING:'正在分析',RUNNING:'正在分析',SUCCEEDED:'已完成',FAILED:'未完成',CANCELLED:'已取消',WAITING_APPROVAL:'等待确认'};
const taskLabel:Record<string,string>={READY:'等待执行',RUNNING:'正在执行',SUCCEEDED:'已完成',FAILED:'未完成',PENDING:'等待前置分析',CANCELLED:'已停止',WAITING_APPROVAL:'等待确认'};
const taskName:Record<string,string>={FUND_METRICS_QUERY:'计算指标',FUND_COMPARE:'基金比较',PORTFOLIO_SNAPSHOT:'读取组合',REPORT_VERIFY:'核对结果',REPORT_WRITE:'生成报告'};

/** Presents long-running research as readable progress and keeps its server state current. */
export default function RunsPage(){
  const[message,setMessage]=useState('结合我的持仓做一份分析与报告');
  const[run,setRun]=useState<Run|null>(null);const[plan,setPlan]=useState<Plan|null>(null);const[events,setEvents]=useState<Ev[]>([]);const[report,setReport]=useState<ResearchReport|null>(null);const[loading,setLoading]=useState(false);
  const[notice,setNotice]=useState('提交研究后会自动更新进度。');
  /** Fetches the owned run, plan, and event history as one user-visible progress snapshot. */
  const refresh=useCallback(async(id:string)=>{
    const nextRun=await api<Run>(`/api/v1/agent/runs/${id}`);setRun(nextRun);
    if(nextRun.planId)setPlan(await api<Plan>(`/api/v1/agent/runs/${id}/plan`));
    setEvents(await api<Ev[]>(`/api/v1/agent/runs/${id}/events`,{headers:{'Last-Event-ID':'0'}}));
    if(nextRun.status==='SUCCEEDED'){
      try{setReport(await api<ResearchReport>(`/api/v1/agent/runs/${id}/report`));}catch{setReport(null);}
    }else setReport(null);
  },[]);
  /** Restores the latest local run so a sign-in refresh does not hide its completed report. */
  useEffect(()=>{const saved=window.localStorage.getItem('fundpilot:lastResearchRunId');if(!saved)return;const timer=window.setTimeout(()=>{void refresh(saved).catch(()=>setNotice('暂时无法恢复上次研究，请重新登录后刷新进度'));},0);return()=>window.clearTimeout(timer);},[refresh]);
  /** Polls only active runs so completed work remains stable without unnecessary requests. */
  useEffect(()=>{if(!run||terminal.has(run.status))return;const timer=window.setInterval(()=>{void refresh(run.runId).catch(()=>setNotice('暂时无法更新进度，请稍后刷新'));},2000);return()=>window.clearInterval(timer);},[refresh,run]);
  /** Creates a new asynchronous research run and starts polling its progress. */
  const submit=async(e:FormEvent)=>{e.preventDefault();if(!message.trim())return setNotice('请描述希望研究的问题');setLoading(true);setReport(null);try{const created=await api<Run>('/api/v1/agent/runs',{method:'POST',body:JSON.stringify({message})});window.localStorage.setItem('fundpilot:lastResearchRunId',created.runId);setNotice('研究任务已创建，正在获取数据与分析。');await refresh(created.runId);}catch(x){setNotice(x instanceof Error?x.message:'提交研究任务失败');}finally{setLoading(false);}};
  /** Cancels an active run and reloads its final task states. */
  const cancel=async()=>{if(!run)return;try{await api(`/api/v1/agent/runs/${run.runId}/cancel`,{method:'POST'});setNotice('研究任务已取消');await refresh(run.runId);}catch(x){setNotice(x instanceof Error?x.message:'取消任务失败');}};
  /** Resolves the server-recorded approval request before execution resumes. */
  const approve=async()=>{if(!run)return;const requested=events.find(ev=>ev.eventType==='approval.requested');const id=requested?.payloadJson.match(/"approvalId":"([^"]+)"/)?.[1];if(!id)return setNotice('当前没有待确认的操作');try{await api(`/api/v1/agent/runs/${run.runId}/approvals/${id}`,{method:'POST',body:JSON.stringify({parameters:'{format=markdown}'})});setNotice('已确认，研究将继续执行。');await refresh(run.runId);}catch(x){setNotice(x instanceof Error?x.message:'确认失败');}};
  const failure=events.findLast(event=>event.eventType==='task.failed'||event.eventType==='run.failed');const reason=failure?.payloadJson.match(/"reason":"([^"]*)"/)?.[1];
  return <AppShell notice={notice}><section className="workspace research-page"><p>RESEARCH</p><h2>研究任务</h2><p className="page-intro">系统会依次读取数据、分析指标、比较基金，并生成结果。执行中的任务每 2 秒自动更新。</p>
    <form className="research-form" onSubmit={submit}><label>研究内容<textarea value={message} onChange={e=>setMessage(e.target.value)} rows={3} placeholder="例如：比较两只基金近一年的风险与表现"/></label><button disabled={loading}>{loading?'正在创建…':'开始研究'}</button></form>
    {run&&<><div className={`run-status ${run.status==='FAILED'?'failed':''}`}><div><small>当前状态</small><b>{runLabel[run.status]??run.status}</b><span>{terminal.has(run.status)?'本次任务已结束。':'正在更新进度，请保持页面打开。'}</span></div><div className="run-actions"><button className="secondary" onClick={()=>void refresh(run.runId)}>刷新进度</button>{!terminal.has(run.status)&&<button className="text-button" onClick={cancel}>取消任务</button>}{run.status==='WAITING_APPROVAL'&&<button onClick={approve}>确认继续</button>}</div></div>
      {report&&<section className="research-report"><div><p>RESEARCH REPORT</p><h3>本次研究报告</h3><span>已汇总 {report.evidenceCount} 项研究产出</span></div><article>{report.content.split('\n').map((line,index)=>line.startsWith('# ')?<h4 key={index}>{line.slice(2)}</h4>:line.startsWith('## ')?<h5 key={index}>{line.slice(3)}</h5>:line.startsWith('- ')?<p key={index}>• {line.slice(2)}</p>:line?<p key={index}>{line}</p>:null)}</article></section>}
      {reason&&<div className="research-error"><b>本次研究未完成</b><span>{reason}</span><small>请调整研究范围后重新提交，或等待数据补齐。</small></div>}
      {plan&&<section className="task-progress"><h3>研究进度</h3>{plan.tasks.map(t=><div key={t.taskKey} className={`task-row ${t.status==='FAILED'?'failed':''}`}><i>{t.status==='SUCCEEDED'?'✓':t.status==='FAILED'?'!':t.status==='RUNNING'?'…':'○'}</i><div><b>{taskName[t.capabilityType]??t.capabilityType}</b><small>{t.taskKey}</small></div><span>{taskLabel[t.status]??t.status}</span></div>)}</section>}
      <details className="event-panel"><summary>查看任务事件</summary><ol>{events.map(event=><li key={event.sequence}>{event.sequence}. {event.eventType}</li>)}</ol></details>
    </>}
  </section></AppShell>;
}
