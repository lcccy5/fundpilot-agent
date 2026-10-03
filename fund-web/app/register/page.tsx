"use client";
import { FormEvent, useState } from "react";
import { api, setSession } from "../../lib/session";
import AppShell from "../../components/AppShell";

/** 只接受站内返回路径，注册成功后恢复原来的页面。 */
function safeNext() {
  const next =
    new URLSearchParams(window.location.search).get("next") ?? "/watchlists";
  return next.startsWith("/") &&
    !next.startsWith("//") &&
    !next.includes("\\") &&
    !/[\u0000-\u001f]/.test(next)
    ? next
    : "/watchlists";
}

export default function RegisterPage() {
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [notice, setNotice] = useState("密码至少 6 位，不会保存在浏览器存储中");
  const [submitting, setSubmitting] = useState(false);
  /** 提交账户信息，服务端校验失败时保留表单并显示原因。 */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    try {
      const data = await api("/api/v1/auth/register", {
        method: "POST",
        body: JSON.stringify({ username, displayName, password }),
      });
      setSession(data.accessToken, data.accessTokenExpiresAt);
      location.href = safeNext();
    } catch (x) {
      setNotice(x instanceof Error ? x.message : "注册失败");
      setSubmitting(false);
    }
  };
  return (
    <AppShell notice={notice} title="注册" kicker="ACCOUNT" variant="auth">
      <section className="workspace">
        <p>账户</p>
        <h2>开启你的研究空间</h2>
        <p className="page-intro">建立账户，让关注与记录随时可查。</p>
        <form className="auth-form" data-hydrated="1" onSubmit={submit}>
          <label>
            用户名
            <input
              required
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="用户名"
            />
          </label>
          <label>
            显示名
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="显示名"
            />
          </label>
          <label>
            密码
            <input
              required
              maxLength={128}
              type="password"
              autoComplete="new-password"
              minLength={6}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="密码（6-128）"
            />
          </label>
          <button type="submit" disabled={submitting}>
            {submitting ? "正在创建…" : "创建账号"}
          </button>
          <a
            href="/login"
            onClick={(e) => {
              e.preventDefault();
              window.location.assign(
                `/login?next=${encodeURIComponent(safeNext())}`,
              );
            }}
          >
            已有账号？登录
          </a>
        </form>
      </section>
    </AppShell>
  );
}
