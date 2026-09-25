import assert from 'node:assert/strict';
import { assessmentDate, firstUnanswered } from './questionnaire.mjs';

const questions = [{ id: 'q1' }, { id: 'q2' }];
assert.equal(firstUnanswered(questions, { q1: 1 }), 'q2');
assert.equal(firstUnanswered(questions, { q1: 1, q2: 2 }), null);
assert.equal(assessmentDate({ confirmedAt: '2026-08-01T00:00:00Z', level: 'BALANCED' }), '2026-08-01T00:00:00Z');
assert.equal(assessmentDate(null), null);

console.log('questionnaire ok');
