'use client';
import {FormEvent,useEffect,useMemo,useState} from 'react';
import Link from 'next/link';
import AppShell from '../../components/AppShell';
import {api,clearGuestWatch,guestWatch} from '../../lib/session';

type FundCode=string|{value:string};
type Item={itemId:string;fundCode:FundCode;version:number};
type Group={groupId:string;displayName:string;version:number;items:Item[]};
type FundProfile={fundCode:string;name:string;fundType?:string;managementCompany?:string;fundManager?:string};

const codeOf=(item:Item)=>typeof item.fundCode==='string'?item.fundCode:item.fundCode.value;
const hydrateProfiles=(next:Group[],onProfile:(fundCode:string,profile:FundProfile)=>void,onMiss?:(fundCode:string)=>void)=>{
  const fundCodes=[...new Set(next.flatMap(group=>group.items.map(codeOf)))].filter(Boolean);
  void Promise.all(fundCodes.map(async fundCode=>{try{onProfile(fundCode,await api<FundProfile>(`/api/v1/funds/${fundCode}`));}catch{onMiss?.(fundCode);}}));
};

/** Gives users an immediately recognizable inventory of the funds they follow. */
export default function WatchlistsPage(){
  const[groups,setGroups]=useState<Group[]>([]);
  const[profiles,setProfiles]=useState<Record<string,FundProfile>>({});
  const[groupName,setGroupName]=useState('');
  const[codes,setCodes]=useState<Record<string,string>>({});
  const[notice,setNotice]=useState('正在加载你的自选…');
  const[loading,setLoading]=useState(true);const[failed,setFailed]=useState(false);
  const[addingTo,setAddingTo]=useState<string>();
  const[removingItem,setRemovingItem]=useState<string>();
  const[recentlyAdded,setRecentlyAdded]=useState<string>();
  const[profileMiss,setProfileMiss]=useState<Record<string,boolean>>({});
  const[creating,setCreating]=useState(false);

  const showProfiles=(next:Group[])=>hydrateProfiles(next,(fundCode,profile)=>setProfiles(current=>({...current,[fundCode]:profile})),(fundCode)=>setProfileMiss(current=>({...current,[fundCode]:true})));
  const load=async(announce=true)=>{
    try{const next=await api<Group[]>('/api/v1/watchlists');setFailed(false);setGroups(next);showProfiles(next);if(announce){const count=next.reduce((sum,group)=>sum+group.items.length,0);setNotice(count?`已加载 ${count} 只自选基金`:'还没有自选基金，先添加一只吧');}}
    catch(x){setFailed(true);setNotice(x instanceof Error?x.message:'请先登录后查看云端自选');}
    finally{setLoading(false);}
  };
  useEffect(()=>{api<Group[]>('/api/v1/watchlists').then(next=>{setFailed(false);setGroups(next);showProfiles(next);const count=next.reduce((sum,group)=>sum+group.items.length,0);setNotice(count?`已加载 ${count} 只自选基金`:'还没有自选基金，先添加一只吧');}).catch(x=>{setFailed(true);setNotice(x instanceof Error?x.message:'请先登录后查看云端自选');}).finally(()=>setLoading(false));},[]);

  const create=async(e:FormEvent)=>{e.preventDefault();if(creating)return;const name=groupName.trim();if(!name)return setNotice('请填写分组名称');setCreating(true);try{const created=await api<Group>('/api/v1/watchlists',{method:'POST',body:JSON.stringify({name})});setGroups(current=>[...current,created]);setGroupName('');setNotice(`已创建“${created.displayName}”分组`);}catch(x){setNotice(x instanceof Error?x.message:'创建分组失败');}finally{setCreating(false);}};
  const add=async(group:Group)=>{const fundCode=codes[group.groupId]??'';if(!/^\d{6}$/.test(fundCode))return setNotice('请输入 6 位基金代码');setAddingTo(group.groupId);try{const updated=await api<Group>(`/api/v1/watchlists/${group.groupId}/items`,{method:'POST',body:JSON.stringify({fundCode})});setGroups(current=>current.map(row=>row.groupId===group.groupId?updated:row));setCodes(current=>({...current,[group.groupId]:''}));setRecentlyAdded(fundCode);setNotice(`${fundCode} 已加入“${group.displayName}”`);showProfiles([updated]);}catch(x){setNotice(x instanceof Error?x.message:'加入失败');}finally{setAddingTo(undefined);}};
  const remove=async(group:Group,item:Item)=>{const fundCode=codeOf(item),fundName=profiles[fundCode]?.name??`基金 ${fundCode}`;if(!window.confirm(`确定从“${group.displayName}”中删除 ${fundName} 吗？`))return;setRemovingItem(item.itemId);try{const updated=await api<Group>(`/api/v1/watchlists/${group.groupId}/items/${item.itemId}?version=${item.version}`,{method:'DELETE'});setGroups(current=>current.map(row=>row.groupId===group.groupId?updated:row));setRecentlyAdded(current=>current===fundCode?undefined:current);setNotice(`已从“${group.displayName}”删除 ${fundName}`);}catch(x){setNotice(x instanceof Error?x.message:'删除失败，请刷新后重试');}finally{setRemovingItem(undefined);}};
  const merge=async()=>{const local=guestWatch();if(!local.length)return setNotice('此设备没有待同步的自选');try{const result=await api<{added:number;existing:number}>('/api/v1/watchlists/merge-local',{method:'POST',body:JSON.stringify({fundCodes:local})});clearGuestWatch();await load(false);setNotice(`同步完成：新增 ${result.added} 只，已有 ${result.existing} 只`);}catch(x){setNotice(x instanceof Error?x.message:'同步失败，请登录后重试');}};

  const localCodes=guestWatch();
  const total=useMemo(()=>groups.reduce((sum,group)=>sum+group.items.length,0),[groups]);
  return <AppShell notice={notice}><section className="workspace watchlist-page">
    <div className="watchlist-title"><div><p>WATCHLIST</p><h2>我的自选</h2><span>集中查看你关注的基金，从这里继续研究。</span></div><div className="watchlist-total"><b>{total}</b><small>只基金 · {groups.length} 个分组</small></div></div>
    {localCodes.length>0&&<section className="local-watch"><div><b>此设备还有 {localCodes.length} 只自选未同步</b><small>{localCodes.join('、')} · 同步后换设备也能看到</small></div><button className="primary" onClick={merge}>同步到我的账户</button></section>}
    {loading?<div className="empty-panel">正在加载自选基金…</div>:failed?<div className="empty-panel"><b>自选暂时加载失败</b><span>请稍后重试。这不是一份空的自选。</span></div>:groups.length?<div className="watch-groups">{groups.map(group=><section key={group.groupId} className="watch-group">
      <div className="group-heading"><div><b>{group.displayName}</b><small>{group.items.length} 只基金</small></div></div>
      {group.items.length?<div className="watch-fund-grid">{group.items.map(item=>{const fundCode=codeOf(item),profile=profiles[fundCode];return <article key={item.itemId} className={`watch-fund-card ${recentlyAdded===fundCode?'just-added':''}`}>
        <div className="watch-fund-main"><span className="fund-avatar">{profile?.name?.slice(0,1)??'基'}</span><div><strong>{profile?.name??`基金 ${fundCode}`}</strong><small><code>{fundCode}</code>{profile?.fundType&&` · ${profile.fundType}`}</small></div></div>
        <div className="watch-fund-meta"><span>{profile?.managementCompany??(profileMiss[fundCode]?'资料暂不可用':'基金资料加载中')}</span>{profile?.fundManager&&<span>基金经理 {profile.fundManager}</span>}</div>
        <div className="watch-fund-actions"><Link href={`/?code=${fundCode}`}>查看详情与研究 <span aria-hidden="true">→</span></Link><button type="button" className="remove-watch" disabled={removingItem===item.itemId} onClick={()=>void remove(group,item)}>{removingItem===item.itemId?'删除中…':'删除'}</button></div>
      </article>})}</div>:<div className="watch-empty"><b>这个分组还没有基金</b><span>在下方输入 6 位基金代码即可加入。</span></div>}
      <form className="add-fund-form" onSubmit={e=>{e.preventDefault();void add(group);}}><label><span>添加基金</span><input aria-label={`添加基金到${group.displayName}`} inputMode="numeric" value={codes[group.groupId]??''} onChange={e=>setCodes(current=>({...current,[group.groupId]:e.target.value.replace(/\D/g,'').slice(0,6)}))} placeholder="输入 6 位基金代码"/></label><button disabled={addingTo===group.groupId}>{addingTo===group.groupId?'正在加入…':'加入自选'}</button></form>
    </section>)}</div>:<div className="watch-empty primary-empty"><b>你的自选还是空的</b><span>先创建一个分组，再加入想持续关注的基金。</span></div>}
    <details className="create-group-panel"><summary>＋ 新建自选分组</summary><form onSubmit={create}><label>分组名称<input value={groupName} onChange={e=>setGroupName(e.target.value)} placeholder="例如：长期关注、机器人主题"/></label><button disabled={creating}>{creating?'正在创建…':'创建分组'}</button></form></details>
  </section></AppShell>;
}
