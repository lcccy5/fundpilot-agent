'use client';
import { FormEvent, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import AppShell from '../../components/AppShell';
import { api, clearGuestWatch, guestWatch } from '../../lib/session';

type FundCode = string | { value: string };
type Item = { itemId: string; fundCode: FundCode; version: number };
type Group = { groupId: string; displayName: string; version: number; items: Item[] };
type FundProfile = {
  fundCode: string;
  name: string;
  fundType?: string;
  managementCompany?: string;
  fundManager?: string;
};

/**
 * 从自选条目里取出 6 位代码，兼容字符串和包在 value 里的两种返回。
 * 两种形状都不符合时读取 value 会得到 undefined，卡片会显示成“基金 undefined”。
 */
const codeOf = (item: Item) => (typeof item.fundCode === 'string' ? item.fundCode : item.fundCode.value);

/**
 * 按代码去重后逐只拉基金资料，成功一条就回调一条。
 * 单只 4xx/5xx 或网络失败被吞掉，对应卡片会一直停在“基金资料加载中”，其它基金继续显示。
 */
const hydrateProfiles = (next: Group[], onProfile: (fundCode: string, profile: FundProfile) => void) => {
  const fundCodes = [...new Set(next.flatMap((group) => group.items.map(codeOf)))].filter(Boolean);
  void Promise.all(
    fundCodes.map(async (fundCode) => {
      try {
        onProfile(fundCode, await api<FundProfile>(`/api/v1/funds/${fundCode}`));
      } catch {
        /* 资料暂时不可用时继续显示代码，不把整页打成失败。 */
      }
    }),
  );
};

/**
 * 按分组展示云端自选，并支持新建分组、加入、删除和把本机自选合并上去。
 * 列表加载时有等待文案；成功但没有任何分组时显示空自选。列表 4xx/5xx 时顶栏报错，但正文仍会显示“你的自选还是空的”，失败和空列表分不开。
 */
export default function WatchlistsPage() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [profiles, setProfiles] = useState<Record<string, FundProfile>>({});
  const [groupName, setGroupName] = useState('');
  const [codes, setCodes] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState('正在加载你的自选…');
  const [loading, setLoading] = useState(true);
  const [addingTo, setAddingTo] = useState<string>();
  const [removingItem, setRemovingItem] = useState<string>();
  const [recentlyAdded, setRecentlyAdded] = useState<string>();

  /**
   * 把分组里的基金资料写进本地缓存。
   * 某只基金资料失败时该代码不会出现在缓存里，卡片名称退回“基金 {代码}”。
   */
  const showProfiles = (next: Group[]) =>
    hydrateProfiles(next, (fundCode, profile) =>
      setProfiles((current) => ({ ...current, [fundCode]: profile })),
    );

  /**
   * 重新拉取全部分组。announce 为 false 时不改顶栏，留给紧接着的同步结果文案。
   * 未登录、4xx/5xx 或网络失败时顶栏显示接口文案或“请先登录后查看云端自选”，已有分组不会被清空。
   */
  const load = async (announce = true) => {
    try {
      const next = await api<Group[]>('/api/v1/watchlists');
      setGroups(next);
      showProfiles(next);
      if (announce) {
        const count = next.reduce((sum, group) => sum + group.items.length, 0);
        setNotice(count ? `已加载 ${count} 只自选基金` : '还没有自选基金，先添加一只吧');
      }
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '请先登录后查看云端自选');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    api<Group[]>('/api/v1/watchlists')
      .then((next) => {
        setGroups(next);
        showProfiles(next);
        const count = next.reduce((sum, group) => sum + group.items.length, 0);
        setNotice(count ? `已加载 ${count} 只自选基金` : '还没有自选基金，先添加一只吧');
      })
      .catch((x) => setNotice(x instanceof Error ? x.message : '请先登录后查看云端自选'))
      .finally(() => setLoading(false));
  }, []);

  /**
   * 用填写的名称新建一个分组。
   * 名称为空白时不发请求，只提示补全；重名、未登录或 5xx 时顶栏显示接口文案或“创建分组失败”，输入框保留。
   */
  const create = async (e: FormEvent) => {
    e.preventDefault();
    const name = groupName.trim();
    if (!name) return setNotice('请填写分组名称');
    try {
      const created = await api<Group>('/api/v1/watchlists', {
        method: 'POST',
        body: JSON.stringify({ name }),
      });
      setGroups((current) => [...current, created]);
      setGroupName('');
      setNotice(`已创建“${created.displayName}”分组`);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '创建分组失败');
    }
  };
  /**
   * 把 6 位代码加入指定分组。
   * 不是 6 位数字时不发请求；加入中按钮禁用。409 或其它 4xx/5xx 时顶栏显示“加入失败”或接口文案，输入保留。
   */
  const add = async (group: Group) => {
    const fundCode = codes[group.groupId] ?? '';
    if (!/^\d{6}$/.test(fundCode)) return setNotice('请输入 6 位基金代码');
    setAddingTo(group.groupId);
    try {
      const updated = await api<Group>(`/api/v1/watchlists/${group.groupId}/items`, {
        method: 'POST',
        body: JSON.stringify({ fundCode }),
      });
      setGroups((current) => current.map((row) => (row.groupId === group.groupId ? updated : row)));
      setCodes((current) => ({ ...current, [group.groupId]: '' }));
      setRecentlyAdded(fundCode);
      setNotice(`${fundCode} 已加入“${group.displayName}”`);
      showProfiles([updated]);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '加入失败');
    } finally {
      setAddingTo(undefined);
    }
  };
  /**
   * 确认后按版本号删除一条自选。
   * 用户取消确认时什么都不做；版本冲突或 4xx/5xx 时提示刷新，分组列表保持删除前的内容。
   */
  const remove = async (group: Group, item: Item) => {
    const fundCode = codeOf(item),
      fundName = profiles[fundCode]?.name ?? `基金 ${fundCode}`;
    if (!window.confirm(`确定从“${group.displayName}”中删除 ${fundName} 吗？`)) return;
    setRemovingItem(item.itemId);
    try {
      const updated = await api<Group>(
        `/api/v1/watchlists/${group.groupId}/items/${item.itemId}?version=${item.version}`,
        { method: 'DELETE' },
      );
      setGroups((current) => current.map((row) => (row.groupId === group.groupId ? updated : row)));
      setRecentlyAdded((current) => (current === fundCode ? undefined : current));
      setNotice(`已从“${group.displayName}”删除 ${fundName}`);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '删除失败，请刷新后重试');
    } finally {
      setRemovingItem(undefined);
    }
  };
  /**
   * 把本机未同步的代码合并到云端，成功后清掉本机缓存再重新加载。
   * 本机没有代码时不发请求；未登录或合并接口失败时保留本机缓存，顶栏提示重试。重新加载失败时由加载逻辑另写顶栏，可能盖住同步结果。
   */
  const merge = async () => {
    const local = guestWatch();
    if (!local.length) return setNotice('此设备没有待同步的自选');
    try {
      const result = await api<{ added: number; existing: number }>('/api/v1/watchlists/merge-local', {
        method: 'POST',
        body: JSON.stringify({ fundCodes: local }),
      });
      clearGuestWatch();
      await load(false);
      setNotice(`同步完成：新增 ${result.added} 只，已有 ${result.existing} 只`);
    } catch (x) {
      setNotice(x instanceof Error ? x.message : '同步失败，请登录后重试');
    }
  };

  const localCodes = guestWatch();
  const total = useMemo(() => groups.reduce((sum, group) => sum + group.items.length, 0), [groups]);
  return (
    <AppShell notice={notice}>
      <section className="workspace watchlist-page">
        <div className="watchlist-title">
          <div>
            <p>WATCHLIST</p>
            <h2>我的自选</h2>
            <span>集中查看你关注的基金，从这里继续研究。</span>
          </div>
          <div className="watchlist-total">
            <b>{total}</b>
            <small>只基金 · {groups.length} 个分组</small>
          </div>
        </div>
        {localCodes.length > 0 && (
          <section className="local-watch">
            <div>
              <b>此设备还有 {localCodes.length} 只自选未同步</b>
              <small>{localCodes.join('、')} · 同步后换设备也能看到</small>
            </div>
            <button className="primary" onClick={merge}>
              同步到我的账户
            </button>
          </section>
        )}
        {loading ? (
          <div className="empty-panel">正在加载自选基金…</div>
        ) : groups.length ? (
          <div className="watch-groups">
            {groups.map((group) => (
              <section key={group.groupId} className="watch-group">
                <div className="group-heading">
                  <div>
                    <b>{group.displayName}</b>
                    <small>{group.items.length} 只基金</small>
                  </div>
                </div>
                {group.items.length ? (
                  <div className="watch-fund-grid">
                    {group.items.map((item) => {
                      const fundCode = codeOf(item),
                        profile = profiles[fundCode];
                      return (
                        <article
                          key={item.itemId}
                          className={`watch-fund-card ${recentlyAdded === fundCode ? 'just-added' : ''}`}
                        >
                          <div className="watch-fund-main">
                            <span className="fund-avatar">{profile?.name?.slice(0, 1) ?? '基'}</span>
                            <div>
                              <strong>{profile?.name ?? `基金 ${fundCode}`}</strong>
                              <small>
                                <code>{fundCode}</code>
                                {profile?.fundType && ` · ${profile.fundType}`}
                              </small>
                            </div>
                          </div>
                          <div className="watch-fund-meta">
                            <span>{profile?.managementCompany ?? '基金资料加载中'}</span>
                            {profile?.fundManager && <span>基金经理 {profile.fundManager}</span>}
                          </div>
                          <div className="watch-fund-actions">
                            <Link href={`/?code=${fundCode}`}>
                              查看详情与研究 <span aria-hidden="true">→</span>
                            </Link>
                            <button
                              type="button"
                              className="remove-watch"
                              disabled={removingItem === item.itemId}
                              onClick={() => void remove(group, item)}
                            >
                              {removingItem === item.itemId ? '删除中…' : '删除'}
                            </button>
                          </div>
                        </article>
                      );
                    })}
                  </div>
                ) : (
                  <div className="watch-empty">
                    <b>这个分组还没有基金</b>
                    <span>在下方输入 6 位基金代码即可加入。</span>
                  </div>
                )}
                <form
                  className="add-fund-form"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void add(group);
                  }}
                >
                  <label>
                    <span>添加基金</span>
                    <input
                      aria-label={`添加基金到${group.displayName}`}
                      inputMode="numeric"
                      value={codes[group.groupId] ?? ''}
                      onChange={(e) =>
                        setCodes((current) => ({
                          ...current,
                          [group.groupId]: e.target.value.replace(/\D/g, '').slice(0, 6),
                        }))
                      }
                      placeholder="输入 6 位基金代码"
                    />
                  </label>
                  <button disabled={addingTo === group.groupId}>
                    {addingTo === group.groupId ? '正在加入…' : '加入自选'}
                  </button>
                </form>
              </section>
            ))}
          </div>
        ) : (
          <div className="watch-empty primary-empty">
            <b>你的自选还是空的</b>
            <span>先创建一个分组，再加入想持续关注的基金。</span>
          </div>
        )}
        <details className="create-group-panel">
          <summary>＋ 新建自选分组</summary>
          <form onSubmit={create}>
            <label>
              分组名称
              <input
                value={groupName}
                onChange={(e) => setGroupName(e.target.value)}
                placeholder="例如：长期关注、机器人主题"
              />
            </label>
            <button>创建分组</button>
          </form>
        </details>
      </section>
    </AppShell>
  );
}
