'use client';
import {useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Item={notificationId:string;ruleId:string;status:string;fingerprint:string;title?:string;summary?:string;createdAt?:string;occurredAt?:string};

/** Displays user notifications as readable updates while keeping internal identifiers out of the primary view. */
export default function NotificationsPage(){
  const[items,setItems]=useState<Item[]>([]);const[notice,setNotice]=useState('重要变化会显示在这里');const[loading,setLoading]=useState(true);
  /** Loads notification records and distinguishes an empty inbox from a failed request. */
  useEffect(()=>{api('/api/v1/notifications').then(setItems).catch(e=>setNotice(e instanceof Error?e.message:'请先登录后查看通知')).finally(()=>setLoading(false));},[]);
  return <AppShell notice={notice}><section className="workspace notification-page"><p>INBOX</p><h2>通知中心</h2><p className="page-intro">查看与你的自选、组合和研究相关的更新。</p>{loading?<div className="empty-panel">正在加载通知…</div>:items.length?<div className="notification-list">{items.map(n=><article key={n.notificationId}><div><b>{n.title??'研究状态更新'}</b><p>{n.summary??'有一项关注内容发生变化，请查看相关研究。'}</p><small>{n.occurredAt??n.createdAt??'时间待同步'}</small></div><span className={n.status==='FAILED'?'status-error':'status-normal'}>{n.status==='SCHEDULED'?'待处理':n.status==='FAILED'?'未完成':'已更新'}</span></article>)}</div>:<div className="empty-panel"><b>暂时没有通知</b><span>基金资料、研究任务或组合出现可用更新后，会在这里提醒你。</span></div>}</section></AppShell>;
}
