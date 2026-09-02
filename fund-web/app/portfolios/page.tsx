'use client';
import {FormEvent,useEffect,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';

type Portfolio={portfolioId:{value:string};displayName:string};

export default function PortfoliosPage(){
  const[list,setList]=useState<Portfolio[]>([]);const[name,setName]=useState('长期组合');const[selected,setSelected]=useState('');const[notice,setNotice]=useState('流水导入后可重建持仓');
  const[fundCode,setFundCode]=useState('000001');const[shares,setShares]=useState('10');const[amount,setAmount]=useState('100');const[nav,setNav]=useState('10');
  const[positions,setPositions]=useState<unknown>(null);const[returns,setReturns]=useState<unknown>(null);const[file,setFile]=useState<File|null>(null);const[batch,setBatch]=useState<{batchId:string;fileSha256:string;validRows:number;invalidRows:number}|null>(null);
  const load=()=>api('/api/v1/portfolios').then((rows:Portfolio[])=>{setList(rows);if(!selected&&rows[0])setSelected(rows[0].portfolioId.value);}).catch(x=>setNotice(x instanceof Error?x.message:'请先登录'));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(()=>{void load();},[]);
  const create=async(e:FormEvent)=>{e.preventDefault();try{const p=await api('/api/v1/portfolios',{method:'POST',body:JSON.stringify({name})});setSelected(p.portfolioId.value);load();}catch(x){setNotice(x instanceof Error?x.message:'新建组合失败');}};
  const append=async(e:FormEvent)=>{e.preventDefault();if(!selected)return;try{await api(`/api/v1/portfolios/${selected}/transactions`,{method:'POST',body:JSON.stringify({fundCode,type:'SUBSCRIPTION',tradeDate:'2026-01-02',confirmDate:'2026-01-03',shares,grossAmount:amount,fee:'0',confirmedNav:nav,idempotencyKey:`manual-${Date.now()}`})});setNotice('已追加申购流水');}catch(x){setNotice(x instanceof Error?x.message:'追加申购失败');}};
  const show=async()=>{if(!selected)return;try{setPositions(await api(`/api/v1/portfolios/${selected}/positions`));setReturns(await api(`/api/v1/portfolios/${selected}/returns`));}catch(x){setNotice(x instanceof Error?x.message:'查询失败');}};
  const preview=async()=>{if(!selected||!file){setNotice('请先选择组合和导入文件');return;}try{const body=new FormData();body.append('file',file);const result=await api(`/api/v1/portfolios/${selected}/imports/preview`,{method:'POST',body});setBatch(result);setNotice(`预检完成：有效 ${result.validRows}，错误 ${result.invalidRows}`);}catch(x){setNotice(x instanceof Error?x.message:'导入预检失败');}};
  const commit=async()=>{if(!selected||!batch){setNotice('请先完成导入预检');return;}try{await api(`/api/v1/portfolios/${selected}/imports/${batch.batchId}/commit`,{method:'POST',body:JSON.stringify({fileSha256:batch.fileSha256})});setNotice('导入已提交');show();}catch(x){setNotice(x instanceof Error?x.message:'提交导入失败');}};
  return <AppShell notice={notice}><section className="workspace"><p>PORTFOLIO</p><h2>我的组合</h2>
    <form className="auth-form" onSubmit={create}><input value={name} onChange={e=>setName(e.target.value)}/><button>新建组合</button></form>
    <select value={selected} onChange={e=>setSelected(e.target.value)}>{list.map(p=><option key={p.portfolioId.value} value={p.portfolioId.value}>{p.displayName}</option>)}</select>
    <form className="auth-form" onSubmit={append}><input value={fundCode} onChange={e=>setFundCode(e.target.value)}/><input value={shares} onChange={e=>setShares(e.target.value)} placeholder="份额"/><input value={amount} onChange={e=>setAmount(e.target.value)} placeholder="金额"/><input value={nav} onChange={e=>setNav(e.target.value)} placeholder="净值"/><button>追加申购</button></form>
    <p><button className="primary" onClick={show}>查看持仓与收益</button></p>
    <pre>{JSON.stringify({positions,returns},null,2)}</pre>
    <h3>CSV / Excel 导入</h3>
    <input type="file" accept=".csv,.xlsx" onChange={e=>setFile(e.target.files?.[0]??null)}/>
    <p><button className="primary" onClick={preview}>预检</button> <button className="primary" onClick={commit}>确认导入</button></p>
  </section></AppShell>;
}
