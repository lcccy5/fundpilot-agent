'use client';
import {ReactNode,useEffect,useState} from 'react';
import {api,clearSession,currentAccessToken,restoreSession} from '../lib/session';

const links=[['/', '▦','总览'],['/watchlists','◒','我的自选'],['/portfolios','▣','我的组合'],['/runs','☰','研究任务'],['/reports','▤','月报'],['/notifications','◉','通知'],['/account','◇','账户与画像']] as const;

export default function AppShell({children,notice}:{children:ReactNode;notice?:string}){
  const[user,setUser]=useState<{displayName?:string;username?:string;roles?:string[]}|null>(null);
  useEffect(()=>{void restoreSession().then(ok=>{if(!ok&&!currentAccessToken())return;api('/api/v1/users/me').then(setUser).catch(()=>setUser(null));});},[]);
  const logout=async()=>{try{await api('/api/v1/auth/logout',{method:'POST'});}finally{clearSession();location.href='/login';}};
  const admin=Boolean(user?.roles?.includes('ADMIN'));
  return <main className="shell"><header className="topbar"><a className="brand" href="/"><i>F</i><span>FundPilot</span><small>基金研究</small></a>
    <nav>{links.slice(0,6).map(([href,icon,label])=><a key={href} href={href}><i>{icon}</i>{label}</a>)}{admin&&<a href="/mcp"><i>⚙</i>工具治理</a>}</nav>
    <div className="account-chip">{user?<><strong>{(user.displayName||user.username||'用户').slice(0,1)}</strong><span>{user.displayName||user.username||'用户'}</span><button onClick={logout}>退出</button></>:<a href="/login">登录 / 注册</a>}</div>
  </header><section className="main"><header className="page-head"><div><p>FUND RESEARCH · DATA FIRST</p><h1>基金研究，从可信数据开始</h1></div><label><i/> {notice??'数据服务正常'}</label></header>{children}</section></main>;
}
