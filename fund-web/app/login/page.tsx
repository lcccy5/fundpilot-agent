'use client';
import {FormEvent,useState} from 'react';
import {api,setSession} from '../../lib/session';
import AppShell from '../../components/AppShell';

export default function LoginPage(){
  const[username,setUsername]=useState('');const[password,setPassword]=useState('');const[notice,setNotice]=useState('登录后才会访问自选、组合和个性化 Agent');
  const submit=async(e:FormEvent)=>{e.preventDefault();try{const data=await api('/api/v1/auth/login',{method:'POST',body:JSON.stringify({username,password})});setSession(data.accessToken,data.accessTokenExpiresAt);location.href='/';}catch(x){setNotice(x instanceof Error?x.message:'登录失败');}};
  return <AppShell notice={notice}><section className="workspace"><p>ACCOUNT</p><h2>登录</h2><form className="auth-form" data-hydrated="1" onSubmit={submit}><input autoComplete="username" value={username} onChange={e=>setUsername(e.target.value)} placeholder="用户名"/><input type="password" autoComplete="current-password" value={password} onChange={e=>setPassword(e.target.value)} placeholder="密码"/><button type="submit">登录</button><a href="/register">没有账号？注册</a></form></section></AppShell>;
}
