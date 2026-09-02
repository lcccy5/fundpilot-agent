'use client';
import {FormEvent,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Question={id:string;prompt:string;choices:{value:number;label:string}[]};

export default function AccountPage(){
  const[questions,setQuestions]=useState<Question[]>([]);const[answers,setAnswers]=useState<Record<string,number>>({});const[profile,setProfile]=useState<unknown>(null);const[notice,setNotice]=useState('风险画像由问卷评分生成，模型不能改写');
  useEffect(()=>{api('/api/v1/risk-questionnaire').then(q=>setQuestions(q.questions??[])).catch(x=>setNotice(x instanceof Error?x.message:'请先登录'));api('/api/v1/users/me/risk-profile').then(setProfile).catch(()=>undefined);},[]);
  const submit=async(e:FormEvent)=>{e.preventDefault();try{const result=await api('/api/v1/users/me/risk-profile',{method:'PUT',body:JSON.stringify({questionnaireVersion:'risk-questionnaire-v1',answers})});setProfile(result);setNotice('画像已保存');}catch(x){setNotice(x instanceof Error?x.message:'保存画像失败');}};
  return <AppShell notice={notice}><section className="workspace"><p>RISK PROFILE</p><h2>账户与风险画像</h2>
    <pre>{JSON.stringify(profile,null,2)}</pre>
    <form className="auth-form" onSubmit={submit}>{questions.map(q=><label key={q.id}>{q.prompt}<select value={answers[q.id]??''} onChange={e=>setAnswers(a=>({...a,[q.id]:Number(e.target.value)}))}><option value="">请选择</option>{q.choices.map(c=><option key={c.value} value={c.value}>{c.label}</option>)}</select></label>)}<button>保存画像</button></form>
  </section></AppShell>;
}
