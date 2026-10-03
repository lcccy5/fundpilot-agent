"use client";
import { useEffect, useState } from "react";
import AppShell from "../../components/AppShell";
import { api } from "../../lib/session";

export default function McpPage() {
  const [info, setInfo] = useState<{
    schemaHash?: string;
    tools?: string[];
    sideEffects?: string;
    endpointRef?: string;
  } | null>(null);
  const [notice, setNotice] = useState(
    "此页保留旧 Java MCP 工具治理，仅 ADMIN 可见；Python Agent 使用独立工具注册表",
  );
  useEffect(() => {
    api("/api/v1/mcp/capabilities")
      .then(setInfo)
      .catch((e) => setNotice(e instanceof Error ? e.message : "需要 ADMIN"));
  }, []);
  return (
    <AppShell notice={notice} title="旧 Java 工具治理" kicker="ADMIN">
      <section className="workspace">
        <p>管理员</p>
        <h2>旧 Java 研究工具</h2>
        {info ? (
          <>
            <p>schemaHash {info.schemaHash}</p>
            <p>
              {info.endpointRef} · {info.sideEffects}
            </p>
            <ol>
              {(info.tools ?? []).map((t) => (
                <li key={t}>{t}</li>
              ))}
            </ol>
          </>
        ) : (
          <p>无权查看或尚未加载。</p>
        )}
      </section>
    </AppShell>
  );
}
