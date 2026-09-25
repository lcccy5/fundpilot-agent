'use client';
import {useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Item={notificationId:string;ruleId:string;status:string;fingerprint:string;title?:string;summary?:string;createdAt?:string;occurredAt?:string;readAt?:string|null};

/** Displays user notifications as readable updates while keeping internal identifiers out of the primary view. */
export default function NotificationsPage(){
  const[items,setItems]=useState<Item[]>([]);const[notice,setNotice]=useState('重要变化会显示在这里');const[loading,setLoading]=useState(true);const[failed,setFailed]=useState(false);
  const load=()=>api<Item[]>('/api/v1/notifications').then(next=>{setItems(next);setFailed(false);}).catch(e=>{setFailed(true);setNotice(e instanceof Error?e.message:'请先登录后查看通知');});
  useEffect(()=>{void load().finally(()=>setLoading(false));},[]);
  const markRead=async(item:Item)=>{
    try{
      await api(`/api/v1/notifications/${item.notificationId}/read`,{method:'POST'});
      setItems(current=>current.map(row=>row.notificationId===item.notificationId?{...row,readAt:row.readAt??new Date().toISOString()}:row));
    }catch(e){setNotice(e instanceof Error?e.message:'标为已读失败');}
  };
  return <AppShell notice={notice} title="通知" kicker="INBOX"><section className="workspace notification-page"><p>INBOX</p><h2>通知中心</h2><p className="page-intro">查看与你的自选、组合和研究相关的更新。已读状态会保存在服务端。</p>{loading?<div className="empty-panel">正在加载通知…</div>:failed?<div className="empty-panel"><b>通知暂时加载失败</b><span>请稍后重试。这不是空收件箱。</span></div>:items.length?<div className="notification-list">{items.map(n=><article key={n.notificationId}><div><b>{n.title??'研究状态更新'}</b><p>{n.summary??'有一项关注内容发生变化，请查看相关研究。'}</p><small>{n.occurredAt??n.createdAt??'时间待同步'}</small></div><span className={n.readAt?'status-normal':'status-error'}>{n.readAt?'已读':'未读'}</span>{n.readAt?null:<button type="button" className="secondary" onClick={()=>void markRead(n)}>标为已读</button>}</article>)}</div>:<div className="empty-panel"><b>暂时没有通知</b><span>基金资料、研究任务或组合出现可用更新后，会在这里提醒你。</span></div>}</section></AppShell>;
}
