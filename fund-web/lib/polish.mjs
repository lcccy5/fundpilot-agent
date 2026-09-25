/** Same-site return path. Off-site, protocol-relative, and slash-backslash values stay on the fallback page. */
export function safeNext(search, fallback = '/') {
  const next = new URLSearchParams(search ?? '').get('next') ?? fallback;
  if (!next.startsWith('/') || next.startsWith('//') || /[\u0000-\u001F\u007F\\]/.test(next)) return fallback;
  try {
    const url = new URL(next, 'https://fundpilot.local');
    if (url.origin !== 'https://fundpilot.local') return fallback;
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return fallback;
  }
}

export function noticeTone(notice) {
  const text = notice ?? '';
  if (/失败|错误|无法|没有权限|请先登录|未完成|暂不可用|缺失/.test(text)) return 'error';
  if (/正在/.test(text)) return 'progress';
  if (/已|完成|同步/.test(text)) return 'success';
  return 'info';
}

export function displayTime(value) {
  if (!value) return '时间待同步';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value).replace('T', ' ').slice(0, 16);
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function runStatusLabel(status) {
  return ({
    PLAN_RUNNING: '正在分析',
    RUNNING: '正在生成',
    SUCCEEDED: '已完成',
    FAILED: '未完成',
    CANCELLED: '已取消',
    WAITING_APPROVAL: '等待确认',
    REJECTED: '已拒绝',
  })[status] ?? (status || '状态未知');
}

export function firstUnanswered(questions, answers) {
  const missing = (questions ?? []).find((question) => answers?.[question.id] == null || answers[question.id] === '');
  return missing?.id ?? null;
}

export function assessmentDate(profile) {
  return profile?.confirmedAt ?? profile?.completedAt ?? profile?.assessmentDate ?? null;
}

export function profileName(profile) {
  const level = profile?.level ?? profile?.riskLevel ?? profile?.riskProfile;
  if (level == null || level === '') return '尚未完成评估';
  return typeof level === 'string' ? level : (level.name ?? '尚未完成评估');
}

/** Later fund query wins. An earlier response must not replace it. */
export function applyFundLoad(state, seq, current, fund) {
  if (seq !== current) return state;
  return { ...state, fund, points: fund.points ?? state.points };
}

export function approvalFromEvent(payloadJson) {
  try {
    const body = JSON.parse(payloadJson ?? '');
    if (!body?.approvalId) return null;
    return { approvalId: String(body.approvalId), parameters: body.parameters == null ? '{format=markdown}' : String(body.parameters) };
  } catch {
    return null;
  }
}

export function eventReason(payloadJson) {
  try {
    const body = JSON.parse(payloadJson ?? '');
    return String(body.reason ?? body.message ?? '');
  } catch {
    return '';
  }
}

export function percentRatio(value) {
  if (value == null || value === '') return null;
  const number = Number(value);
  if (!Number.isFinite(number)) return null;
  return `${(number * 100).toFixed(2)}%`;
}

export function moneyText(value) {
  if (value == null || value === '') return '—';
  const number = Number(value);
  if (!Number.isFinite(number)) return '—';
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

export function fundCodeText(value) {
  if (value == null) return '';
  return typeof value === 'string' ? value : (value.value ?? '');
}

export function holdingRows(positions, valuationPositions) {
  const valued = new Map();
  for (const item of valuationPositions ?? []) {
    const code = fundCodeText(item.position?.fundCode ?? item.fundCode);
    if (code) valued.set(code, item);
  }
  return (positions ?? []).map((position) => {
    const code = fundCodeText(position.fundCode);
    const quote = valued.get(code);
    return {
      code,
      shares: position.confirmedShares ?? position.shares ?? null,
      marketValue: quote?.value ?? position.marketValue ?? null,
      profit: position.realizedProfit ?? position.profit ?? null,
    };
  });
}

export function summaryFigures(valuation, returns) {
  return {
    totalValue: valuation?.totalValue ?? null,
    profit: valuation?.unrealizedProfit ?? null,
    rate: returns?.returnStatus === 'AVAILABLE' ? percentRatio(returns.moneyWeightedReturn) : null,
    asOf: valuation?.asOfDate ?? returns?.asOfDate ?? null,
    rateReason: returns?.returnStatus && returns.returnStatus !== 'AVAILABLE' ? '收益率还不能计算' : '',
  };
}

export function shellTitle(path) {
  const titles = {
    '/': ['总览', '开始研究'],
    '/watchlists': ['自选', '我的自选'],
    '/portfolios': ['组合', '我的组合'],
    '/runs': ['研究', '研究任务'],
    '/reports': ['月报', '月度回顾'],
    '/notifications': ['通知', '通知中心'],
    '/account': ['账户', '账户与风险画像'],
    '/login': ['账户', '登录'],
    '/register': ['账户', '注册'],
    '/mcp': ['治理', '工具治理'],
  };
  return titles[path] ?? ['研究', '基金研究'];
}

export function calendarGap(earlier, later) {
  return Math.round((Date.parse(later) - Date.parse(earlier)) / 86400000);
}

export function navChartSegments(points) {
  const ordered = [...(points ?? [])].filter((point) => point?.navDate && point.unitNav != null)
    .sort((a, b) => a.navDate.localeCompare(b.navDate));
  if (ordered.length < 2) return { segments: [], min: 0, max: 0, points: ordered, gaps: [] };
  const start = Date.parse(ordered[0].navDate);
  const span = Math.max(Date.parse(ordered.at(-1).navDate) - start, 86400000);
  const values = ordered.map((point) => Number(point.unitNav));
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const placed = ordered.map((point) => ({
    ...point,
    x: ((Date.parse(point.navDate) - start) / span) * 100,
    y: 90 - ((Number(point.unitNav) - min) / range) * 72,
  }));
  const gaps = [];
  let path = `M ${placed[0].x.toFixed(2)} ${placed[0].y.toFixed(2)}`;
  for (let index = 1; index < placed.length; index += 1) {
    const days = calendarGap(ordered[index - 1].navDate, ordered[index].navDate);
    if (days > 3) gaps.push({ from: ordered[index - 1].navDate, to: ordered[index].navDate, days });
    path += ` L ${placed[index].x.toFixed(2)} ${placed[index].y.toFixed(2)}`;
  }
  return { segments: [path], min, max, points: placed, gaps };
}

export function acceptDelta(stopped, content, chunk) {
  if (stopped) return content ?? '';
  return `${content ?? ''}${chunk ?? ''}`;
}

export function importIssues(batch) {
  return (batch?.rows ?? []).filter((row) => row?.errorCode || row?.safeMessage);
}
