import fs from 'node:fs';

const base=process.env.E2E_BASE_URL;
if (!base) {
  console.log('v3-browser: skipped (set E2E_BASE_URL to run Playwright against a live app)');
  process.exit(0);
}

const {chromium}=await import('playwright');

const user='e2euser'+Date.now().toString().slice(-8);
const password='correct-horse-battery';

const browser=await chromium.launch({headless:true});
const page=await browser.newPage();
const errors=[];
const seen=[];
const pageErrors=[];
page.on('pageerror',err=>pageErrors.push(String(err)));
page.on('console',msg=>{
  const text=msg.text();
  if (msg.type()==='error') pageErrors.push(text);
  if (/Bearer |accessToken|refreshToken/i.test(text)) errors.push('console leaked token: '+text.slice(0,80));
});
page.on('request',r=>seen.push(r.method()+' '+r.url()));
try {
  await page.goto(base+'/register',{waitUntil:'load',timeout:30000});
  await page.getByRole('heading',{name:'注册'}).waitFor({timeout:20000});
  await page.locator('form.auth-form[data-hydrated="1"]').waitFor({timeout:45000});
  await page.getByPlaceholder('用户名').fill(user);
  await page.getByPlaceholder('显示名').fill(user);
  await page.getByPlaceholder('密码（10-128）').fill(password);
  const registerWait=page.waitForResponse(r=>r.url().includes('/api/v1/auth/register')&&r.request().method()==='POST',{timeout:20000});
  await page.getByRole('button',{name:'创建账号'}).click();
  const registerRes=await registerWait;
  if (!registerRes.ok()) throw new Error('register failed HTTP '+registerRes.status()+' '+await registerRes.text());
  await page.waitForURL(/watchlists/,{timeout:20000});
  const storage=await page.evaluate(()=>({ls:Object.keys(localStorage),ss:Object.keys(sessionStorage)}));
  if (storage.ls.some(k=>/token|jwt|access/i.test(k))||storage.ss.some(k=>/token|jwt|access/i.test(k))) {
    throw new Error('token key found in web storage');
  }
  const dismissOverlay=async()=>{await page.locator('#__vinext_dev_error_overlay_root').evaluate(el=>el.remove()).catch(()=>{});};
  const clickButton=async(name)=>{await dismissOverlay();await page.getByRole('button',{name}).click({force:true});};
  await page.getByRole('button',{name:'新建分组'}).waitFor({timeout:20000});
  await clickButton('新建分组');
  await page.getByRole('link',{name:'我的组合'}).click();
  await page.getByRole('button',{name:'新建组合'}).waitFor({timeout:20000});
  await clickButton('新建组合');
  await clickButton('追加申购');
  await clickButton('查看持仓与收益');
  await page.getByRole('link',{name:'研究任务'}).click();
  await page.getByRole('button',{name:'提交异步任务'}).waitFor({timeout:20000});
  await clickButton('提交异步任务');
  await page.waitForTimeout(1500);
  const body=await page.textContent('body');
  if (!body||!/PLAN_AND_EXECUTE|研究任务|Run /.test(body)) throw new Error('runs page did not show a routed task');
  if (errors.length) throw new Error(errors.join('; '));
  fs.mkdirSync('../target/v3-acceptance',{recursive:true});
  fs.writeFileSync('../target/v3-acceptance/playwright.json',JSON.stringify({ok:true,user,pages:['register','watchlists','portfolios','runs']},null,2));
  console.log('v3-browser: passed register/watchlist/portfolio/runs with memory-only token');
} catch (e) {
  fs.mkdirSync('../target/v3-acceptance',{recursive:true});
  try {
    fs.writeFileSync('../target/v3-acceptance/playwright-fail.html', await page.content());
    fs.writeFileSync('../target/v3-acceptance/playwright-fail.json',JSON.stringify({pageErrors,seen:seen.slice(-80)},null,2));
    await page.screenshot({path:'../target/v3-acceptance/playwright-fail.png',fullPage:true});
  } catch {}
  throw e;
} finally {
  await browser.close();
}
