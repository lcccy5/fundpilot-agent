'use client';
import {FormEvent,useState} from 'react';
import {api,setSession} from '../../lib/session';
import AppShell from '../../components/AppShell';
import {safeNext} from '../../lib/polish.mjs';

/** Renders account creation and stores the short-lived access session after a successful registration. */
export default function RegisterPage(){
  const[username,setUsername]=useState('');const[displayName,setDisplayName]=useState('');const[password,setPassword]=useState('');const[notice,setNotice]=useState('密码至少 6 位，不会保存在浏览器存储中');const[submitting,setSubmitting]=useState(false);
  /** Submits account details and keeps backend validation errors beside the registration form. */
  const submit=async(e:FormEvent)=>{e.preventDefault();if(submitting)return;if(!username.trim()||!displayName.trim()||password.length<6)return setNotice('请填写用户名、显示名，以及至少 6 位密码');setSubmitting(true);try{const data=await api('/api/v1/auth/register',{method:'POST',body:JSON.stringify({username,displayName,password})});setSession(data.accessToken,data.accessTokenExpiresAt);location.href=safeNext(window.location.search,'/watchlists');}catch(x){setNotice(x instanceof Error?x.message:'注册失败');setSubmitting(false);}};
  return <AppShell notice={notice}><section className="workspace"><p>账户</p><h2>注册</h2><form className="auth-form" data-hydrated="1" onSubmit={submit}><label>用户名<input autoComplete="username" value={username} onChange={e=>setUsername(e.target.value)} placeholder="用户名"/></label><label>显示名<input value={displayName} onChange={e=>setDisplayName(e.target.value)} placeholder="显示名"/></label><label>密码<input type="password" autoComplete="new-password" minLength={6} value={password} onChange={e=>setPassword(e.target.value)} placeholder="密码（6-128）"/></label><button type="submit" disabled={submitting}>{submitting?'正在创建…':'创建账号'}</button></form></section></AppShell>;
}
