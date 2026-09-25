import assert from 'node:assert/strict';
import { presentRisk, riskFigure } from './portfolio-ledger.mjs';

assert.equal(riskFigure(null), null);
assert.equal(riskFigure(undefined), null);
assert.equal(riskFigure(0), '0.00%');
const missing = presentRisk({ coverage: 'PARTIAL', maxFundWeight: null, warnings: ['NAV_UNAVAILABLE:000001'] }, 1);
assert.equal(missing.ready, false);
assert.match(missing.reason, /NAV_UNAVAILABLE:000001/);
assert.equal(presentRisk(null, 0).ready, false);
const ready = presentRisk({ coverage: 'COMPLETE', maxFundWeight: 40, top3Weight: 80, concentrationStatus: 'MODERATE', asOfDate: '2026-08-01' }, 2);
assert.equal(ready.ready, true);
assert.equal(ready.maxFund, '40.00%');

console.log('portfolio-ledger ok');
