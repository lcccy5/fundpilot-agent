'use client';
import {useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Item={notificationId:string;ruleId:string;status:string;fingerprint:string};

export default function NotificationsPage(){
  const[items,setItems]=useState<Item[]>([]);
  const[notice,setNotice]=useState('站内通知按用户隔离，免打扰会保留为 SCHEDULED 而不是丢弃');
  useEffect(()=>{api('/api/v1/notifications').then(setItems).catch(e=>setNotice(e instanceof Error?e.message:'请先登录'));},[]);
  return <AppShell notice={notice}><section className="workspace"><p>INBOX</p><h2>通知中心</h2>
    <ol>{items.map(n=><li key={n.notificationId}>{n.status} · {n.ruleId} · {n.fingerprint}</li>)}</ol>
  </section></AppShell>;
}
