import assert from 'node:assert/strict';
import { comparisonRequest, presentComparison } from './fund-compare.mjs';

const view = presentComparison({
  commonStartDate: '2026-01-05',
  commonEndDate: '2026-06-30',
  navBasis: 'UNIT_NAV',
  funds: [
    { fundCode: { value: '000001' }, navBasis: 'UNIT_NAV', cumulativeReturn: { status: 'AVAILABLE', value: 0.032 }, maxDrawdown: { status: 'AVAILABLE', value: -0.011 } },
    { fundCode: { value: '110022' }, navBasis: 'ACCUMULATED_NAV', cumulativeReturn: { status: 'UNAVAILABLE', unavailableReason: 'LOW_DATA_COVERAGE' }, maxDrawdown: { status: 'UNAVAILABLE', value: null, unavailableReason: 'LOW_DATA_COVERAGE' } },
  ],
});

assert.equal(view.rows[0].intervalReturn.includes('持有收益'), false);
assert.match(view.rows[0].intervalReturn, /区间单位净值变化 3\.20%/);
assert.match(view.rows[0].drawdown, /-1\.10%/);
assert.match(view.rows[1].drawdown, /缺失/);
assert.doesNotMatch(view.rows[1].drawdown, /0\.00/);
assert.match(view.notes.join(' '), /口径/);
assert.throws(() => comparisonRequest(['000001'], '2026-01-01', '2026-02-01'), /2 到 3/);
assert.deepEqual(comparisonRequest(['000001', '000001', '110022'], '2026-01-01', '2026-02-01').fundCodes, ['000001', '110022']);

console.log('fund-compare ok');
