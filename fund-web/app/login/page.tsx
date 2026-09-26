'use client';
import { FormEvent, useState } from 'react';
import { api, setSession } from '../../lib/session';
import AppShell from '../../components/AppShell';

/**
 * 收集用户名和密码，成功后保存短期访问令牌并回到首页。
 * 用户名为空仍会提交，由接口返回 4xx；401、校验失败或其它 4xx/5xx 留在本页，顶栏显示接口文案。响应不是 Error 时显示“登录失败”。
 */
export default function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [notice, setNotice] = useState('登录后才会访问自选、组合和个性化 Agent');
  /**
   * 把表单交给登录接口，成功后写入会话并整页跳转。
   * 阻止默认提交后若接口返回 4xx/5xx、正文不是 JSON，或网络中断，则停在登录页并改写顶栏提示，不清空已填内容。
   */
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const data = await api('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      });
      setSession(data.accessToken, data.accessTokenExpiresAt);
      location.href = '/';
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '登录失败');
    }
  };
  return (
    <AppShell notice={notice}>
      <section className="workspace">
        <p>ACCOUNT</p>
        <h2>登录</h2>
        <form className="auth-form" data-hydrated="1" onSubmit={submit}>
          <input
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="用户名"
          />
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="密码"
          />
          <button type="submit">登录</button>
          <a href="/register">没有账号？注册</a>
        </form>
      </section>
    </AppShell>
  );
}
