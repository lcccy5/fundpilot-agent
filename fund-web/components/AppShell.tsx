'use client';
import {ReactNode,useEffect,useState} from 'react';
import Link from 'next/link';
import {api,clearSession,currentAccessToken,restoreSession} from '../lib/session';

const links=[['/', '⌂','总览'],['/watchlists','☆','我的自选'],['/portfolios','▣','我的组合'],['/runs','◌','研究任务'],['/reports','▤','月报'],['/notifications','◉','通知']] as const;

function noticeTone(notice:string){
  if(/失败|错误|无法|没有权限|请先登录|未完成|暂不可用/.test(notice))return 'error';
  if(/正在/.test(notice))return 'progress';
  if(/已|完成|同步/.test(notice))return 'success';
  return 'info';
}

/** Provides the shared application navigation and exposes the actual page status. */
export default function AppShell({children,notice,title='基金研究',kicker='FUND RESEARCH'}:{children:ReactNode;notice?:string;title?:string;kicker?:string}){
  const[user,setUser]=useState<{displayName?:string;username?:string;roles?:string[]}|null>(null);
  const[menuOpen,setMenuOpen]=useState(false);
  useEffect(()=>{void restoreSession().then(ok=>{if(!ok&&!currentAccessToken())return;api('/api/v1/users/me').then(setUser).catch(()=>setUser(null));});},[]);
  const logout=async()=>{try{await api('/api/v1/auth/logout',{method:'POST'});}finally{clearSession();location.href='/login';}};
  const admin=Boolean(user?.roles?.includes('ADMIN'));
  const active=typeof window==='undefined'?'':window.location.pathname;
  return <main className="shell"><header className="topbar"><Link className="brand" href="/"><i>F</i><span>FundPilot</span><small>基金研究</small></Link>
    <button type="button" className="nav-toggle" aria-expanded={menuOpen} aria-controls="primary-nav" onClick={()=>setMenuOpen(open=>!open)}>{menuOpen?'关闭菜单':'菜单'}</button>
    <nav id="primary-nav" aria-label="主导航" className={menuOpen?'open':''}>{links.map(([href,icon,label])=><Link key={href} href={href} aria-current={active===href?'page':undefined} className={active===href?'active':''} onClick={()=>setMenuOpen(false)}><i>{icon}</i>{label}</Link>)}{admin&&<Link href="/mcp" className={active==='/mcp'?'active':''} onClick={()=>setMenuOpen(false)}><i>⚙</i>工具治理</Link>}<Link className="nav-account" href={user?'/account':'/login'} onClick={()=>setMenuOpen(false)}>{user?'账户与风险画像':'登录 / 注册'}</Link>{user&&<button type="button" className="nav-account" onClick={logout}>退出</button>}</nav>
    <div className="account-chip">{user?<><Link className="avatar" href="/account" aria-label="查看账户与风险画像">{(user.displayName||user.username||'用户').slice(0,1)}</Link><Link className="account-name" href="/account">{user.displayName||user.username||'用户'}</Link><button onClick={logout}>退出</button></>:<Link href="/login">登录 / 注册</Link>}</div>
  </header><section className="main"><header className="page-head"><div><p>{kicker}</p><h1>{title}</h1></div>{notice&&<label className={`notice notice-${noticeTone(notice)}`}><i/> {notice}</label>}</header>{children}</section></main>;
}
