'use client';
import { useEffect, useState } from 'react';
import AppShell from '../../components/AppShell';
import { api } from '../../lib/session';

/**
 * 给管理员看当前 MCP 能力清单。
 * 未登录、403 或接口 5xx 时顶栏改为错误文案，正文停在“无权查看或尚未加载”；工具列表为空时仍显示哈希和地址，只是列表没有条目。加载过程中没有单独的等待文案。
 */
export default function McpPage() {
  const [info, setInfo] = useState<{
    schemaHash?: string;
    tools?: string[];
    sideEffects?: string;
    endpointRef?: string;
  } | null>(null);
  const [notice, setNotice] = useState('MCP 管理仅 ADMIN 可见；Schema 变化会暂停旧计划');
  useEffect(() => {
    api('/api/v1/mcp/capabilities')
      .then(setInfo)
      .catch((e) => setNotice(e instanceof Error ? e.message : '需要 ADMIN'));
  }, []);
  return (
    <AppShell notice={notice}>
      <section className="workspace">
        <p>MCP</p>
        <h2>受控 Capability</h2>
        {info ? (
          <>
            <p>schemaHash {info.schemaHash}</p>
            <p>
              {info.endpointRef} · {info.sideEffects}
            </p>
            <ol>{(info.tools ?? []).map((t) => <li key={t}>{t}</li>)}</ol>
          </>
        ) : (
          <p>无权查看或尚未加载。</p>
        )}
      </section>
    </AppShell>
  );
}
