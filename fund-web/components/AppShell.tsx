'use client';
import {ReactNode,useEffect,useState} from 'react';
import Link from 'next/link';
import {usePathname} from 'next/navigation';
import {api,clearSession,currentAccessToken,restoreSession} from '../lib/session';
import {noticeTone,shellTitle} from '../lib/polish.mjs';

const links=[['/', '⌂','总览'],['/watchlists','☆','我的自选'],['/portfolios','▣','我的组合'],['/runs','◌','研究任务'],['/reports','▤','月报'],['/notifications','◉','通知']] as const;

/** Provides the shared application navigation and exposes the actual page status. */
export default function AppShell({children,notice}:{children:ReactNode;notice?:string}){
  const path=usePathname()||'/';
  const[user,setUser]=useState<{displayName?:string;username?:string;roles?:string[]}|null>(null);
  const[menuOpen,setMenuOpen]=useState(false);
  useEffect(()=>{void restoreSession().then(ok=>{if(!ok&&!currentAccessToken())return;api('/api/v1/users/me').then(setUser).catch(()=>setUser(null));});},[]);
  useEffect(()=>{setMenuOpen(false);},[path]);
  const logout=async()=>{try{await api('/api/v1/auth/logout',{method:'POST'});}finally{clearSession();location.href='/login';}};
  const admin=Boolean(user?.roles?.includes('ADMIN'));
  const [kicker,title]=shellTitle(path);
  const tone=notice?noticeTone(notice):'info';
  return <main className="shell"><a className="skip-link" href="#content">跳到主要内容</a><header className="topbar"><Link className="brand" href="/"><i>F</i><span>FundPilot</span><small>基金研究</small></Link>
    <button type="button" className="nav-toggle" aria-expanded={menuOpen} aria-controls="primary-nav" onClick={()=>setMenuOpen(open=>!open)}>{menuOpen?'关闭菜单':'菜单'}</button>
    <nav id="primary-nav" aria-label="主导航" className={menuOpen?'open':''}>{links.map(([href,icon,label])=><Link key={href} href={href} aria-current={path===href?'page':undefined} className={path===href?'active':''}><i>{icon}</i>{label}</Link>)}{admin&&<Link href="/mcp" className={path==='/mcp'?'active':''}><i>⚙</i>工具治理</Link>}<Link className="nav-account" href={user?'/account':'/login'}>{user?'账户与风险画像':'登录 / 注册'}</Link>{user&&<button type="button" className="nav-account" onClick={()=>void logout()}>退出</button>}</nav>
    <div className="account-chip">{user?<><Link className="avatar" href="/account" aria-label="查看账户与风险画像">{(user.displayName||user.username||'用户').slice(0,1)}</Link><Link className="account-name" href="/account">{user.displayName||user.username||'用户'}</Link><button type="button" onClick={()=>void logout()}>退出</button></>:<Link href="/login">登录 / 注册</Link>}</div>
  </header><section className="main" id="content"><header className="page-head"><div><p>{kicker}</p><h1>{title}</h1></div>{notice&&<p className={`notice notice-${tone}`} role={tone==='error'?'alert':'status'}>{notice}</p>}</header>{children}</section></main>;
}
