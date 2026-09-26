'use client';
import { FormEvent, useState } from 'react';
import { api, setSession } from '../../lib/session';
import AppShell from '../../components/AppShell';

/**
 * 收集新账号资料，注册成功后保存访问令牌并进入自选页。
 * 密码框有最少 6 位的浏览器约束；服务端 4xx 校验、用户名冲突或 5xx 时留在本页，顶栏显示接口文案，已填内容保留。
 */
export default function RegisterPage() {
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [notice, setNotice] = useState('密码至少 6 位，不会保存在浏览器存储中');
  /**
   * 提交注册请求，成功后写入会话并跳到自选。
   * 接口 4xx/5xx、非 JSON 正文或网络失败时不跳转，顶栏改为错误文案；拿不到 Error 时显示“注册失败”。
   */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const data = await api('/api/v1/auth/register', {
        method: 'POST',
        body: JSON.stringify({ username, displayName, password }),
      });
      setSession(data.accessToken, data.accessTokenExpiresAt);
      location.href = '/watchlists';
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '注册失败');
    }
  };
  return (
    <AppShell notice={notice}>
      <section className="workspace">
        <p>ACCOUNT</p>
        <h2>注册</h2>
        <form className="auth-form" data-hydrated="1" onSubmit={submit}>
          <input
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="用户名"
          />
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="显示名"
          />
          <input
            type="password"
            autoComplete="new-password"
            minLength={6}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="密码（6-128）"
          />
          <button type="submit">创建账号</button>
        </form>
      </section>
    </AppShell>
  );
}
