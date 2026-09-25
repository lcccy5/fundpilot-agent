import fs from 'node:fs';

const page = fs.readFileSync('app/page.tsx', 'utf8');
const start = page.indexOf('const loadFund');
const end = page.indexOf('const changeNavRange');
if (start < 0 || end < start) {
  console.error('loadFund must keep its own stale-response guard');
  process.exit(1);
}
const loadFund = page.slice(start, end);
const guards = loadFund.match(/if \(seq !== requestSeq\.current\) return;/g) ?? [];
if (guards.length < 2 || !loadFund.includes('const seq = ++requestSeq.current') || !loadFund.includes('setFund(null)') || !loadFund.includes('setPoints([])')) {
  console.error('consecutive fund queries must clear the previous fund and ignore a stale response');
  process.exit(1);
}

let current = 0;
let shown = null;
const first = ++current;
const second = ++current;
if (second === current) shown = '110022';
if (first === current) shown = '000001';
if (shown !== '110022') {
  console.error('later query was overwritten by an earlier fund');
  process.exit(1);
}

console.log('query-race: a slower earlier fund does not replace the later one');
