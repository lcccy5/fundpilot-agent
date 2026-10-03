// 全部写操作都拦截为固定数据，不创建真实账户、导入真实交易或调用收费模型。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { chromium } from 'playwright';

const base = process.env.E2E_BASE_URL || 'http://localhost:3001';
const output = '../target/finance-app';
fs.mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.platform === 'win32' ? { channel: 'msedge' } : {}) });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
let mode = 'normal';
let race = false;
let invalidImport = true;
let runStatus = 'WAITING_APPROVAL';
let reportMissing = false;
let missingValuation = false;
const errors = [];
const writes = [];
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => {
  if (message.type() !== 'error') return;
  // 仅允许本套检查主动返回的失败响应；运行时错误和 hydration 错误仍算失败。
  if (mode === 'failure' && /Failed to load resource.*503/.test(message.text())) return;
  if (reportMissing && /Failed to load resource.*404/.test(message.text())) return;
  if (/Failed to load resource.*400/.test(message.text()) && /auth\/(login|register)/.test(message.location().url)) return;
  errors.push(message.text());
});

const longName = '华夏成长混合发起式证券投资基金长期配置份额测试名称';
const fund = code => ({ fundCode: code, name: code === '000001' ? longName : '测试稳健基金', fundType: '混合型', managementCompany: '测试基金管理有限公司', fundManager: '测试经理', establishedDate: '2001-12-18' });
const portfolios = [{ portfolioId: { value: 'p1' }, displayName: '长期配置' }, { portfolioId: { value: 'p2' }, displayName: '稳健组合' }];
const groups = [{ groupId: 'g1', displayName: '长期关注', version: 1, items: [{ itemId: 'i1', fundCode: '000001', version: 1 }] }, { groupId: 'g2', displayName: '待研究', version: 1, items: [] }];
const snapshot = { commonStartDate: '2025-10-01', commonEndDate: '2026-09-30', navBasis: 'UNIT_NAV', funds: ['000001', '110022'].map(fundCode => ({ fundCode, observationCount: 250, coverageStatus: 'PARTIAL', metrics: { cumulativeReturn: { status: 'AVAILABLE', value: '-0.02', evidenceId: 'E01' }, maxDrawdown: { status: 'UNAVAILABLE', value: null, evidenceId: 'E02' } } })) };
const opinion = { summary: '根据同区间数据，仍需关注风险与数据覆盖。', claims: [{ statement: '这是一条有来源的观点。', evidenceIds: ['E01'] }], limitations: ['部分净值缺失'] };
const decision = { preferredFundCode: null, conclusion: '证据不足，暂不选择。', rationale: opinion.claims, disagreements: ['收益与风险关注点不同'], limitations: opinion.limitations };
const traces = ['data', 'bull', 'bear', 'judge'].map(role => ({ role, durationMs: 25, startedAt: '', finishedAt: '' }));
const arenaResult = { runId: 'arena-fixture', snapshot, bull: opinion, bear: opinion, decision, trace: traces };
const history = [{ runId: 'r1', status: 'SUCCEEDED', message: '比较基金风险与表现', startedAt: '2026-09-30T09:00:00' }, { runId: 'r2', status: 'WAITING_APPROVAL', message: '等待确认的组合研究', startedAt: '2026-09-30T10:00:00' }];

await page.route('**/api/**', async route => {
  const request = route.request();
  const path = new URL(request.url()).pathname;
  const method = request.method();
  const json = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data }) });
  const raw = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
  if (method !== 'GET') writes.push({ path, method, body: request.postData() });
  if (path.endsWith('/auth/refresh')) return json({ accessToken: 'fixture-only-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z' });
  if (/auth\/(login|register)$/.test(path)) return route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ message: '测试表单错误，请检查输入' }) });
  if (path.endsWith('/users/me')) return json({ displayName: '测试用户', username: 'fixture', roles: [] });
  if (mode === 'failure') return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '测试服务暂不可用', detail: '测试服务暂不可用' }) });
  if (path === '/api/v1/watchlists') return method === 'POST' ? json({ groupId: 'new', displayName: '新分组', items: [], version: 1 }) : json(mode === 'empty' ? [] : groups);
  if (/watchlists\/g1\/items$/.test(path)) return json({ ...groups[0], items: [...groups[0].items, { itemId: 'i2', fundCode: '110022', version: 1 }] });
  if (path === '/api/v1/portfolios') return method === 'POST' ? json(portfolios[1]) : json(mode === 'empty' ? [] : portfolios);
  if (/portfolios\/p\d\//.test(path)) {
    const id = path.split('/')[4];
    if (race && id === 'p1') await new Promise(resolve => setTimeout(resolve, 500));
    if (path.endsWith('/positions')) return json(mode === 'empty' ? [] : [{ fundCode: {value:id === 'p1' ? '000001' : '110022'}, confirmedShares: 1000, remainingCost: 1000 }]);
    if (path.endsWith('/returns')) return json({ moneyWeightedReturn: missingValuation?null:-0.02, asOfDate: '2026-09-30', returnStatus: 'AVAILABLE' });
    if (path.endsWith('/valuation')) return json({totalValue:missingValuation?null:id==='p1'?123456.78:980,unrealizedProfit:missingValuation?null:-20,asOfDate:'2026-09-30',positions:[{position:{fundCode:{value:id==='p1'?'000001':'110022'},remainingCost:1000,confirmedShares:1000},value:missingValuation?null:980}]});
    if (path.endsWith('/transactions')) return json(method === 'POST' ? {} : [{ transactionId: 'tx1', fundCode: '000001', transactionType: 'SUBSCRIPTION', confirmDate: '2026-09-01', grossAmount: 1000 }]);
    if (path.endsWith('/risk')) return json({ coverage: 'PARTIAL', warnings: ['部分基金净值缺失'] });
    if (path.endsWith('/preview')) return json({ batchId: 'b1', fileSha256: 'checksum', validRows: 1, invalidRows: invalidImport ? 1 : 0, rows: invalidImport ? [{ sourceRowNumber: 2, safeMessage: '基金代码格式错误' }] : [] });
    if (path.endsWith('/commit')) return json({});
  }
  if (path === '/api/v1/fund-comparisons') return json({ ...snapshot, funds: snapshot.funds.map(item => ({ ...item, coverage: 'PARTIAL', cumulativeReturn: item.metrics.cumulativeReturn, maxDrawdown: item.metrics.maxDrawdown })) });
  if (path === '/api/v1/funds') return json([fund('000001')]);
  if (/funds\/\d{6}\/nav$/.test(path)) return json({ items: mode === 'empty' ? [] : [{ navDate: '2026-09-28', unitNav: 1.12 }, { navDate: '2026-09-30', unitNav: 1.1 }] });
  if (path.endsWith('/nav/sync')) return json({});
  if (/funds\/\d{6}$/.test(path)) return json(fund(path.split('/').at(-1)));
  if (path === '/api/v1/risk-questionnaire') return json({ questions: mode === 'empty' ? [] : [{ id: 'q1', prompt: '你愿意承担多大的波动？', choices: [{ value: 0, label: '尽量避免损失' }, { value: 1, label: '可接受一定波动' }] }, { id: 'q2', prompt: '预计持有多久？', choices: [{ value: 1, label: '一年以上' }] }] });
  if (path.endsWith('/risk-profile')) return json(mode === 'empty' ? null : { level: '稳健型', completedAt: '2026-09-30' });
  if (path === '/api/v1/notifications') return json(mode === 'empty' ? [] : [{ notificationId: 'n1', title: '研究已完成', summary: '查看你的基金比较报告与依据。', createdAt: '2026-09-30 12:00', readAt: null }, { notificationId: 'n2', title: '组合数据已更新', summary: '估值已同步到最新数据日。', readAt: '2026-09-30' }]);
  if (path.endsWith('/read')) return json({});
  if (path === '/api/agent/runs') return json(method === 'POST' ? { runId: 'r2', status: runStatus } : mode === 'empty' ? [] : history);
  if (/runs\/r\d\/report$/.test(path)) {
    if (reportMissing) return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
    if (race && path.includes('/r1/')) await new Promise(resolve => setTimeout(resolve, 500));
    return json({ artifactUri: 'fixture', content: path.includes('/r1/') ? '# 第一份报告\n## 风险分析\n负收益与净值缺失需要同时关注。' : '# 第二份报告\n这是当前选择的报告。', evidenceCount: 2 });
  }
  if (/runs\/r\d\/plan$/.test(path)) return json({ planId: 'plan', tasks: [{ taskKey: 'analysis', capabilityType: 'ANALYST', status: 'SUCCEEDED' }, { taskKey: 'write', capabilityType: 'REPORT_WRITE', status: runStatus }] });
  if (/runs\/r\d\/events$/.test(path)) return json([{ sequence: 1, eventType: 'approval.requested', payloadJson: JSON.stringify({ approvalId: 'a1', parameters: 'report' }) }]);
  if (/approvals\/a1/.test(path)) { runStatus = path.endsWith('/reject') ? 'REJECTED' : 'SUCCEEDED'; return json({}); }
  if (path.endsWith('/cancel')) { runStatus = 'CANCELLED'; return json({}); }
  if (/runs\/r\d$/.test(path)) return json({ runId: path.split('/').at(-1), status: path.endsWith('r1') ? 'SUCCEEDED' : runStatus, planId: 'plan' });
  if (path === '/api/agent/reports') return json(mode === 'empty' ? [] : [{ jobId: 'j1', runId: 'r1', status: 'SUCCEEDED', periodStart: '2026-08-01', periodEnd: '2026-08-31' }, { jobId: 'j2', runId: 'r2', status: 'SUCCEEDED', periodStart: '2026-09-01', periodEnd: '2026-09-30' }]);
  if (path.endsWith('/reports/monthly')) return json({});
  if (path.startsWith('/api/research/runs/')) return raw(arenaResult);
  if (path.endsWith('/research/compare/stream')) {
    const events = [{ type: 'started', runId: arenaResult.runId }, ...traces.map(trace => ({ type: 'node_completed', node: trace.role, trace, output: trace.role === 'data' ? snapshot : trace.role === 'judge' ? decision : opinion })), { type: 'completed', result: arenaResult }];
    return route.fulfill({ contentType: 'text/event-stream', body: events.map(({ type, ...event }) => `event: ${type}\ndata: ${JSON.stringify(event)}\n\n`).join('') });
  }
  return json({});
});

const paths = ['/', '/watchlists', '/portfolios', '/compare', '/arena', '/runs', '/reports', '/notifications', '/account', '/login', '/register'];
async function open(path) {
  await page.goto(base + path, { waitUntil: 'networkidle' });
  await page.locator(path.split('?')[0] === '/login' || path.split('?')[0] === '/register' ? '.auth-content h2' : '.page-head h1').waitFor();
}
async function widthCheck(label) {
  const dimension = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth }));
  if (dimension.scroll > dimension.width + 1) {
    console.log(await page.evaluate(() => [...document.querySelectorAll('body *')].filter(node => node.getBoundingClientRect().right > innerWidth + 1).slice(0, 8).map(node => ({ tag: node.tagName, class: node.className, right: node.getBoundingClientRect().right }))));
    await page.screenshot({ path: `${output}/overflow.png`, fullPage: true });
  }
  assert.ok(dimension.scroll <= dimension.width + 1, `${label} 横向溢出 ${JSON.stringify(dimension)}`);
}

try {
  await open('/');
  // 11 页 × 正常/空数据/失败状态。页面形态各存桌面及手机截图。
  for (mode of (process.env.SKIP_MATRIX ? [] : ['normal', 'empty', 'failure'])) {
    for (const path of paths) {
      await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await open(path);
      if (path === '/' && mode !== 'empty') {
        await page.getByRole('textbox', { name: '基金名称或代码' }).fill('000001');
        await page.getByRole('button', { name: '查询基金', exact: true }).click();
        await page.waitForTimeout(100);
      }
      if (path === '/arena' && mode !== 'empty') { await page.getByRole('button', { name: '开始研究', exact: true }).click(); await page.waitForTimeout(150); }
      if (path === '/compare' && mode !== 'empty') { await page.getByRole('button', { name: '对比基金', exact: true }).click(); await page.waitForTimeout(150); }
      const name = path === '/' ? 'research' : path.slice(1);
      for (const width of [320, 390, 430, 768, 900, 901, 1024, 1440, 1920]) {
        await page.setViewportSize({ width, height: 1000 });
        await widthCheck(`${mode} ${path} ${width}`);
        if (width === 390 || width === 1440) await page.screenshot({ path: `${output}/${name}-${mode}-${width}.png`, fullPage: true });
      }
      if (mode === 'failure' && !['/login', '/register'].includes(path)) assert.ok((await page.locator('body').innerText()).includes('暂') || (await page.locator('body').innerText()).includes('失败'), `${path} 应提示请求失败`);
    }
    console.log(`11 页面 ${mode} 状态：9 种宽度通过`);
  }
  mode = 'normal';
  await page.setViewportSize({ width: 390, height: 900 });
  await open('/?code=000001');
  await page.getByRole('button', { name: 'AI 研究', exact: true }).click();
  await page.getByRole('textbox', { name: '向研究助手提问' }).fill('尚未提交的问题');
  await page.getByRole('button', { name: '基金概览', exact: true }).click();
  assert.equal(await page.locator('.agent').isVisible(), false);
  await page.getByRole('button', { name: 'AI 研究', exact: true }).click();
  assert.equal(await page.getByRole('textbox', { name: '向研究助手提问' }).inputValue(), '尚未提交的问题');
  assert.deepEqual(await page.locator('.mobile-tabbar a span').allTextContents(), ['研究', '自选', '组合', '我的']);

  await open('/watchlists');
  await page.getByRole('button', { name: '待研究 · 0' }).click();
  await page.getByText('这个分组还没有基金', { exact: true }).waitFor();
  await page.getByRole('button', { name: '全部基金', exact: true }).click();
  await page.locator('.watch-fund-card strong').getByText(longName).waitFor();

  await open('/notifications');
  await page.getByRole('button', { name: '未读 1' }).click();
  await page.getByRole('button', { name: '标为已读', exact: true }).click();
  await page.getByRole('button', { name: '未读 0' }).waitFor();

  await open('/account');
  await page.getByRole('button', { name: '保存风险画像' }).click();
  assert.equal(await page.locator('.unanswered').count(), 1);
  await page.locator('#question-q1 select').selectOption('0');
  await page.locator('#question-q2 select').selectOption('1');
  await page.getByRole('button', { name: '保存风险画像' }).click();
  await page.getByText('风险画像已保存', { exact: true }).waitFor();
  assert.equal(JSON.parse(writes.findLast(item => item.path.endsWith('/risk-profile')).body).answers.q1, 0);

  race = true;
  await open('/portfolios');
  const selector = page.getByLabel('当前组合');
  await selector.selectOption('p1');
  await selector.selectOption('p2');
  await page.waitForTimeout(650);
  assert.match(await page.locator('.holding-row').first().innerText(), /测试稳健基金/);
  assert.equal(await page.locator('.metric-card').first().locator('b').textContent(), '980.00');
  race = false;
  missingValuation = true;
  await page.getByRole('button', { name: '刷新数据', exact: true }).click();
  await page.waitForTimeout(150);
  assert.deepEqual(await page.locator('.metric-card b').allTextContents(), ['—', '—', '—'], '缺失估值和收益率不得显示为零');
  missingValuation = false;
  await page.getByText('导入 CSV / Excel 流水', { exact: true }).click();
  await page.locator('input[type=file]').setInputFiles({ name: 'fixture.csv', mimeType: 'text/csv', buffer: Buffer.from('fundCode,shares\nBAD,1') });
  await page.getByRole('button', { name: '检查导入文件' }).click();
  await page.getByText('基金代码格式错误', { exact: false }).waitFor();
  assert.equal(await page.getByRole('button', { name: '确认导入', exact: true }).isDisabled(), true);
  invalidImport = false;
  await page.locator('input[type=file]').setInputFiles({ name: 'valid.csv', mimeType: 'text/csv', buffer: Buffer.from('fundCode,shares\n000001,1') });
  assert.equal(await page.locator('.import-errors').count(), 0);
  await page.getByRole('button', { name: '检查导入文件' }).click();
  await page.getByRole('button', { name: '确认导入', exact: true }).click();
  assert.equal(JSON.parse(writes.findLast(item => item.path.endsWith('/commit')).body).fileSha256, 'checksum');

  await open('/runs');
  await page.getByRole('button', { name: /等待确认的组合研究/ }).click();
  await page.getByRole('button', { name: '确认继续', exact: true }).waitFor();
  await page.getByRole('button', { name: '确认继续', exact: true }).click();
  await page.locator('.research-report h4').getByText('第二份报告').waitFor();
  assert.ok(writes.some(item => item.path.endsWith('/approvals/a1')));
  runStatus = 'WAITING_APPROVAL';
  await page.getByRole('button', { name: /等待确认的组合研究/ }).click();
  await page.getByRole('button', { name: '拒绝', exact: true }).click();
  await page.locator('.run-status b').getByText('已拒绝', { exact: true }).waitFor();
  reportMissing = true;
  await page.getByRole('button', { name: /比较基金风险与表现/ }).click();
  await page.getByText('任务已结束，正文尚未准备好', { exact: true }).waitFor();
  reportMissing = false;
  race = true;
  await open('/reports');
  await page.getByRole('button', { name: '查看月报', exact: true }).last().click();
  await page.waitForTimeout(650);
  await page.locator('.research-report h4').getByText('第二份报告').waitFor();
  assert.equal(await page.locator('.research-report h4').getByText('第一份报告').count(), 0);
  race = false;

  await open('/login?next=/notifications');
  await page.getByPlaceholder('用户名', { exact: true }).fill('fixture');
  await page.getByPlaceholder('密码', { exact: true }).fill('test-only');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByText('测试表单错误，请检查输入', { exact: true }).waitFor();
  await page.getByRole('link', { name: '没有账号？注册' }).click();
  await page.waitForURL('**/register?next=%2Fnotifications');
  await page.waitForLoadState('networkidle');
  await page.getByPlaceholder('用户名', { exact: true }).fill('fixture');
  await page.getByPlaceholder('显示名').fill('测试用户');
  await page.getByPlaceholder('密码（6-128）').fill('test-only');
  await page.getByRole('button', { name: '创建账号', exact: true }).click();
  await page.getByText('测试表单错误，请检查输入', { exact: true }).waitFor();
  assert.deepEqual(errors, [], '浏览器运行时或非预期控制台错误');
  console.log(process.env.SKIP_MATRIX ? 'finance-app: 交互复查通过' : 'finance-app: PASS（11 页面 × 3 状态 × 9 宽度；手机切换、分组、已读、问卷零值、组合竞争、导入、审批及拒绝、正文未就绪、报告竞争、认证错误与返回路径）');
} finally { await browser.close(); }
