import fs from 'node:fs';

const page = fs.readFileSync('app/portfolios/page.tsx', 'utf8');
const compact = page.replace(/\s+/g, '');
const fileInput = page.match(/<input\s+type="file"[\s\S]*?\/>/)?.[0] ?? '';
if (!fileInput.includes('setBatch(null)')) {
  console.error('choosing a new file must clear the previous import errors');
  process.exit(1);
}
if (!page.includes('className="import-errors"') || !compact.includes('row.safeMessage||row.errorCode') || !page.includes('第 {row.sourceRowNumber} 行')) {
  console.error('import error rows must stay visible');
  process.exit(1);
}
if (!compact.includes('disabled={batch.invalidRows>0||committing}')) {
  console.error('invalid import rows must block commit');
  process.exit(1);
}

console.log('import-errors: error rows stay visible and block commit');
