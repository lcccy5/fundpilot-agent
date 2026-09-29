import assert from 'node:assert/strict';
import { navChartSegments } from './nav-gaps.mjs';

const chart = navChartSegments([
  { navDate: '2026-01-02', unitNav: '1.0000' },
  { navDate: '2026-01-05', unitNav: '1.0100' },
  { navDate: '2026-01-20', unitNav: '1.2000' },
]);

assert.equal(chart.segments.length, 1, 'market holidays must not break the NAV line');
assert.match(chart.segments[0], /^M .+ L .+ L /);
assert.doesNotMatch(chart.segments.join(' '), / C /, 'smoothing must not invent values across missing dates');
assert.equal(chart.points[1].navDate, '2026-01-05');
assert.ok(chart.points[1].x > chart.points[0].x);
assert.ok(chart.points[2].x - chart.points[1].x > chart.points[1].x - chart.points[0].x);

console.log('nav-gaps ok');
