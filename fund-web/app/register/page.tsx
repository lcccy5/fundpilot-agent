'use client';
import {FormEvent,useState} from 'react';
import {api,setSession} from '../../lib/session';
import AppShell from '../../components/AppShell';

export default function RegisterPage(){
  const[username,setUsername]=useState('');const[displayName,setDisplayName]=useState('');const[password,setPassword]=useState('');const[notice,setNotice]=useState('密码至少 10 位，不会保存在浏览器存储中');
  const submit=async(e:FormEvent)=>{e.preventDefault();try{const data=await api('/api/v1/auth/register',{method:'POST',body:JSON.stringify({username,displayName,password})});setSession(data.accessToken,data.accessTokenExpiresAt);location.href='/watchlists';}catch(x){setNotice(x instanceof Error?x.message:'注册失败');}};
  return <AppShell notice={notice}><section className="workspace"><p>ACCOUNT</p><h2>注册</h2><form className="auth-form" data-hydrated="1" onSubmit={submit}><input autoComplete="username" value={username} onChange={e=>setUsername(e.target.value)} placeholder="用户名"/><input value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="显示名"/><input type="password" autoComplete="new-password" value={password} onChange={e=>setPassword(e.target.value)} placeholder="密码（10-128）"/><button type="submit">创建账号</button></form></section></AppShell>;
}
