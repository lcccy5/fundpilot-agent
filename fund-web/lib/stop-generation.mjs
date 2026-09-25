/** Ignores later answer chunks after the user has stopped the run. */
export function acceptDelta(stopped, content, chunk) {
  if (stopped) return content;
  return `${content ?? ''}${chunk ?? ''}`;
}

/** Success stays at 已停止. A failed cancel keeps the text and says how to retry. */
export function stopNotice(cancelFailed) {
  return cancelFailed
    ? '已停止生成。取消没有成功，已生成的内容仍保留，可以再点停止重试。'
    : '已停止';
}
