import fs from 'node:fs';

const page = fs.readFileSync('app/login/page.tsx', 'utf8');
const body = page.match(/function safeNext\(\)\{([\s\S]*?)\n\}/)?.[1];
if (!body || !page.includes('location.href=safeNext()')) {
  console.error('login must return through safeNext');
  process.exit(1);
}
const safeNext = new Function('window', body);
const destination = (search) => safeNext({ location: { search } });
if (destination('?next=/notifications') !== '/notifications') {
  console.error('login should return to the original page');
  process.exit(1);
}
if (destination('?next=//evil.example') !== '/' || destination('?next=https://evil.example') !== '/') {
  console.error('login must reject an off-site return path');
  process.exit(1);
}

console.log('login-return: original page is kept and off-site next is rejected');
