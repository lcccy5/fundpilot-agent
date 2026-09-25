/** Missing risk stays missing. A real zero is still shown as zero. */
export function riskFigure(value) {
  if (value == null || value === '') return null;
  const number = Number(value);
  if (!Number.isFinite(number)) return null;
  return `${number.toFixed(2)}%`;
}

export function presentRisk(risk, positionCount) {
  if (!positionCount) return { ready: false, reason: '还没有持仓，无法计算集中度。' };
  const maxFund = riskFigure(risk?.maxFundWeight);
  const top3 = riskFigure(risk?.top3Weight);
  if (!risk || risk.coverage === 'PARTIAL' || !maxFund || !top3) {
    const warning = Array.isArray(risk?.warnings) && risk.warnings.length ? risk.warnings.join('；') : '';
    return { ready: false, reason: warning ? `风险还不能计算：${warning}` : '净值不完整，不能用 0 代替集中度。' };
  }
  return {
    ready: true,
    reason: '',
    maxFund,
    top3,
    status: risk.concentrationStatus || '未标注',
    asOf: risk.asOfDate || '',
  };
}
