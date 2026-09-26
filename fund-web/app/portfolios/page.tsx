'use client';
import { FormEvent, useEffect, useState } from 'react';
import AppShell from '../../components/AppShell';
import { api } from '../../lib/session';

type Portfolio = { portfolioId: { value: string }; displayName: string };
type Position = {
  fundCode?: string;
  fundName?: string;
  shares?: number | string;
  marketValue?: number | string;
  profit?: number | string;
};
type ReturnInfo = {
  totalValue?: number | string;
  totalReturn?: number | string;
  returnRate?: number | string;
  valuationDate?: string;
};
/**
 * 取当天日期的 YYYY-MM-DD，用作买入表单的默认交易日和确认日。
 * 运行环境时区异常时可能差一天，不会抛错。
 */
const today = () => new Date().toISOString().slice(0, 10);
/**
 * 把金额格式成中文数字；没有值时显示占位横线。
 * 无法转成数字时会得到 “NaN”，页面不会单独标成校验错误。
 */
const money = (value: unknown) =>
  value == null ? '—' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 });

/**
 * 管理组合列表、持仓收益、手工买入，以及 CSV/Excel 流水的预检和提交。
 * 持仓加载时有等待文案，没有持仓时有空状态。导入只展示有效行和错误行的数量，不列出哪一行没过校验；预检或提交的 4xx/5xx 只出现在顶栏。
 */
export default function PortfoliosPage() {
  const [list, setList] = useState<Portfolio[]>([]);
  const [name, setName] = useState('');
  const [selected, setSelected] = useState('');
  const [notice, setNotice] = useState('先创建组合，再记录实际确认的交易');
  const [fundCode, setFundCode] = useState('');
  const [shares, setShares] = useState('');
  const [amount, setAmount] = useState('');
  const [nav, setNav] = useState('');
  const [tradeDate, setTradeDate] = useState(today());
  const [confirmDate, setConfirmDate] = useState(today());
  const [positions, setPositions] = useState<Position[]>([]);
  const [returns, setReturns] = useState<ReturnInfo | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [batch, setBatch] = useState<{
    batchId: string;
    fileSha256: string;
    validRows: number;
    invalidRows: number;
  } | null>(null);
  const [loading, setLoading] = useState(false);
  /**
   * 读取组合列表，并在当前还没选中时选中第一项。
   * 未登录或列表 4xx/5xx 时只改顶栏，不清空已有列表；空列表不会自动清空当前选中项。
   */
  const load = () =>
    api('/api/v1/portfolios')
      .then((rows: Portfolio[]) => {
        setList(rows);
        setSelected((current) => current || rows[0]?.portfolioId.value || '');
      })
      .catch((x) => setNotice(x instanceof Error ? x.message : '请先登录后查看组合'));
  /**
   * 同时读取选中组合的持仓和收益。
   * 没有选中项时直接返回。任一接口 4xx/5xx 时顶栏报错，持仓和收益保持上一次成功的值，可能和失败提示同时出现。
   */
  const show = async (id = selected) => {
    if (!id) return;
    setLoading(true);
    try {
      const [nextPositions, nextReturns] = await Promise.all([
        api(`/api/v1/portfolios/${id}/positions`),
        api(`/api/v1/portfolios/${id}/returns`),
      ]);
      setPositions(Array.isArray(nextPositions) ? nextPositions : ((nextPositions as { items?: Position[] }).items ?? []));
      setReturns(nextReturns as ReturnInfo);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '暂时无法读取组合数据');
    } finally {
      setLoading(false);
    }
  };
  // The initial request deliberately runs once; later changes are driven by the explicit selector.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    api('/api/v1/portfolios')
      .then((rows: Portfolio[]) => {
        setList(rows);
        const first = rows[0]?.portfolioId.value;
        if (first) {
          setSelected(first);
          void show(first);
        }
      })
      .catch((x) => setNotice(x instanceof Error ? x.message : '请先登录后查看组合'));
  }, []);
  /**
   * 用名称新建组合并把它设为当前选中项。
   * 名称为空白时不发请求；重名、未登录或 5xx 时顶栏报错，名称保留。
   */
  const create = async (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return setNotice('请为组合填写名称');
    try {
      const p = await api('/api/v1/portfolios', { method: 'POST', body: JSON.stringify({ name }) });
      setSelected(p.portfolioId.value);
      setName('');
      setNotice('组合已创建');
      void load();
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '新建组合失败');
    }
  };
  /**
   * 把一笔手工买入记到当前组合。缺字段时不发请求，避免写出不完整流水。
   * 未选组合、代码不是 6 位，或份额、金额、净值为空时只改顶栏。接口校验失败或 5xx 时保留表单。
   */
  const append = async (e: FormEvent) => {
    e.preventDefault();
    if (!selected) return setNotice('请先选择组合');
    if (!/^\d{6}$/.test(fundCode) || !shares || !amount || !nav) {
      return setNotice('请完整填写基金代码、份额、金额和确认净值');
    }
    try {
      await api(`/api/v1/portfolios/${selected}/transactions`, {
        method: 'POST',
        body: JSON.stringify({
          fundCode,
          type: 'SUBSCRIPTION',
          tradeDate,
          confirmDate,
          shares,
          grossAmount: amount,
          fee: '0',
          confirmedNav: nav,
          idempotencyKey: `manual-${Date.now()}`,
        }),
      });
      setNotice('已记录买入，正在更新持仓');
      setFundCode('');
      setShares('');
      setAmount('');
      setNav('');
      void show();
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '记录买入失败');
    }
  };
  /**
   * 上传文件做导入预检，只展示有效行数和错误行数。
   * 没选组合或没选文件时不上传。文件类型不对、解析失败或 4xx/5xx 时不保留批次，顶栏显示“导入检查失败”或接口文案。
   */
  const preview = async () => {
    if (!selected || !file) {
      setNotice('请先选择组合和导入文件');
      return;
    }
    try {
      const body = new FormData();
      body.append('file', file);
      const result = await api(`/api/v1/portfolios/${selected}/imports/preview`, { method: 'POST', body });
      setBatch(result);
      setNotice(`检查完成：有效 ${result.validRows} 行，错误 ${result.invalidRows} 行`);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '导入检查失败');
    }
  };
  /**
   * 用预检得到的批次和文件校验和提交导入。有错误行时按钮是禁用的，这里仍会在没有批次时直接提示。
   * 校验和不匹配、批次过期或 5xx 时顶栏报错，持仓不刷新，批次记录保留以便重试。
   */
  const commit = async () => {
    if (!selected || !batch) return setNotice('请先完成导入文件检查');
    try {
      await api(`/api/v1/portfolios/${selected}/imports/${batch.batchId}/commit`, {
        method: 'POST',
        body: JSON.stringify({ fileSha256: batch.fileSha256 }),
      });
      setBatch(null);
      setNotice('导入完成，持仓已更新');
      void show();
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '提交导入失败');
    }
  };
  return (
    <AppShell notice={notice}>
      <section className="workspace portfolio-page">
        <p>PORTFOLIO</p>
        <h2>我的组合</h2>
        <p className="page-intro">记录实际确认的交易，查看已同步的持仓与收益。数据日期以组合结果为准。</p>
        <div className="portfolio-toolbar">
          <label>
            当前组合
            <select
              value={selected}
              onChange={(e) => {
                const next = e.target.value;
                setSelected(next);
                setBatch(null);
                void show(next);
              }}
            >
              <option value="">请选择组合</option>
              {list.map((p) => (
                <option key={p.portfolioId.value} value={p.portfolioId.value}>
                  {p.displayName}
                </option>
              ))}
            </select>
          </label>
          <button className="secondary" onClick={() => void show()} disabled={!selected || loading}>
            {loading ? '更新中…' : '刷新数据'}
          </button>
        </div>
        <form className="inline-form" onSubmit={create}>
          <label>
            新建组合
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：长期配置" />
          </label>
          <button>创建组合</button>
        </form>
        <div className="portfolio-summary">
          <article>
            <small>组合总资产</small>
            <b>{money(returns?.totalValue)}</b>
            <em>{returns?.valuationDate ? `估值日 ${returns.valuationDate}` : '选择组合后展示'}</em>
          </article>
          <article>
            <small>累计收益</small>
            <b>{money(returns?.totalReturn)}</b>
            <em>以已确认流水计算</em>
          </article>
          <article>
            <small>累计收益率</small>
            <b>{returns?.returnRate == null ? '—' : `${Number(returns.returnRate).toFixed(2)}%`}</b>
            <em>具体口径以组合数据为准</em>
          </article>
        </div>
        <h3>当前持仓</h3>
        {loading ? (
          <div className="empty-panel">正在更新持仓…</div>
        ) : positions.length ? (
          <div className="holding-table">
            <div className="holding-head">
              <span>基金</span>
              <span>份额</span>
              <span>市值</span>
              <span>收益</span>
            </div>
            {positions.map((p, index) => (
              <div className="holding-row" key={`${p.fundCode}-${index}`}>
                <span>
                  <b>{p.fundName ?? p.fundCode ?? '基金'}</b>
                  <small>{p.fundCode}</small>
                </span>
                <span>{money(p.shares)}</span>
                <span>{money(p.marketValue)}</span>
                <span className={Number(p.profit) >= 0 ? 'up' : 'down'}>{money(p.profit)}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="empty-panel">
            <b>暂无可展示的持仓</b>
            <span>记录买入或导入已确认的交易后，这里会展示你的组合。</span>
          </div>
        )}
        <details className="entry-panel">
          <summary>记录一笔买入</summary>
          <form className="entry-grid" onSubmit={append}>
            <label>
              基金代码
              <input
                value={fundCode}
                onChange={(e) => setFundCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                placeholder="6 位基金代码"
              />
            </label>
            <label>
              交易日期
              <input type="date" value={tradeDate} onChange={(e) => setTradeDate(e.target.value)} />
            </label>
            <label>
              确认日期
              <input type="date" value={confirmDate} onChange={(e) => setConfirmDate(e.target.value)} />
            </label>
            <label>
              确认份额
              <input inputMode="decimal" value={shares} onChange={(e) => setShares(e.target.value)} placeholder="例如 1000" />
            </label>
            <label>
              确认金额
              <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="例如 10000" />
            </label>
            <label>
              确认净值
              <input inputMode="decimal" value={nav} onChange={(e) => setNav(e.target.value)} placeholder="例如 1.0000" />
            </label>
            <button>保存买入记录</button>
          </form>
        </details>
        <details className="entry-panel">
          <summary>导入 CSV / Excel 流水</summary>
          <p>先检查文件，再确认导入。导入前请核对基金代码、日期、金额和份额。</p>
          <input
            type="file"
            accept=".csv,.xlsx"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setBatch(null);
            }}
          />
          <div className="import-actions">
            <button className="secondary" type="button" onClick={() => void preview()}>
              检查导入文件
            </button>
            {batch && (
              <>
                <span>
                  有效 {batch.validRows} 行，错误 {batch.invalidRows} 行
                </span>
                <button type="button" onClick={() => void commit()} disabled={batch.invalidRows > 0}>
                  确认导入
                </button>
              </>
            )}
          </div>
        </details>
      </section>
    </AppShell>
  );
}
