const API = process.env.NEXT_PUBLIC_API_BASE ?? '';
const GUEST_WATCH_KEY = 'fundpilot.guestWatch';
const SESSION_KEY = 'fundpilot.accessSession';

type StoredSession = { token: string; expiresAt: string };

/**
 * 从 sessionStorage 取出上次保存的访问令牌。
 * 服务端渲染没有 window 时返回空；内容不是 JSON、缺少 token 或缺少过期时间时也返回空，避免把损坏记录当成已登录。
 */
function readStoredSession(): StoredSession | undefined {
  if (typeof window === 'undefined') return undefined;
  try {
    const raw = window.sessionStorage.getItem(SESSION_KEY);
    const value = raw ? (JSON.parse(raw) as StoredSession) : undefined;
    return value?.token && value?.expiresAt ? value : undefined;
  } catch {
    return undefined;
  }
}

const storedSession = readStoredSession();
let accessToken: string | undefined = storedSession?.token;
let accessExpires = storedSession ? Date.parse(storedSession.expiresAt) : 0;
let refreshInFlight: Promise<boolean> | undefined;

/**
 * 把响应体读成 JSON。
 * 空正文当成空对象，调用方仍要自己看 HTTP 状态；正文不是 JSON 时抛出错误，提示前后端可能没一起启动。网络中断发生在 text() 之前，由 fetch 抛出。
 */
export async function readJsonResponse<T = unknown>(response: Response): Promise<T> {
  const text = await response.text();
  if (!text) return {} as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new Error('服务返回了非 JSON 响应，请确认前后端服务均已启动');
  }
}

/**
 * 从错误响应里挑出给用户看的句子。
 * 正文里有 message、msg 或 error 时优先用它；401 提示登录，403 提示无权限，其余用状态文本，状态文本也为空时用通用失败句。不区分 5xx 与其它 4xx。
 */
function responseMessage(status: number, payload: unknown, statusText: string) {
  const body = payload as { message?: string; msg?: string; error?: string };
  if (body?.message || body?.msg || body?.error) return body.message ?? body.msg ?? body.error!;
  if (status === 401) return '请先登录后再使用此功能';
  if (status === 403) return '当前账号没有权限执行此操作';
  return statusText || '请求失败，请稍后重试';
}

/**
 * 读取只存在本机的自选代码。
 * 没有记录或 JSON 损坏时返回空数组，不抛错；localStorage 被禁用时同样返回空数组。
 */
export function guestWatch(): string[] {
  try {
    const raw = localStorage.getItem(GUEST_WATCH_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

/**
 * 只保留 6 位数字代码，去重后写回本机。
 * 非法代码被丢掉而不是报错；存储配额用尽或处于无痕限制时 setItem 会抛出，调用方需要自己接住。
 */
export function saveGuestWatch(codes: string[]) {
  localStorage.setItem(
    GUEST_WATCH_KEY,
    JSON.stringify([...new Set(codes.filter((c) => /^\d{6}$/.test(c)))]),
  );
}

/**
 * 删除本机自选缓存。
 * 键不存在时也成功；存储不可用时 removeItem 会抛出。
 */
export function clearGuestWatch() {
  localStorage.removeItem(GUEST_WATCH_KEY);
}

/**
 * 返回内存中的访问令牌。
 * 尚未登录或已经清除时得到 undefined，不会去读存储。
 */
export function currentAccessToken() {
  return accessToken;
}

/**
 * 记下访问令牌和过期时间，并在浏览器里同步到 sessionStorage。
 * 过期时间无法解析时数值变成 NaN，新鲜度检查会失败并在下次请求时走刷新；服务端渲染时只改内存。存储配额用尽时 setItem 抛错，内存已经先被更新。
 */
export function setSession(token: string, expiresAt: string) {
  accessToken = token;
  accessExpires = Date.parse(expiresAt);
  if (typeof window !== 'undefined') {
    window.sessionStorage.setItem(SESSION_KEY, JSON.stringify({ token, expiresAt }));
  }
}

/**
 * 清掉内存和 sessionStorage 里的访问令牌。
 * 服务端没有 window 时只清内存；存储不可用时 removeItem 会抛出，内存已经先被清空。
 */
export function clearSession() {
  accessToken = undefined;
  accessExpires = 0;
  if (typeof window !== 'undefined') {
    window.sessionStorage.removeItem(SESSION_KEY);
  }
}

/**
 * 用 HttpOnly 刷新cookie换一张新的访问令牌。
 * 刷新接口非 2xx 时清会话并返回 false；2xx 但缺少令牌或过期时间时返回 false 且保留旧会话。正文不是 JSON 时抛错。
 */
async function refreshOnce() {
  const r = await fetch(`${API}/api/v1/auth/refresh`, { method: 'POST', credentials: 'include' });
  if (!r.ok) {
    clearSession();
    return false;
  }
  const j = await readJsonResponse<{
    data?: { accessToken: string; accessTokenExpiresAt: string };
    accessToken?: string;
    accessTokenExpiresAt?: string;
  }>(r);
  const data = j.data ?? j;
  if (!data.accessToken || !data.accessTokenExpiresAt) return false;
  setSession(data.accessToken, data.accessTokenExpiresAt);
  return true;
}

/**
 * 带访问令牌调用 JSON 接口，并在 401/403 时尝试刷新一次再重放。
 * 登录注册等 /api/v1/auth/ 路径不会刷新。刷新失败后，401 提示登录、403 提示无权限；其它 4xx/5xx 使用响应文案。
 * 空正文当成空对象，非 JSON 正文抛错，网络失败由 fetch 抛出。成功时优先返回 data 字段。
 */
// Existing callers progressively type API responses; the permissive default preserves legacy endpoints.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export async function api<T = any>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  /**
   * 按当前请求头发一次请求。网络中断时抛出；4xx/5xx 不在这里抛，留给后面的状态判断。
   */
  const send = () => fetch(`${API}${path}`, { ...init, headers, credentials: 'include' });
  let r = await send();
  if ((r.status === 401 || r.status === 403) && !path.startsWith('/api/v1/auth/')) {
    refreshInFlight ??= refreshOnce().finally(() => {
      refreshInFlight = undefined;
    });
    if (await refreshInFlight) {
      if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
      r = await fetch(`${API}${path}`, { ...init, headers, credentials: 'include' });
    }
  }
  const json = await readJsonResponse<{ data?: unknown; message?: string; msg?: string; error?: string }>(r);
  if (!r.ok) {
    const sessionRequired = (r.status === 401 || r.status === 403) && !path.startsWith('/api/v1/auth/');
    throw new Error(
      sessionRequired
        ? r.status === 401
          ? '请先登录后再使用此功能'
          : '当前账号没有权限执行此操作'
        : responseMessage(r.status, json, r.statusText),
    );
  }
  return (json.data ?? json) as T;
}

/**
 * 判断内存里的访问令牌是否还没到提前 5 秒的过期线。
 * 没有令牌，或过期时间是 NaN 时返回 false，不抛错。
 */
export function hasFreshAccess() {
  return Boolean(accessToken) && Date.now() < accessExpires - 5000;
}

/**
 * 令牌仍新鲜时直接返回 true，否则走一次刷新。
 * 刷新接口失败返回 false；刷新响应不是 JSON 或网络中断时抛错，调用方需要接住。
 */
export async function restoreSession() {
  if (hasFreshAccess()) return true;
  return refreshOnce();
}
