'use client';
import {FormEvent,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Task={taskKey:string;capabilityType:string;status:string};
type Plan={planId:string;status:string;tasks:Task[]};
type Run={runId:string;status:string;executionMode:string;routeReason:string;planId?:string;lastEventSequence:number};
type Ev={sequence:number;eventType:string;payloadJson:string};

export default function RunsPage(){
  const[message,setMessage]=useState('比较 000001 110022 161725 并结合我的组合生成报告');
  const[run,setRun]=useState<Run|null>(null);const[plan,setPlan]=useState<Plan|null>(null);const[events,setEvents]=useState<Ev[]>([]);
  const[notice,setNotice]=useState('异步研究任务走 Plan-and-Execute，可取消、审批和按 Last-Event-ID 回放');
  const refresh=async(id:string,after=0)=>{
    const r=await api(`/api/v1/agent/runs/${id}`) as Run;setRun(r);
    if(r.planId)setPlan(await api(`/api/v1/agent/runs/${id}/plan`) as Plan);
    const page=await api(`/api/v1/agent/runs/${id}/events`,{headers:{'Last-Event-ID':String(after)}}) as Ev[];
    setEvents(page);
  };
  const submit=async(e:FormEvent)=>{e.preventDefault();try{const created=await api('/api/v1/agent/runs',{method:'POST',body:JSON.stringify({message})}) as Run;setNotice(`已路由为 ${created.executionMode} · ${created.routeReason}`);await refresh(created.runId);}catch(x){setNotice(x instanceof Error?x.message:'提交失败');}};
  const cancel=async()=>{if(!run)return;try{await api(`/api/v1/agent/runs/${run.runId}/cancel`,{method:'POST'});setNotice('任务已取消');await refresh(run.runId);}catch(x){setNotice(x instanceof Error?x.message:'取消任务失败');}};
  const approve=async()=>{
    if(!run)return;
    const requested=events.find(ev=>ev.eventType==='approval.requested');
    const id=requested?.payloadJson?.match(/"approvalId":"([^"]+)"/)?.[1];
    if(!id){setNotice('没有待审批项');return;}
    try{await api(`/api/v1/agent/runs/${run.runId}/approvals/${id}`,{method:'POST',body:JSON.stringify({parameters:'{format=markdown}'})});setNotice('审批已通过');await refresh(run.runId);}catch(x){setNotice(x instanceof Error?x.message:'审批失败');}
  };
  return <AppShell notice={notice}><section className="workspace"><p>AGENT RUNTIME</p><h2>研究任务 DAG</h2>
    <form className="auth-form" onSubmit={submit}><textarea value={message} onChange={e=>setMessage(e.target.value)} rows={3}/><button>提交异步任务</button></form>
    {run&&<p>Run {run.runId} · {run.status} · {run.executionMode}<button className="text-button" onClick={cancel}>取消</button>{run.status==='WAITING_APPROVAL'&&<button className="text-button" onClick={approve}>审批通过</button>}</p>}
    {plan&&<ol className="agent-trace">{plan.tasks.map(t=><li key={t.taskKey}>{t.taskKey} · {t.capabilityType} · {t.status}</li>)}</ol>}
    <h3>事件回放</h3>
    <ol>{events.map(ev=><li key={ev.sequence}>{ev.sequence} {ev.eventType}</li>)}</ol>
  </section></AppShell>;
}
