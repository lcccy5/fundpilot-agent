// 使用固定接口数据检查页面交互，避免视觉验收调用收费模型或写入真实账户。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { chromium } from 'playwright';

const base = process.env.E2E_BASE_URL || 'http://localhost:3001';
const output = '../target/research-theme';
fs.mkdirSync(output, { recursive: true });
// Windows 开发机可使用已安装的 Edge；其他环境使用 Playwright 的 Chromium。
const browser = await chromium.launch({ headless: true, ...(process.platform === 'win32' ? { channel: 'msedge' } : {}) });
const failures = [];
let delayedAnswer = false;
let signedIn = true;
let administrator = false;
const fund = { fundCode: '000001', name: '华夏成长混合', fundType: '混合型', fundManager: '测试经理', managementCompany: '测试基金管理有限公司', establishedDate: '2001-12-18' };
const nav = [ { navDate: '2026-09-28', unitNav: 1.12 }, { navDate: '2026-09-29', unitNav: 1.15 }, { navDate: '2026-09-30', unitNav: 1.14 } ];
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
page.on('pageerror', error => failures.push(error.message));
page.on('console', message => {
  if (message.type() !== 'error') return;
  // 游客用例主动返回 401；只排除这一个预期的刷新接口错误，仍检查其他控制台异常。
  if (!signedIn && message.text().includes('401') && message.location().url.endsWith('/api/v1/auth/refresh')) return;
  failures.push(message.text());
});
await page.route('**/api/**', async route => {
  const path = new URL(route.request().url()).pathname;
  const json = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data }) });
  if (path === '/api/v1/auth/refresh') return signedIn ? json({ accessToken: 'ui-fixture-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z' }) : route.fulfill({ status: 401, contentType: 'application/json', body: '{}' });
  if (path === '/api/v1/users/me') return json({ username: '演示用户', roles: administrator ? ['ADMIN'] : [] });
  if (path === '/api/v1/watchlists') return json([]);
  if (path === '/api/v1/funds/000001/nav') return json({ items: nav });
  if (path === '/api/v1/funds/000001/nav/sync') return json({});
  if (path === '/api/v1/funds/000001') return json(fund);
  if (path === '/api/v1/funds') return json([fund]);
  if (path === '/api/agent/conversations') return json({ conversationId: 'fixture-conversation' });
  if (path.endsWith('/cancel')) return json({ status: 'CANCELLED' });
  if (path.endsWith('/queryExecutionSummary')) return json({ run_id: 'fixture-run', tools: [], sources: [], agentOps: { available: false } });
  if (path === '/api/agent/chat/stream') {
    if (delayedAnswer) await new Promise(resolve => setTimeout(resolve, 1200));
    const events = [
      { type: 'run.started', runId: 'fixture-run', data: {} },
      { type: 'tool.completed', data: { toolName: 'get_fund_profile', durationMs: 12, evidenceIds: ['E01'] } },
      { type: 'evidence.verifying', data: {} },
      { type: 'answer.completed', runId: 'fixture-run', data: { answer: '本次已取得基金资料。测试研究结论 [E01]。', evidence: [{ evidenceId: 'E01', evidenceType: 'FUND_PROFILE', dataSource: '测试数据', excerpt: '用于页面验收的基金资料。' }] } },
    ];
    return route.fulfill({ contentType: 'text/event-stream', body: events.map(event => `data: ${JSON.stringify(event)}\n\n`).join('') });
  }
  return json([]);
});

async function checkWidth(label) {
  const dimensions = await page.evaluate(() => ({ content: document.documentElement.scrollWidth, screen: innerWidth }));
  if (dimensions.content > dimensions.screen + 1) {
    await page.screenshot({ path: `${output}/overflow.png`, fullPage: true });
    console.log(await page.evaluate(() => [...document.querySelectorAll('body *')].filter(element => element.getBoundingClientRect().right > innerWidth + 1).slice(0, 15).map(element => ({ tag: element.tagName, class: element.className, right: element.getBoundingClientRect().right }))));
  }
  assert.ok(dimensions.content <= dimensions.screen + 1, `${label} 出现横向溢出：${JSON.stringify(dimensions)}`);
}

try {
  await page.goto(base, { waitUntil: 'networkidle' });
  await page.getByText('查一只基金，再问它的表现和风险').waitFor();
  await checkWidth('桌面空状态');
  await page.screenshot({ path: `${output}/desktop-empty.png`, fullPage: true });
  await page.getByRole('textbox', { name: '基金名称或代码' }).fill('华夏');
  await page.getByRole('button', { name: '查询基金', exact: true }).click();
  await page.locator('.name-matches button').click();
  await page.locator('.fund-bar h2').waitFor();
  assert.equal(await page.locator('.fund-bar h2').textContent(), fund.name);
  await page.locator('.chart svg').waitFor();
  assert.match(await page.locator('.fund-quote').textContent(), /1\.1400/);
  await page.getByRole('button', { name: '近1个月', exact: true }).click();
  await page.getByRole('button', { name: '近1个月', exact: true }).getAttribute('aria-pressed').then(value => assert.equal(value, 'true'));
  await page.locator('.fund-details summary').click();
  await page.locator('.fund-details dd').getByText('测试基金管理有限公司', { exact: true }).waitFor();
  await page.locator('.fund-details summary').click();
  await page.getByRole('button', { name: '加入自选', exact: true }).click();
  await page.getByRole('button', { name: '已加入自选', exact: true }).waitFor();
  const composer = page.getByRole('textbox', { name: '向研究助手提问' });
  await composer.fill('请解释这只基金的风险');
  await composer.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true });
  assert.equal(await page.getByRole('button', { name: '发送研究问题' }).isVisible(), true, '中文输入法选词不得提交问题');
  await composer.press('Shift+Enter');
  await composer.press('End');
  await composer.type('以及数据日期');
  assert.match(await composer.inputValue(), /\n/, 'Shift+Enter 应保留换行');
  await composer.press('Enter');
  await page.getByText('测试研究结论', { exact: false }).waitFor();
  await page.locator('.messages button.evidence').click();
  await page.locator('.evidence-card').waitFor();
  await checkWidth('桌面有数据');
  const desktopColumns = await page.locator('.research-workbench').evaluate(element => getComputedStyle(element).gridTemplateColumns);
  assert.equal(desktopColumns.split(' ').length, 2, '桌面应为双栏');
  await page.evaluate(() => scrollTo(0, 0));
  await page.screenshot({ path: `${output}/desktop.png`, fullPage: true });
  await page.reload({ waitUntil: 'networkidle' });
  await page.getByText('测试研究结论', { exact: false }).waitFor();
  delayedAnswer = true;
  await page.getByRole('textbox', { name: '向研究助手提问' }).fill('请继续分析');
  await page.getByRole('button', { name: '发送研究问题' }).click();
  await page.getByRole('button', { name: '停止', exact: true }).click();
  await page.getByRole('button', { name: '发送研究问题' }).waitFor();
  assert.equal(await page.getByRole('textbox', { name: '向研究助手提问' }).isEnabled(), true);
  assert.equal(await page.getByText('测试研究结论', { exact: false }).count(), 1, '停止后不得追加迟到的回答');
  assert.equal(await page.locator('.agent-live').count(), 0, '停止后不得残留“研究中”的进度提示');
  await page.getByText('已停止研究', { exact: true }).waitFor();
  for (const width of [1920, 1440, 1200, 1100, 1051, 1024, 901, 900, 768, 430, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    await checkWidth(`${width}px`);
    if (width === 390) {
      await page.evaluate(() => scrollTo(0, 0));
      await page.screenshot({ path: `${output}/mobile.png`, fullPage: true });
      await page.screenshot({ path: `${output}/mobile-screen.png` });
      const footer = await page.locator('.mobile-tabbar').boundingBox();
      assert.ok(footer && footer.y + footer.height <= 901, '手机底部导航应固定在视口内');
    }
  }
  await page.getByRole('button', { name: '菜单', exact: true }).click();
  await page.keyboard.press('Escape');
  assert.equal(await page.getByRole('button', { name: '菜单', exact: true }).getAttribute('aria-expanded'), 'false');
  await page.getByRole('button', { name: '菜单', exact: true }).click();
  await page.locator('#primary-nav').getByRole('link', { name: '我的自选' }).click();
  await page.waitForURL('**/watchlists');
  await checkWidth('手机自选页面');
  // 全局导航和主题也作用于其他页面，检查主要页面能正常挂载且不会横向溢出。
  for (const path of ['/portfolios', '/runs', '/reports', '/compare', '/login']) {
    await page.goto(`${base}${path}`, { waitUntil: 'networkidle' });
    await page.locator(path === '/login' ? '.auth-content h2' : '.page-head h1').waitFor();
    await checkWidth(`手机 ${path}`);
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  administrator = true;
  await page.goto(`${base}/notifications`, { waitUntil: 'networkidle' });
  await page.locator('#primary-nav').getByRole('link', { name: '工具治理', exact: true }).waitFor();
  administrator = false;
  signedIn = false;
  await page.evaluate(() => sessionStorage.clear());
  await page.reload({ waitUntil: 'networkidle' });
  await page.getByRole('link', { name: '登录 / 注册', exact: true }).waitFor();
  assert.equal(await page.locator('#primary-nav').getByRole('link', { name: '工具治理', exact: true }).count(), 0, '游客不能看到管理入口');
  assert.deepEqual(failures, [], '浏览器运行时发生异常');
  console.log('research-theme: PASS（查询、图表、资料展开、自选、换行与 Enter 发送、引用、刷新恢复、停止、Escape 菜单、导航、游客及管理员入口；12 种宽度和主要页面）');
} finally {
  await browser.close();
}
