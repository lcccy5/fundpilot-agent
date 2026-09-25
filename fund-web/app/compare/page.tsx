'use client';
import {FormEvent,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';
import {comparisonRequest,presentComparison} from '../../lib/fund-compare.mjs';

const iso=(offset:number)=>{const date=new Date();date.setMonth(date.getMonth()-offset);return date.toISOString().slice(0,10);};

export default function ComparePage(){
  const[codes,setCodes]=useState(['000001','110022','']);
  const[startDate,setStartDate]=useState(iso(12));
  const[endDate,setEndDate]=useState(iso(0));
  const[notice,setNotice]=useState('同一时间区间对比区间收益和最大回撤。单位净值变化不会写成持有收益。');
  const[view,setView]=useState<ReturnType<typeof presentComparison>|null>(null);
  const submit=async(e:FormEvent)=>{
    e.preventDefault();
    try{
      const body=comparisonRequest(codes,startDate,endDate);
      const result=await api('/api/v1/fund-comparisons',{method:'POST',body:JSON.stringify(body)});
      const next=presentComparison(result);
      setView(next);
      setNotice(next.notes.length?next.notes.join('；'):`已按 ${next.basis} 对比 ${next.windowText}`);
    }catch(error){setView(null);setNotice(error instanceof Error?error.message:'对比失败');}
  };
  return <AppShell notice={notice} title="基金对比" kicker="COMPARE"><section className="workspace"><p>对比</p><h2>两到三只基金，同一区间</h2><p className="page-intro">收益和回撤都来自单位净值。缺数据或口径不一致时会标出，不会把单位净值变化写成持有收益。</p>
    <form className="auth-form" onSubmit={submit}>
      {codes.map((code,index)=><label key={index}>基金 {index+1}<input value={code} onChange={e=>setCodes(current=>current.map((item,itemIndex)=>itemIndex===index?e.target.value.replace(/\D/g,'').slice(0,6):item))} placeholder="6 位代码，第三只可选"/></label>)}
      <label>开始<input type="date" value={startDate} onChange={e=>setStartDate(e.target.value)}/></label>
      <label>结束<input type="date" value={endDate} onChange={e=>setEndDate(e.target.value)}/></label>
      <button>对比</button>
    </form>
    {view&&<div className="holding-table"><div className="holding-head"><span>基金</span><span>区间</span><span>回撤</span></div>{view.rows.map(row=><div className="holding-row" key={row.code}><span>{row.code}</span><span>{row.intervalReturn}</span><span>{row.drawdown}</span></div>)}<p>共同区间 {view.windowText} · 口径 {view.basis}</p>{view.notes.map(note=><p key={note}>{note}</p>)}</div>}
  </section></AppShell>;
}
