import fs from 'node:fs';
const session=fs.readFileSync('lib/session.ts','utf8');
if (/localStorage\.setItem\([^)]*(accessToken|refreshToken|Bearer)/i.test(session)) {
  console.error('access token must not be stored in localStorage');
  process.exit(1);
}
if (!session.includes('let accessToken')) {
  console.error('expected in-memory accessToken');
  process.exit(1);
}
for (const page of ['app/login/page.tsx','app/register/page.tsx','app/portfolios/page.tsx','app/watchlists/page.tsx','app/runs/page.tsx','app/notifications/page.tsx','app/reports/page.tsx']) {
  if (!fs.existsSync(page)) { console.error('missing '+page); process.exit(1); }
}
console.log('e2e-gate: memory token + login/portfolio/runs routes present');
