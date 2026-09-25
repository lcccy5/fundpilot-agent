'use client';
import {FormEvent,useEffect,useState} from 'react';
import {api,setSession} from '../../lib/session';
import AppShell from '../../components/AppShell';

function safeNext(){
  const next=new URLSearchParams(window.location.search).get('next')??'/';
  return next.startsWith('/')&&!next.startsWith('//')?next:'/';
}

export default function LoginPage(){
  const[username,setUsername]=useState('');const[password,setPassword]=useState('');const[notice,setNotice]=useState('登录后才会访问自选、组合和个性化 Agent');const[submitting,setSubmitting]=useState(false);const[registerHref,setRegisterHref]=useState('/register');
  useEffect(()=>{setRegisterHref(`/register?next=${encodeURIComponent(safeNext())}`);},[]);
  const submit=async(e:FormEvent)=>{e.preventDefault();if(submitting)return;setSubmitting(true);try{const data=await api('/api/v1/auth/login',{method:'POST',body:JSON.stringify({username,password})});setSession(data.accessToken,data.accessTokenExpiresAt);location.href=safeNext();}catch(x){setNotice(x instanceof Error?x.message:'登录失败');setSubmitting(false);}};
  return <AppShell notice={notice} title="登录" kicker="ACCOUNT"><section className="workspace"><p>账户</p><h2>登录</h2><form className="auth-form" data-hydrated="1" onSubmit={submit}><label>用户名<input autoComplete="username" value={username} onChange={e=>setUsername(e.target.value)} placeholder="用户名"/></label><label>密码<input type="password" autoComplete="current-password" value={password} onChange={e=>setPassword(e.target.value)} placeholder="密码"/></label><button type="submit" disabled={submitting}>{submitting?'正在登录…':'登录'}</button><a href={registerHref}>没有账号？注册</a></form></section></AppShell>;
}
