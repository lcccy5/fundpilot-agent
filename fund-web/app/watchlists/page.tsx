'use client';
import {FormEvent,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api,clearGuestWatch,guestWatch} from '../../lib/session';

type Group={groupId:string;displayName:string;version:number;items:{itemId:string;fundCode:{value:string};version:number}[]};

export default function WatchlistsPage(){
  const[groups,setGroups]=useState<Group[]>([]);const[name,setName]=useState('默认分组');const[code,setCode]=useState('');const[notice,setNotice]=useState('登录后自选会跨设备保存');
  const load=()=>api('/api/v1/watchlists').then(setGroups).catch(x=>setNotice(x instanceof Error?x.message:'请先登录'));
  useEffect(()=>{void load();},[]);
  const create=async(e:FormEvent)=>{e.preventDefault();try{await api('/api/v1/watchlists',{method:'POST',body:JSON.stringify({name})});setNotice('分组已创建');load();}catch(x){setNotice(x instanceof Error?x.message:'创建分组失败');}};
  const add=async(groupId:string)=>{try{await api(`/api/v1/watchlists/${groupId}/items`,{method:'POST',body:JSON.stringify({fundCode:code})});setCode('');setNotice('基金已加入分组');load();}catch(x){setNotice(x instanceof Error?x.message:'加入失败');}};
  const merge=async()=>{const local=guestWatch();if(!local.length){setNotice('没有本地临时自选');return;}try{const result=await api('/api/v1/watchlists/merge-local',{method:'POST',body:JSON.stringify({fundCodes:local})});clearGuestWatch();setNotice(`合并完成：新增 ${result.added}，已有 ${result.existing}`);load();}catch(x){setNotice(x instanceof Error?x.message:'合并失败');}};
  return <AppShell notice={notice}><section className="workspace"><p>WATCHLIST</p><h2>我的自选</h2>
    <form className="auth-form" onSubmit={create}><input value={name} onChange={e=>setName(e.target.value)}/><button>新建分组</button></form>
    <p><button className="primary" onClick={merge}>合并本地临时自选</button></p>
    {groups.map(g=><div key={g.groupId} className="watch-list"><b>{g.displayName}</b>
      {g.items.map(i=><div key={i.itemId}>{i.fundCode.value}</div>)}
      <form onSubmit={e=>{e.preventDefault();add(g.groupId);}}><input value={code} onChange={e=>setCode(e.target.value.replace(/\D/g,'').slice(0,6))} placeholder="基金代码"/><button>加入</button></form>
    </div>)}
  </section></AppShell>;
}
