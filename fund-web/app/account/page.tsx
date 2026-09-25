'use client';
import {FormEvent,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';
import {assessmentDate,displayTime,firstUnanswered,profileName} from '../../lib/polish.mjs';

type Question={id:string;prompt:string;choices:{value:number;label:string}[]};
type Profile={riskLevel?:string;riskProfile?:string;completedAt?:string;assessmentDate?:string;confirmedAt?:string;level?:string|{name?:string};[key:string]:unknown};

/** Collects a risk questionnaire and presents its result as a user-facing profile. */
export default function AccountPage(){
  const[questions,setQuestions]=useState<Question[]>([]);const[questionsReady,setQuestionsReady]=useState(false);const[questionsFailed,setQuestionsFailed]=useState(false);const[answers,setAnswers]=useState<Record<string,number>>({});const[profile,setProfile]=useState<Profile|null>(null);const[profileReady,setProfileReady]=useState(false);const[saving,setSaving]=useState(false);const[notice,setNotice]=useState('风险画像来自问卷评分，用于帮助理解研究内容');
  /** Loads questions and any existing profile independently so an unavailable result does not hide the form. */
  useEffect(()=>{api('/api/v1/risk-questionnaire').then(q=>{setQuestions(q.questions??[]);setQuestionsFailed(false);}).catch(x=>{setQuestionsFailed(true);setNotice(x instanceof Error?x.message:'请先登录后填写问卷');}).finally(()=>setQuestionsReady(true));api('/api/v1/users/me/risk-profile').then(setProfile).catch(()=>setProfile(null)).finally(()=>setProfileReady(true));},[]);
  const missingId=profile?null:firstUnanswered(questions,answers);
  useEffect(()=>{if(!profileReady||profile||!missingId)return;document.getElementById(`question-${missingId}`)?.scrollIntoView({behavior:'smooth',block:'center'});},[profileReady,profile,missingId]);
  /** Saves a complete questionnaire; incomplete answers remain on the page for correction. */
  const submit=async(e:FormEvent)=>{e.preventDefault();if(saving)return;const missing=firstUnanswered(questions,answers);if(missing){setNotice('请先完成标出的题目');document.getElementById(`question-${missing}`)?.scrollIntoView({behavior:'smooth',block:'center'});return;}setSaving(true);try{const result=await api('/api/v1/users/me/risk-profile',{method:'PUT',body:JSON.stringify({questionnaireVersion:'risk-questionnaire-v1',answers})});setProfile(result);setNotice('风险画像已保存');}catch(x){setNotice(x instanceof Error?x.message:'保存画像失败');}finally{setSaving(false);}};
  const assessedAt=assessmentDate(profile);
  return <AppShell notice={notice}><section className="workspace profile-page"><p>账户</p><h2>账户与风险画像</h2><p className="page-intro">完成问卷后，研究结果可以更贴近你的风险承受范围。问卷结果不构成投资建议。</p>
    <div className="profile-card"><small>当前风险画像</small><b>{profileName(profile)}</b><span>{assessedAt?`评估日期：${displayTime(assessedAt)}`:'完成问卷后展示评估日期'}</span></div>
    <form className="questionnaire" onSubmit={submit}><h3>风险承受能力问卷</h3>{questions.length?questions.map((q,index)=><label key={q.id} id={`question-${q.id}`} className={q.id===missingId?'unanswered':''}><span>{index+1}. {q.prompt}{q.id===missingId?' · 请先完成这题':''}</span><select value={answers[q.id]??''} onChange={e=>{const value=e.target.value;setAnswers(a=>value===''?Object.fromEntries(Object.entries(a).filter(([id])=>id!==q.id)):({...a,[q.id]:Number(value)}));}}><option value="">请选择</option>{q.choices.map(c=><option key={c.value} value={c.value}>{c.label}</option>)}</select></label>):questionsFailed?<div className="empty-panel"><b>问卷没有加载出来</b><span>请稍后重试。这不是一份空白问卷。</span></div>:<div className="empty-panel">{questionsReady?'当前没有可填写的题目':'正在加载问卷…'}</div>}<button disabled={!questions.length||saving}>{saving?'正在保存…':'保存风险画像'}</button></form>
  </section></AppShell>;
}
