'use client';
import {FormEvent,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api,clearGuestWatch,guestWatch} from '../../lib/session';

type Group={groupId:string;displayName:string;version:number;items:{itemId:string;fundCode:{value:string};version:number}[]};

/** Lets signed-in users manage cloud watchlists and reconcile device-only items. */
export default function WatchlistsPage(){
  const[groups,setGroups]=useState<Group[]>([]);const[name,setName]=useState('默认分组');const[code,setCode]=useState('');const[notice,setNotice]=useState('登录后，自选会同步到你的账户');const[loading,setLoading]=useState(true);
  /** Retrieves cloud groups; local-only items stay visible through the merge card. */
  const load=()=>{setLoading(true);return api('/api/v1/watchlists').then(setGroups).catch(x=>setNotice(x instanceof Error?x.message:'请先登录后查看云端自选')).finally(()=>setLoading(false));};
  useEffect(()=>{api('/api/v1/watchlists').then(setGroups).catch(x=>setNotice(x instanceof Error?x.message:'请先登录后查看云端自选')).finally(()=>setLoading(false));},[]);
  /** Creates a named cloud group for organizing funds with the same research goal. */
  const create=async(e:FormEvent)=>{e.preventDefault();if(!name.trim())return setNotice('请填写分组名称');try{await api('/api/v1/watchlists',{method:'POST',body:JSON.stringify({name})});setNotice('已创建自选分组');void load();}catch(x){setNotice(x instanceof Error?x.message:'创建分组失败');}};
  /** Adds one valid six-digit fund code to the selected cloud group. */
  const add=async(groupId:string)=>{if(!/^\d{6}$/.test(code))return setNotice('请输入 6 位基金代码');try{await api(`/api/v1/watchlists/${groupId}/items`,{method:'POST',body:JSON.stringify({fundCode:code})});setCode('');setNotice('已加入该分组');void load();}catch(x){setNotice(x instanceof Error?x.message:'加入失败');}};
  /** Merges browser-only watch codes only after a signed-in user explicitly requests it. */
  const merge=async()=>{const local=guestWatch();if(!local.length){setNotice('此设备没有待同步的自选');return;}try{const result=await api('/api/v1/watchlists/merge-local',{method:'POST',body:JSON.stringify({fundCodes:local})});clearGuestWatch();setNotice(`同步完成：新增 ${result.added} 只，已有 ${result.existing} 只`);void load();}catch(x){setNotice(x instanceof Error?x.message:'同步失败，请登录后重试');}};
  const localCount=guestWatch().length;
  return <AppShell notice={notice}><section className="workspace watchlist-page"><p>WATCHLIST</p><h2>我的自选</h2><p className="page-intro">关注感兴趣的基金。未登录时的自选只保存在当前设备。</p>
    {localCount>0&&<div className="local-watch"><div><b>此设备有 {localCount} 只待同步自选</b><small>登录后同步到云端，换设备也能继续查看。</small></div><button className="primary" onClick={merge}>同步到云端</button></div>}
    <form className="inline-form" onSubmit={create}><label>新建分组<input value={name} onChange={e=>setName(e.target.value)} placeholder="例如：长期关注"/></label><button>新建分组</button></form>
    {loading?<div className="empty-panel">正在加载自选…</div>:groups.length?groups.map(g=><div key={g.groupId} className="watch-group"><div className="group-heading"><div><b>{g.displayName}</b><small>{g.items.length} 只基金</small></div></div>
      {g.items.length?<div className="fund-code-list">{g.items.map(i=><a key={i.itemId} href={`/?code=${i.fundCode.value}`}>{i.fundCode.value}<span>查看研究</span></a>)}</div>:<div className="empty-inline">这个分组还是空的，输入基金代码开始关注。</div>}
      <form className="add-fund-form" onSubmit={e=>{e.preventDefault();void add(g.groupId);}}><label>基金代码<input value={code} onChange={e=>setCode(e.target.value.replace(/\D/g,'').slice(0,6))} placeholder="6 位基金代码"/></label><button>加入分组</button></form>
    </div>):<div className="empty-panel"><b>还没有云端自选</b><span>查询基金后点击“加入自选”，或先创建一个分组。</span></div>}
  </section></AppShell>;
}
