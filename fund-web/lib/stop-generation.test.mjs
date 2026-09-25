import assert from 'node:assert/strict';
import { acceptDelta, stopNotice } from './stop-generation.mjs';

assert.equal(acceptDelta(false, '已有', '更多'), '已有更多');
assert.equal(acceptDelta(true, '已有', '更多'), '已有');
assert.equal(stopNotice(false), '已停止');
assert.match(stopNotice(true), /已生成的内容仍保留/);
assert.match(stopNotice(true), /重试/);

console.log('stop-generation ok');
