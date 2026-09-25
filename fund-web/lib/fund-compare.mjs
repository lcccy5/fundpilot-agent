function metricText(metric, label) {
  if (!metric || metric.status !== 'AVAILABLE' || metric.value == null) {
    return `${label}缺失${metric?.unavailableReason ? `：${metric.unavailableReason}` : ''}`;
  }
  return `${label} ${(Number(metric.value) * 100).toFixed(2)}%`;
}

/** Same-window comparison. Unit NAV change is never labeled as holding return. */
export function presentComparison(result) {
  const funds = Array.isArray(result?.funds) ? result.funds : [];
  const notes = [];
  const basis = result?.navBasis?.name ?? result?.navBasis ?? '';
  const rows = funds.map((fund) => {
    const code = fund.fundCode?.value ?? String(fund.fundCode ?? '');
    const fundBasis = fund.navBasis?.name ?? fund.navBasis ?? '';
    if (basis && fundBasis && fundBasis !== basis) notes.push(`${code} 的净值口径是 ${fundBasis}，与 ${basis} 不一致`);
    if (fund.coverage && fund.coverage !== 'COMPLETE' && fund.coverage?.status !== 'COMPLETE') {
      const coverage = typeof fund.coverage === 'string' ? fund.coverage : fund.coverage.status;
      if (coverage && coverage !== 'COMPLETE') notes.push(`${code} 的数据覆盖是 ${coverage}`);
    }
    return {
      code,
      intervalReturn: metricText(fund.cumulativeReturn, '区间单位净值变化'),
      drawdown: metricText(fund.maxDrawdown, '最大回撤'),
    };
  });
  return {
    windowText: result?.commonStartDate && result?.commonEndDate ? `${result.commonStartDate} 至 ${result.commonEndDate}` : '共同区间未知',
    basis: basis || '口径未返回',
    rows,
    notes,
  };
}

export function comparisonRequest(codes, startDate, endDate) {
  const fundCodes = [...new Set(codes.map(code => code.trim()).filter(code => /^\d{6}$/.test(code)))];
  if (fundCodes.length < 2 || fundCodes.length > 3) throw new Error('请填写 2 到 3 只不同的 6 位基金代码');
  if (!startDate || !endDate || startDate > endDate) throw new Error('请选择同一段开始和结束日期');
  return { fundCodes, startDate, endDate, navBasis: 'UNIT_NAV' };
}
