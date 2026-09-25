/** First question that still has no selected choice. */
export function firstUnanswered(questions, answers) {
  const missing = (questions ?? []).find(question => answers?.[question.id] == null || answers[question.id] === '');
  return missing?.id ?? null;
}

export function assessmentDate(profile) {
  return profile?.confirmedAt ?? profile?.completedAt ?? profile?.assessmentDate ?? null;
}
