"use client";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import AppShell from "../../components/AppShell";
import { api } from "../../lib/session";
import {
  MetricCard,
  StatePanel,
  amountText,
  valueTone,
} from "../../components/FinanceUI";
import { presentRisk } from "../../lib/portfolio-ledger.mjs";

type Portfolio = { portfolioId: { value: string }; displayName: string };
type Position = {
  fundCode?: string;
  fundName?: string;
  shares?: number | string;
  marketValue?: number | string;
  profit?: number | string;
};
// Java 的流水持仓与估值分开返回；展示字段在前端明确转换。
type LedgerPosition = {
  fundCode: string | { value: string };
  confirmedShares: number | string;
  remainingCost: number | string;
};
type Valuation = {
  asOfDate?: string;
  totalValue?: number | string | null;
  unrealizedProfit?: number | string | null;
  positions?: {
    position: LedgerPosition;
    value?: number | string | null;
    navDate?: string;
  }[];
};
type ReturnInfo = {
  asOfDate?: string;
  moneyWeightedReturn?: number | string | null;
  returnStatus?: string;
  warnings?: string[];
};
type ImportIssue = {
  sourceRowNumber: number;
  errorCode?: string;
  safeMessage?: string;
};
type ImportPreview = {
  batchId: string;
  fileSha256: string;
  validRows: number;
  invalidRows: number;
  rows?: ImportIssue[];
};
type Tx = {
  transactionId?: string;
  fundCode: string | { value: string };
  transactionType?: string;
  confirmDate?: string;
  shares?: string | number;
  grossAmount?: string | number;
};
type Risk = {
  asOfDate?: string;
  maxFundWeight?: string | number | null;
  top3Weight?: string | number | null;
  concentrationStatus?: string;
  coverage?: string;
  warnings?: string[];
};
const fundCodeOf = (value: Tx["fundCode"]) =>
  typeof value === "string" ? value : (value?.value ?? "");
const txLabel: Record<string, string> = {
  SUBSCRIPTION: "买入",
  REDEMPTION: "赎回",
  DIVIDEND: "分红",
  SWITCH_IN: "转入",
  SWITCH_OUT: "转出",
};
const today = () => new Date().toISOString().slice(0, 10);
const money = amountText;

/** 先展示资产与持仓，交易记录和导入操作按需展开。 */
export default function PortfoliosPage() {
  const [list, setList] = useState<Portfolio[]>([]);
  const [name, setName] = useState("");
  const [selected, setSelected] = useState("");
  const [notice, setNotice] = useState("先创建组合，再记录实际确认的交易");
  const [fundCode, setFundCode] = useState("");
  const [shares, setShares] = useState("");
  const [amount, setAmount] = useState("");
  const [nav, setNav] = useState("");
  const [tradeDate, setTradeDate] = useState(today());
  const [confirmDate, setConfirmDate] = useState(today());
  const [positions, setPositions] = useState<Position[]>([]);
  const [returns, setReturns] = useState<ReturnInfo | null>(null);
  const [valuation, setValuation] = useState<Valuation | null>(null);
  const [transactions, setTransactions] = useState<Tx[]>([]);
  const [risk, setRisk] = useState<Risk | null>(null);
  const [ledgerNote, setLedgerNote] = useState("");
  const [riskNote, setRiskNote] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [batch, setBatch] = useState<ImportPreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [committing, setCommitting] = useState(false);
  const requestSeq = useRef(0);
  const currentPortfolio = useRef("");
  const [creating, setCreating] = useState(false);
  const [listLoading, setListLoading] = useState(true);
  const [listFailed, setListFailed] = useState(false);
  /** 刷新组合列表，保留用户当前选择。 */
  const load = () =>
    api("/api/v1/portfolios")
      .then((rows: Portfolio[]) => {
        setList(rows);
        setSelected((current) => current || rows[0]?.portfolioId.value || "");
      })
      .catch((x) =>
        setNotice(x instanceof Error ? x.message : "请先登录后查看组合"),
      );
  /** 切换组合或记录交易后，读取同一组合的持仓、收益、流水与风险。 */
  const show = useCallback(async (id: string) => {
    // 每次切换都清空上个组合数据，迟到的响应不能覆盖当前组合。
    const seq = ++requestSeq.current;
    currentPortfolio.current = id;
    setPositions([]);
    setReturns(null);
    setValuation(null);
    setTransactions([]);
    setRisk(null);
    setLedgerNote("");
    setRiskNote("");
    if (!id) {
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [
        nextPositions,
        nextReturns,
        nextTransactions,
        nextRisk,
        nextValuation,
      ] = await Promise.allSettled([
        api(`/api/v1/portfolios/${id}/positions`),
        api(`/api/v1/portfolios/${id}/returns`),
        api(`/api/v1/portfolios/${id}/transactions`),
        api(`/api/v1/portfolios/${id}/risk`),
        api<Valuation>(`/api/v1/portfolios/${id}/valuation`),
      ]);
      if (seq !== requestSeq.current) return;
      const valuationData =
        nextValuation.status === "fulfilled" ? nextValuation.value : null;
      setValuation(valuationData);
      if (nextValuation.status === "rejected")
        setNotice("估值数据暂时无法读取，未用零值代替");
      if (nextPositions.status === "fulfilled") {
        const rows = Array.isArray(nextPositions.value)
          ? (nextPositions.value as LedgerPosition[])
          : [];
        const holdings = await Promise.all(
          rows.map(async (position) => {
            const code = fundCodeOf(position.fundCode);
            const valued = valuationData?.positions?.find(
              (item) => fundCodeOf(item.position.fundCode) === code,
            );
            const profile = await api<{ name: string }>(
              `/api/v1/funds/${code}`,
            ).catch(() => null);
            return {
              fundCode: code,
              fundName: profile?.name,
              shares: position.confirmedShares,
              marketValue: valued?.value ?? undefined,
              profit:
                valued?.value == null
                  ? undefined
                  : Number(valued.value) - Number(position.remainingCost),
            };
          }),
        );
        if (seq !== requestSeq.current) return;
        setPositions(holdings);
      } else
        setNotice(
          nextPositions.reason instanceof Error
            ? nextPositions.reason.message
            : "暂时无法读取持仓",
        );
      if (nextReturns.status === "fulfilled")
        setReturns(nextReturns.value as ReturnInfo);
      else setNotice("收益数据暂时无法读取，未用零值代替");
      if (nextTransactions.status === "fulfilled") {
        setTransactions(
          Array.isArray(nextTransactions.value) ? nextTransactions.value : [],
        );
        setLedgerNote("");
      } else {
        setTransactions([]);
        setLedgerNote(
          nextTransactions.reason instanceof Error
            ? nextTransactions.reason.message
            : "交易记录暂时读不出来",
        );
      }
      if (nextRisk.status === "fulfilled") {
        setRisk(nextRisk.value as Risk);
        setRiskNote("");
      } else {
        setRisk(null);
        setRiskNote(
          nextRisk.reason instanceof Error
            ? nextRisk.reason.message
            : "风险摘要暂时读不出来",
        );
      }
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "暂时无法读取组合数据");
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);
  const invalidate = useCallback(() => {
    requestSeq.current++;
  }, []);
  useEffect(() => {
    api("/api/v1/portfolios")
      .then((rows: Portfolio[]) => {
        setList(rows);
        const first = rows[0]?.portfolioId.value;
        if (first) {
          setSelected(first);
          setNotice("组合数据按已确认交易计算，估值日期见资产卡片");
          void show(first);
        }
      })
      .catch((x) => {
        setListFailed(true);
        setNotice(x instanceof Error ? x.message : "请先登录后查看组合");
      })
      .finally(() => setListLoading(false));
    return invalidate;
  }, [show, invalidate]);
  /** 创建后立即展示新组合，避免仍显示旧组合的数据。 */
  const create = async (e: FormEvent) => {
    e.preventDefault();
    if (creating) return;
    if (!name.trim()) return setNotice("请为组合填写名称");
    setCreating(true);
    try {
      const p = await api("/api/v1/portfolios", {
        method: "POST",
        body: JSON.stringify({ name }),
      });
      setSelected(p.portfolioId.value);
      setName("");
      setNotice("组合已创建");
      void show(p.portfolioId.value);
      void load();
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "新建组合失败");
    } finally {
      setCreating(false);
    }
  };
  /** 只记录用户填写的已确认买入；默认留空，不自动生成交易金额。 */
  const append = async (e: FormEvent) => {
    e.preventDefault();
    if (saving) return;
    if (!selected) return setNotice("请先选择组合");
    if (!/^\d{6}$/.test(fundCode) || !shares || !amount || !nav)
      return setNotice("请完整填写基金代码、份额、金额和确认净值");
    setSaving(true);
    try {
      await api(`/api/v1/portfolios/${selected}/transactions`, {
        method: "POST",
        body: JSON.stringify({
          fundCode,
          type: "SUBSCRIPTION",
          tradeDate,
          confirmDate,
          shares,
          grossAmount: amount,
          fee: "0",
          confirmedNav: nav,
          idempotencyKey: `manual-${Date.now()}`,
        }),
      });
      setNotice("已记录买入，正在更新持仓");
      setFundCode("");
      setShares("");
      setAmount("");
      setNav("");
      void show(selected);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "记录买入失败");
    } finally {
      setSaving(false);
    }
  };
  /** 先检查文件并展示错误行，通过校验后才允许确认导入。 */
  const preview = async () => {
    if (previewing) return;
    if (!selected || !file) {
      setNotice("请先选择组合和导入文件");
      return;
    }
    const target = selected;
    setPreviewing(true);
    setBatch(null);
    try {
      const body = new FormData();
      body.append("file", file);
      const result = await api<ImportPreview>(
        `/api/v1/portfolios/${selected}/imports/preview`,
        { method: "POST", body },
      );
      if (currentPortfolio.current !== target) return;
      setBatch(result);
      setNotice(
        `检查完成：有效 ${result.validRows} 行，错误 ${result.invalidRows} 行`,
      );
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "导入检查失败");
    } finally {
      setPreviewing(false);
    }
  };
  /** 提交经过预览的批次，同时传入文件校验值。 */
  const commit = async () => {
    if (committing) return;
    if (!selected || !batch) return setNotice("请先完成导入文件检查");
    if (batch.invalidRows > 0) return setNotice("请先修正错误行后再导入");
    setCommitting(true);
    try {
      await api(
        `/api/v1/portfolios/${selected}/imports/${batch.batchId}/commit`,
        {
          method: "POST",
          body: JSON.stringify({ fileSha256: batch.fileSha256 }),
        },
      );
      setBatch(null);
      setNotice("导入完成，持仓已更新");
      void show(selected);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "提交导入失败");
    } finally {
      setCommitting(false);
    }
  };
  return (
    <AppShell notice={notice} title="我的组合" kicker="PORTFOLIO">
      <section className="workspace portfolio-page">
        <p>PORTFOLIO</p>
        <h2>我的组合</h2>
        <p className="page-intro">
          记录实际确认的交易，查看已同步的持仓与收益。数据日期以组合结果为准。
        </p>
        <div className="portfolio-toolbar">
          <label>
            当前组合
            <select
              disabled={saving || committing || previewing}
              value={selected}
              onChange={(e) => {
                const next = e.target.value;
                setSelected(next);
                setBatch(null);
                setFile(null);
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
          <button
            className="secondary"
            onClick={() => void show(selected)}
            disabled={
              !selected || loading || saving || committing || previewing
            }
          >
            {loading ? "更新中…" : "刷新数据"}
          </button>
        </div>
        {listLoading && <StatePanel title="正在加载组合" loading />}
        {listFailed && (
          <StatePanel title="组合暂时加载失败" error>
            {notice}
          </StatePanel>
        )}
        <div className="portfolio-summary">
          <MetricCard
            label="组合总资产（元）"
            value={money(valuation?.totalValue)}
            note={
              loading
                ? "正在更新估值"
                : valuation?.asOfDate
                  ? `估值日 ${valuation.asOfDate}`
                  : "暂未提供估值数据"
            }
          />
          <MetricCard
            label="持仓浮动盈亏（元）"
            value={amountText(valuation?.unrealizedProfit, true)}
            tone={valueTone(valuation?.unrealizedProfit)}
            note={
              valuation?.unrealizedProfit == null
                ? "收益数据暂不可用"
                : Number(valuation.unrealizedProfit) > 0
                  ? "持仓浮盈"
                  : Number(valuation.unrealizedProfit) < 0
                    ? "持仓浮亏"
                    : "收益持平"
            }
          />
          <MetricCard
            label="资金加权年化收益率"
            value={
              returns?.moneyWeightedReturn == null
                ? "—"
                : `${amountText(Number(returns.moneyWeightedReturn) * 100, true)}%`
            }
            tone={valueTone(returns?.moneyWeightedReturn)}
            note={
              returns?.moneyWeightedReturn == null
                ? "现金流不足或无法求解，暂不展示"
                : "XIRR · 按已确认现金流计算"
            }
          />
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
                  <b>{p.fundName ?? p.fundCode ?? "基金"}</b>
                  <small>{p.fundCode}</small>
                </span>
                <span data-label="份额">{money(p.shares)}</span>
                <span data-label="市值">{money(p.marketValue)}</span>
                <span
                  data-label="收益"
                  className={Number(p.profit) >= 0 ? "up" : "down"}
                >
                  {p.profit == null
                    ? "—"
                    : `${Number(p.profit) > 0 ? "+" : ""}${money(p.profit)}`}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <div className="empty-panel">
            <b>暂无可展示的持仓</b>
            <span>记录买入或导入已确认的交易后，这里会展示你的组合。</span>
          </div>
        )}
        <h3>交易记录</h3>
        {ledgerNote ? (
          <div className="empty-panel">
            <b>交易记录没有加载出来</b>
            <span>{ledgerNote}</span>
          </div>
        ) : transactions.length ? (
          <div className="holding-table">
            <div className="holding-head">
              <span>基金</span>
              <span>类型</span>
              <span>确认日</span>
              <span>金额</span>
            </div>
            {transactions.map((item, index) => (
              <div className="holding-row" key={item.transactionId ?? index}>
                <span>{fundCodeOf(item.fundCode)}</span>
                <span data-label="类型">
                  {txLabel[item.transactionType ?? ""] ??
                    item.transactionType ??
                    "交易"}
                </span>
                <span data-label="确认日">
                  {item.confirmDate ?? "日期缺失"}
                </span>
                <span data-label="金额">{money(item.grossAmount)}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="empty-panel">
            <b>还没有确认交易</b>
            <span>记录买入或导入流水后，交易会显示在持仓下方。</span>
          </div>
        )}
        <h3>风险摘要</h3>
        {riskNote ? (
          <div className="empty-panel">
            <b>风险摘要没有加载出来</b>
            <span>{riskNote}</span>
          </div>
        ) : (
          (() => {
            const view = presentRisk(risk, positions.length);
            return view.ready ? (
              <div className="portfolio-summary">
                <article>
                  <small>单只最高权重</small>
                  <b>{view.maxFund}</b>
                  <em>{view.asOf ? `截至 ${view.asOf}` : "按已有持仓"}</em>
                </article>
                <article>
                  <small>前三大权重合计</small>
                  <b>{view.top3}</b>
                  <em>{view.status}</em>
                </article>
              </div>
            ) : (
              <div className="empty-panel">
                <b>还不能给出集中度</b>
                <span>{view.reason}</span>
              </div>
            );
          })()
        )}
        <details
          className="entry-panel"
          open={!list.length && !listLoading && !listFailed}
        >
          <summary>新建组合</summary>
          <form className="inline-form" onSubmit={create}>
            <label>
              新建组合
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="例如：长期配置"
              />
            </label>
            <button disabled={creating}>
              {creating ? "正在创建…" : "创建组合"}
            </button>
          </form>
        </details>
        <details className="entry-panel">
          <summary>记录一笔买入</summary>
          <form className="entry-grid" onSubmit={append}>
            <label>
              基金代码
              <input
                value={fundCode}
                onChange={(e) =>
                  setFundCode(e.target.value.replace(/\D/g, "").slice(0, 6))
                }
                placeholder="6 位基金代码"
              />
            </label>
            <label>
              交易日期
              <input
                type="date"
                value={tradeDate}
                onChange={(e) => setTradeDate(e.target.value)}
              />
            </label>
            <label>
              确认日期
              <input
                type="date"
                value={confirmDate}
                onChange={(e) => setConfirmDate(e.target.value)}
              />
            </label>
            <label>
              确认份额
              <input
                inputMode="decimal"
                value={shares}
                onChange={(e) => setShares(e.target.value)}
                placeholder="例如 1000"
              />
            </label>
            <label>
              确认金额
              <input
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="例如 10000"
              />
            </label>
            <label>
              确认净值
              <input
                inputMode="decimal"
                value={nav}
                onChange={(e) => setNav(e.target.value)}
                placeholder="例如 1.0000"
              />
            </label>
            <button disabled={saving || !selected}>
              {saving ? "正在保存…" : "保存买入记录"}
            </button>
          </form>
        </details>
        <details className="entry-panel">
          <summary>导入 CSV / Excel 流水</summary>
          <p>
            先检查文件，再确认导入。导入前请核对基金代码、日期、金额和份额。
          </p>
          <input
            type="file"
            disabled={previewing || committing}
            accept=".csv,.xlsx"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null);
              setBatch(null);
            }}
          />
          <div className="import-actions">
            <button
              className="secondary"
              type="button"
              onClick={() => void preview()}
              disabled={previewing}
            >
              {previewing ? "正在检查…" : "检查导入文件"}
            </button>
            {batch && (
              <>
                <span>
                  有效 {batch.validRows} 行，错误 {batch.invalidRows} 行
                </span>
                <button
                  type="button"
                  onClick={() => void commit()}
                  disabled={batch.invalidRows > 0 || committing}
                >
                  {committing ? "正在导入…" : "确认导入"}
                </button>
              </>
            )}
          </div>
          {batch?.rows?.filter((row) => row.errorCode || row.safeMessage)
            .length ? (
            <ul className="import-errors">
              {batch.rows
                .filter((row) => row.errorCode || row.safeMessage)
                .map((row) => (
                  <li key={row.sourceRowNumber}>
                    第 {row.sourceRowNumber} 行：
                    {row.safeMessage || row.errorCode}
                  </li>
                ))}
            </ul>
          ) : null}
        </details>
      </section>
    </AppShell>
  );
}
