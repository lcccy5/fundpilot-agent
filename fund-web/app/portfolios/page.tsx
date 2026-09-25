'use client';
import {FormEvent,useEffect,useRef,useState} from 'react';
import AppShell from '../../components/AppShell';
import {api} from '../../lib/session';
import {holdingRows,importIssues,moneyText,summaryFigures} from '../../lib/polish.mjs';

type Portfolio={portfolioId:{value:string};displayName:string};
type Position={fundCode?:string|{value:string};fundName?:string;shares?:number|string;confirmedShares?:number|string;marketValue?:number|string|null;profit?:number|string|null;realizedProfit?:number|string|null};
type ReturnInfo={asOfDate?:string;moneyWeightedReturn?:number|string|null;returnStatus?:string;totalValue?:number|string|null;unrealizedProfit?:number|string|null};
type Valuation={asOfDate?:string;totalValue?:number|string|null;unrealizedProfit?:number|string|null;positions?:{position?:Position;fundCode?:Position['fundCode'];value?:number|string|null}[]};
type ImportIssue={sourceRowNumber:number;errorCode?:string;safeMessage?:string};
type ImportPreview={batchId:string;fileSha256:string;validRows:number;invalidRows:number;rows?:ImportIssue[]};
const today=()=>new Date().toISOString().slice(0,10);

/** Presents a portfolio as understandable holdings before exposing bookkeeping tools. */
export default function PortfoliosPage(){
  const[list,setList]=useState<Portfolio[]>([]);const[name,setName]=useState('');const[selected,setSelected]=useState('');const[notice,setNotice]=useState('先创建组合，再记录实际确认的交易');
  const[fundCode,setFundCode]=useState('');const[shares,setShares]=useState('');const[amount,setAmount]=useState('');const[nav,setNav]=useState('');const[tradeDate,setTradeDate]=useState(today());const[confirmDate,setConfirmDate]=useState(today());
  const[positions,setPositions]=useState<Position[]>([]);const[returns,setReturns]=useState<ReturnInfo|null>(null);const[valuation,setValuation]=useState<Valuation|null>(null);const[file,setFile]=useState<File|null>(null);const[batch,setBatch]=useState<ImportPreview|null>(null);const[loading,setLoading]=useState(false);const[saving,setSaving]=useState(false);const[recording,setRecording]=useState(false);const[previewing,setPreviewing]=useState(false);const[committing,setCommitting]=useState(false);const portfolioSeq=useRef(0);
  /** Loads portfolios and selects the first available one without overwriting a selection. */
  const load=()=>api('/api/v1/portfolios').then((rows:Portfolio[])=>{setList(rows);setSelected(current=>current||rows[0]?.portfolioId.value||'');}).catch(x=>setNotice(x instanceof Error?x.message:'请先登录后查看组合'));
  /** Refreshes the selected portfolio summary after a write or a selection change. */
  const show=async(id=selected)=>{if(!id)return;const seq=++portfolioSeq.current;setLoading(true);setPositions([]);setReturns(null);setValuation(null);try{
    const [nextPositions,nextReturns,nextValuation]=await Promise.all([api(`/api/v1/portfolios/${id}/positions`),api(`/api/v1/portfolios/${id}/returns`),api(`/api/v1/portfolios/${id}/valuation`)]);
    if(seq!==portfolioSeq.current)return;
    setPositions(Array.isArray(nextPositions)?nextPositions:((nextPositions as {items?:Position[]}).items??[]));
    setReturns(nextReturns as ReturnInfo);
    setValuation(nextValuation as Valuation);
  }catch(x){if(seq!==portfolioSeq.current)return;setNotice(x instanceof Error?x.message:'暂时无法读取组合数据');}finally{if(seq===portfolioSeq.current)setLoading(false);}};
  // The initial request deliberately runs once; later changes are driven by the explicit selector.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(()=>{api('/api/v1/portfolios').then((rows:Portfolio[])=>{setList(rows);const first=rows[0]?.portfolioId.value;if(first){setSelected(first);void show(first);}}).catch(x=>setNotice(x instanceof Error?x.message:'请先登录后查看组合'));},[]);
  /** Creates a portfolio and immediately makes it the active working portfolio. */
  const create=async(e:FormEvent)=>{e.preventDefault();if(saving)return;if(!name.trim())return setNotice('请为组合填写名称');setSaving(true);try{const p=await api('/api/v1/portfolios',{method:'POST',body:JSON.stringify({name})});setSelected(p.portfolioId.value);setName('');setNotice('组合已创建');void show(p.portfolioId.value);void load();}catch(x){setNotice(x instanceof Error?x.message:'新建组合失败');}finally{setSaving(false);}};
  /** Records a user-entered purchase; empty defaults prevent accidental fabricated transactions. */
  const append=async(e:FormEvent)=>{e.preventDefault();if(recording)return;if(!selected)return setNotice('请先选择组合');if(!/^\d{6}$/.test(fundCode)||!shares||!amount||!nav)return setNotice('请完整填写基金代码、份额、金额和确认净值');setRecording(true);try{await api(`/api/v1/portfolios/${selected}/transactions`,{method:'POST',body:JSON.stringify({fundCode,type:'SUBSCRIPTION',tradeDate,confirmDate,shares,grossAmount:amount,fee:'0',confirmedNav:nav,idempotencyKey:`manual-${Date.now()}`})});setNotice('已记录买入，正在更新持仓');setFundCode('');setShares('');setAmount('');setNav('');void show();}catch(x){setNotice(x instanceof Error?x.message:'记录买入失败');}finally{setRecording(false);}};
  /** Validates an uploaded transaction file before the irreversible import commit. */
  const preview=async()=>{if(previewing)return;if(!selected||!file){setNotice('请先选择组合和导入文件');return;}setPreviewing(true);try{const body=new FormData();body.append('file',file);const result=await api<ImportPreview>(`/api/v1/portfolios/${selected}/imports/preview`,{method:'POST',body});setBatch(result);setNotice(`检查完成：有效 ${result.validRows} 行，错误 ${result.invalidRows} 行`);}catch(x){setNotice(x instanceof Error?x.message:'导入检查失败');}finally{setPreviewing(false);}};
  /** Commits only the preview batch matched to the selected file checksum. */
  const commit=async()=>{if(committing)return;if(!selected||!batch)return setNotice('请先完成导入文件检查');if(batch.invalidRows>0)return setNotice('请先修正错误行后再导入');setCommitting(true);try{await api(`/api/v1/portfolios/${selected}/imports/${batch.batchId}/commit`,{method:'POST',body:JSON.stringify({fileSha256:batch.fileSha256})});setBatch(null);setNotice('导入完成，持仓已更新');void show();}catch(x){setNotice(x instanceof Error?x.message:'提交导入失败');}finally{setCommitting(false);}};
  return <AppShell notice={notice}><section className="workspace portfolio-page"><p>PORTFOLIO</p><h2>我的组合</h2><p className="page-intro">记录实际确认的交易，查看已同步的持仓与收益。数据日期以组合结果为准。</p>
    <div className="portfolio-toolbar"><label>当前组合<select value={selected} onChange={e=>{const next=e.target.value;setSelected(next);setBatch(null);void show(next);}}><option value="">请选择组合</option>{list.map(p=><option key={p.portfolioId.value} value={p.portfolioId.value}>{p.displayName}</option>)}</select></label><button className="secondary" onClick={()=>void show()} disabled={!selected||loading}>{loading?'更新中…':'刷新数据'}</button></div>
    <form className="inline-form" onSubmit={create}><label>新建组合<input value={name} onChange={e=>setName(e.target.value)} placeholder="例如：长期配置"/></label><button disabled={saving}>{saving?'正在创建…':'创建组合'}</button></form>
    {(()=>{const figures=summaryFigures(valuation,returns);return <div className="portfolio-summary"><article><small>组合总资产</small><b>{moneyText(figures.totalValue)}</b><em>{figures.asOf?`估值日 ${figures.asOf}`:'选择组合后展示'}</em></article><article><small>浮动收益</small><b>{moneyText(figures.profit)}</b><em>缺净值时不会用 0 代替</em></article><article><small>资金加权收益率</small><b>{figures.rate??'—'}</b><em>{figures.rateReason||'按已确认现金流'}</em></article></div>;})()}
    <h3>当前持仓</h3>{loading?<div className="empty-panel">正在更新持仓…</div>:positions.length?<div className="holding-table"><div className="holding-head"><span>基金</span><span>份额</span><span>市值</span><span>已实现收益</span></div>{holdingRows(positions,valuation?.positions).map((row,index)=><div className="holding-row" key={`${row.code}-${index}`}><span><b>{row.code||'基金'}</b></span><span>{moneyText(row.shares)}</span><span>{moneyText(row.marketValue)}</span><span className={row.profit==null?'':Number(row.profit)>=0?'up':'down'}>{moneyText(row.profit)}</span></div>)}</div>:<div className="empty-panel"><b>暂无可展示的持仓</b><span>记录买入或导入已确认的交易后，这里会展示你的组合。</span></div>}
    <details className="entry-panel"><summary>记录一笔买入</summary><form className="entry-grid" onSubmit={append}><label>基金代码<input value={fundCode} onChange={e=>setFundCode(e.target.value.replace(/\D/g,'').slice(0,6))} placeholder="6 位基金代码"/></label><label>交易日期<input type="date" value={tradeDate} onChange={e=>setTradeDate(e.target.value)}/></label><label>确认日期<input type="date" value={confirmDate} onChange={e=>setConfirmDate(e.target.value)}/></label><label>确认份额<input inputMode="decimal" value={shares} onChange={e=>setShares(e.target.value)} placeholder="例如 1000"/></label><label>确认金额<input inputMode="decimal" value={amount} onChange={e=>setAmount(e.target.value)} placeholder="例如 10000"/></label><label>确认净值<input inputMode="decimal" value={nav} onChange={e=>setNav(e.target.value)} placeholder="例如 1.0000"/></label><button disabled={recording}>{recording?'正在保存…':'保存买入记录'}</button></form></details>
    <details className="entry-panel"><summary>导入 CSV / Excel 流水</summary><p>先检查文件，再确认导入。导入前请核对基金代码、日期、金额和份额。</p><input type="file" accept=".csv,.xlsx" onChange={e=>{setFile(e.target.files?.[0]??null);setBatch(null);}}/><div className="import-actions"><button className="secondary" type="button" onClick={()=>void preview()} disabled={previewing}>{previewing?'正在检查…':'检查导入文件'}</button>{batch&&<><span>有效 {batch.validRows} 行，错误 {batch.invalidRows} 行</span><button type="button" onClick={()=>void commit()} disabled={batch.invalidRows>0||committing}>{committing?'正在导入…':'确认导入'}</button></>}</div>{importIssues(batch).length?<ul className="import-errors">{importIssues(batch).map(row=><li key={row.sourceRowNumber}>第 {row.sourceRowNumber} 行：{row.safeMessage||row.errorCode}</li>)}</ul>:null}</details>
  </section></AppShell>;
}
