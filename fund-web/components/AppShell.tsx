'use client';
import { ReactNode, useEffect, useState } from 'react';
import Link from 'next/link';
import { api, clearSession, currentAccessToken, restoreSession } from '../lib/session';

const links = [
  ['/', '⌂', '总览'],
  ['/watchlists', '☆', '我的自选'],
  ['/portfolios', '▣', '我的组合'],
  ['/runs', '◌', '研究任务'],
  ['/reports', '▤', '月报'],
  ['/notifications', '◉', '通知'],
] as const;

/**
 * 画出全站顶栏，并把当前页提示放在标题旁边。
 * 刷新后若会话恢复失败且内存里没有令牌，就不再请求用户资料；/users/me 返回 4xx/5xx 或网络失败时头像区退回登录入口，正文仍渲染。
 * notice 含“失败”或“错误”时用错误样式，其它文案只作普通提示，空 notice 不占位。
 */
export default function AppShell({ children, notice }: { children: ReactNode; notice?: string }) {
  const [user, setUser] = useState<{ displayName?: string; username?: string; roles?: string[] } | null>(null);
  useEffect(() => {
    void restoreSession().then((ok) => {
      if (!ok && !currentAccessToken()) return;
      api('/api/v1/users/me')
        .then(setUser)
        .catch(() => setUser(null));
    });
  }, []);
  /**
   * 通知服务端注销，然后清掉本页会话并进入登录页。
   * 注销接口 4xx/5xx 或网络失败时仍会清本地令牌并跳转，避免停在已经失效的页面。
   */
  const logout = async () => {
    try {
      await api('/api/v1/auth/logout', { method: 'POST' });
    } finally {
      clearSession();
      location.href = '/login';
    }
  };
  const admin = Boolean(user?.roles?.includes('ADMIN'));
  const active = typeof window === 'undefined' ? '' : window.location.pathname;
  return (
    <main className="shell">
      <header className="topbar">
        <Link className="brand" href="/">
          <i>F</i>
          <span>FundPilot</span>
          <small>基金研究</small>
        </Link>
        <nav aria-label="主导航">
          {links.map(([href, icon, label]) => (
            <Link
              key={href}
              href={href}
              aria-current={active === href ? 'page' : undefined}
              className={active === href ? 'active' : ''}
            >
              <i>{icon}</i>
              {label}
            </Link>
          ))}
          {admin && (
            <Link href="/mcp" className={active === '/mcp' ? 'active' : ''}>
              <i>⚙</i>工具治理
            </Link>
          )}
        </nav>
        <div className="account-chip">
          {user ? (
            <>
              <Link className="avatar" href="/account" aria-label="查看账户与风险画像">
                {(user.displayName || user.username || '用户').slice(0, 1)}
              </Link>
              <Link className="account-name" href="/account">
                {user.displayName || user.username || '用户'}
              </Link>
              <button onClick={logout}>退出</button>
            </>
          ) : (
            <Link href="/login">登录 / 注册</Link>
          )}
        </div>
      </header>
      <section className="main">
        <header className="page-head">
          <div>
            <p>FUND RESEARCH</p>
            <h1>基金研究工作台</h1>
          </div>
          {notice && (
            <label className={notice.includes('失败') || notice.includes('错误') ? 'notice-error' : ''}>
              <i /> {notice}
            </label>
          )}
        </header>
        {children}
      </section>
    </main>
  );
}
