/** Python Agent 返回的数据形状。字段名与 Pydantic 的 JSON 别名保持一致。 */
export type Role = 'data' | 'bull' | 'bear' | 'judge';
export type Trace = { role: Role; startedAt: string; finishedAt: string; durationMs: number };
export type Claim = { statement: string; evidenceIds: string[] };
export type Opinion = { summary: string; claims: Claim[]; limitations: string[] };
export type Decision = {
  preferredFundCode: string | null;
  conclusion: string;
  rationale: Claim[];
  disagreements: string[];
  limitations: string[];
};
export type Snapshot = {
  commonStartDate: string;
  commonEndDate: string;
  navBasis: string;
  funds: Array<{
    fundCode: string;
    observationCount: number;
    coverageStatus: string;
    metrics: Record<string, { status: string; value: string | number | null; evidenceId: string }>;
  }>;
};
export type ResearchResult = {
  runId: string;
  snapshot: Snapshot;
  bull: Opinion;
  bear: Opinion;
  decision: Decision;
  trace: Trace[];
};

export type ResearchEvent =
  | { type: 'started'; runId: string }
  | { type: 'node_completed'; node: Role; output: Snapshot | Opinion | Decision; trace: Trace }
  | { type: 'completed'; result: ResearchResult }
  | { type: 'failed'; message: string };

/** fetch 支持 POST；这里把返回的字节流还原成一条条 SSE 事件。 */
export async function readResearchStream(
  response: Response,
  onEvent: (event: ResearchEvent) => void,
): Promise<void> {
  if (!response.ok) {
    let message = `研究请求失败（HTTP ${response.status}）`;
    try { message = (await response.json() as { detail?: string }).detail || message; } catch { /* 保留 HTTP 提示 */ }
    throw new Error(message);
  }
  if (!response.body) throw new Error('服务器没有返回研究事件流');

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;

  function consume(block: string) {
    const lines = block.split('\n');
    const name = lines.find(line => line.startsWith('event: '))?.slice(7);
    const data = lines.filter(line => line.startsWith('data: ')).map(line => line.slice(6)).join('\n');
    if (!name || !data) return;
    const payload = JSON.parse(data) as Record<string, unknown>;
    const event = { type: name, ...payload } as ResearchEvent;
    if (event.type === 'completed' || event.type === 'failed') finished = true;
    onEvent(event);
  }

  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n');
      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        consume(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf('\n\n');
      }
      if (done) break;
    }
    if (buffer.trim()) consume(buffer);
    if (!finished) throw new Error('连接提前结束，研究结果尚未完成');
  } finally {
    reader.releaseLock();
  }
}
