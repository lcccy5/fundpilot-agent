'use client';
import {useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

export default function McpPage(){
  const[info,setInfo]=useState<{schemaHash?:string;tools?:string[];sideEffects?:string;endpointRef?:string}|null>(null);
  const[notice,setNotice]=useState('MCP 管理仅 ADMIN 可见；Schema 变化会暂停旧计划');
  useEffect(()=>{api('/api/v1/mcp/capabilities').then(setInfo).catch(e=>setNotice(e instanceof Error?e.message:'需要 ADMIN'));},[]);
  return <AppShell notice={notice}><section className="workspace"><p>MCP</p><h2>受控 Capability</h2>
    {info?<><p>schemaHash {info.schemaHash}</p><p>{info.endpointRef} · {info.sideEffects}</p><ol>{(info.tools??[]).map(t=><li key={t}>{t}</li>)}</ol></>:<p>无权查看或尚未加载。</p>}
  </section></AppShell>;
}
