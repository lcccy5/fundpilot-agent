"use client";

import { ReactNode, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import AppIcon, { type IconName } from "./AppIcon";
import {
  api,
  clearSession,
  currentAccessToken,
  restoreSession,
} from "../lib/session";

const navigation: {
  title: string;
  items: { href: string; icon: IconName; label: string }[];
}[] = [
  {
    title: "研究空间",
    items: [
      { href: "/", icon: "home", label: "研究工作台" },
      { href: "/arena", icon: "sparkles", label: "多 Agent 研究" },
      { href: "/runs", icon: "tasks", label: "研究任务" },
      { href: "/compare", icon: "report", label: "基金对比" },
    ],
  },
  {
    title: "资产与记录",
    items: [
      { href: "/watchlists", icon: "star", label: "我的自选" },
      { href: "/portfolios", icon: "wallet", label: "我的组合" },
      { href: "/reports", icon: "report", label: "月报" },
      { href: "/notifications", icon: "bell", label: "通知" },
    ],
  },
];
const mobileLinks = [
  { href: "/", icon: "home", label: "研究" },
  { href: "/watchlists", icon: "star", label: "自选" },
  { href: "/portfolios", icon: "wallet", label: "组合" },
  { href: "/account", icon: "user", label: "我的" },
] as const;

function noticeTone(notice: string) {
  if (/失败|错误|无法|没有权限|请先登录|未完成|暂不可用/.test(notice))
    return "error";
  if (/正在/.test(notice)) return "progress";
  if (/已|完成|同步/.test(notice)) return "success";
  return "info";
}

/** 应用外壳统一导航和页面状态，各业务页面只负责自己的内容与交互。 */
export default function AppShell({
  children,
  notice,
  title = "基金研究",
  kicker = "FUND RESEARCH",
  variant = "default",
}: {
  children: ReactNode;
  notice?: string;
  title?: string;
  kicker?: string;
  variant?: "default" | "auth";
}) {
  const [user, setUser] = useState<{
    displayName?: string;
    username?: string;
    roles?: string[];
  } | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const active = usePathname() ?? "";
  const displayName = user?.displayName || user?.username || "访客";
  const activeLabel =
    navigation
      .flatMap((group) => group.items)
      .find((item) => item.href === active)?.label || title;

  useEffect(() => {
    let disposed = false;
    void restoreSession()
      .then(async (ok) => {
        if (!ok && !currentAccessToken()) return;
        try {
          const currentUser = await api<{
            displayName?: string;
            username?: string;
            roles?: string[];
          }>("/api/v1/users/me");
          if (!disposed) setUser(currentUser);
        } catch {
          if (!disposed) setUser(null);
        }
      })
      .catch(() => {
        if (!disposed) setUser(null);
      });
    return () => {
      disposed = true;
    };
  }, []);

  // 手机菜单可以用 Escape 关闭，关闭后把键盘焦点还给入口按钮。
  useEffect(() => {
    if (!menuOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
        menuButton.current?.focus();
      }
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [menuOpen]);

  const logout = async () => {
    try {
      await api("/api/v1/auth/logout", { method: "POST" });
    } finally {
      clearSession();
      window.location.assign("/login");
    }
  };

  if (variant === "auth")
    return (
      <div className="auth-shell">
        <header className="auth-toolbar">
          <Link className="product-brand" href="/">
            <span className="product-logo">
              <AppIcon name="sparkles" />
            </span>
            FundPilot
          </Link>
          <Link href="/">
            返回研究首页 <AppIcon name="arrow" size={16} />
          </Link>
        </header>
        <main className="main auth-main">
          <section className="auth-story">
            <span className="intro-eyebrow">每一次判断，都有依据</span>
            <h1>
              看清资产，
              <br />
              也看懂它背后的风险。
            </h1>
            <p>
              保存关注的基金，记录自己的组合，
              <br />让 AI 帮你整理数据与公开资料。
            </p>
            <div className="auth-features">
              <span>
                <AppIcon name="star" />
                关注基金
              </span>
              <span>
                <AppIcon name="wallet" />
                管理组合
              </span>
              <span>
                <AppIcon name="shield" />
                研究有据
              </span>
            </div>
          </section>
          <div className="auth-content">
            {children}
            {notice && (
              <p
                role="status"
                className={`notice notice-${noticeTone(notice)}`}
              >
                {notice}
              </p>
            )}
          </div>
        </main>
        <footer className="auth-footer">FundPilot · 你的基金研究空间</footer>
      </div>
    );

  return (
    <div className="product-shell">
      <aside className={`product-sidebar${menuOpen ? " is-open" : ""}`}>
        <Link
          className="product-brand"
          href="/"
          onClick={() => setMenuOpen(false)}
        >
          <span className="product-logo">
            <AppIcon name="sparkles" size={22} />
          </span>
          <span>
            FundPilot<small>你的基金研究空间</small>
          </span>
        </Link>
        <nav id="primary-nav" aria-label="主导航">
          {navigation.map((group) => (
            <div className="nav-group" key={group.title}>
              <p>{group.title}</p>
              {group.items.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  aria-current={active === item.href ? "page" : undefined}
                  className={active === item.href ? "active" : ""}
                  onClick={() => setMenuOpen(false)}
                >
                  <AppIcon name={item.icon} />
                  <span>{item.label}</span>
                  {active === item.href && <span className="nav-active-dot" />}
                </Link>
              ))}
            </div>
          ))}
          {user?.roles?.includes("ADMIN") && (
            <div className="nav-group">
              <p>管理</p>
              <Link
                href="/mcp"
                aria-current={active === "/mcp" ? "page" : undefined}
                className={active === "/mcp" ? "active" : ""}
                onClick={() => setMenuOpen(false)}
              >
                <AppIcon name="settings" />
                <span>工具治理</span>
              </Link>
            </div>
          )}
        </nav>
        <div className="sidebar-bottom">
          <div className="sidebar-note">
            <AppIcon name="shield" />
            <b>让研究有据可查</b>
            <p>结合基金数据与公开资料，保留每次分析的来源。</p>
          </div>
          <Link
            className="sidebar-account"
            href={user ? "/account" : "/login"}
            onClick={() => setMenuOpen(false)}
          >
            <span className="product-avatar">{displayName.slice(0, 1)}</span>
            <span>
              <b>{displayName}</b>
              <small>{user ? "账户与风险画像" : "登录以保存研究记录"}</small>
            </span>
            <AppIcon name="arrow" size={16} />
          </Link>
          {user && (
            <button className="sidebar-logout" type="button" onClick={logout}>
              退出登录
            </button>
          )}
        </div>
      </aside>
      <div className="product-frame">
        <header className="product-toolbar">
          <Link className="mobile-brand" href="/">
            FundPilot<span>基金研究</span>
          </Link>
          <div className="toolbar-breadcrumb">
            <span>FundPilot</span>
            <span>/</span>
            <b>{activeLabel}</b>
          </div>
          <div className="toolbar-actions">
            <Link href="/compare" className="toolbar-compare">
              <AppIcon name="report" size={16} />
              基金对比
            </Link>
            <Link
              href="/notifications"
              className="toolbar-icon"
              aria-label="查看通知"
            >
              <AppIcon name="bell" />
            </Link>
            <Link
              href={user ? "/account" : "/login"}
              className="product-avatar"
              aria-label={user ? "查看账户与风险画像" : "登录 / 注册"}
            >
              {displayName.slice(0, 1)}
            </Link>
          </div>
          <button
            ref={menuButton}
            type="button"
            className="product-menu-toggle"
            aria-label={menuOpen ? "关闭菜单" : "菜单"}
            aria-expanded={menuOpen}
            aria-controls="primary-nav"
            onClick={() => setMenuOpen((open) => !open)}
          >
            <AppIcon name={menuOpen ? "close" : "menu"} />
          </button>
        </header>
        <main className="main">
          <header className="page-head">
            <div>
              <p>{kicker}</p>
              <h1>{title}</h1>
            </div>
            {notice && (
              <label
                role="status"
                className={`notice notice-${noticeTone(notice)}`}
              >
                <i />
                {notice}
              </label>
            )}
          </header>
          {children}
        </main>
      </div>
      <nav className="mobile-tabbar" aria-label="快捷导航">
        {mobileLinks.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active === item.href ? "page" : undefined}
            className={active === item.href ? "active" : ""}
            onClick={() => setMenuOpen(false)}
          >
            <AppIcon name={item.icon} />
            <span>{item.label}</span>
          </Link>
        ))}
      </nav>
    </div>
  );
}
