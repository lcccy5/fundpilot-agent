const API=process.env.NEXT_PUBLIC_API_BASE??'';
const GUEST_WATCH_KEY='fundpilot.guestWatch';
const SESSION_KEY='fundpilot.accessSession';
type StoredSession={token:string;expiresAt:string};
function readStoredSession():StoredSession|undefined{
  if(typeof window==='undefined')return undefined;
  try{const raw=window.sessionStorage.getItem(SESSION_KEY);const value=raw?JSON.parse(raw) as StoredSession:undefined;return value?.token&&value?.expiresAt?value:undefined;}catch{return undefined;}
}
const storedSession=readStoredSession();
let accessToken:string|undefined=storedSession?.token;
let accessExpires=storedSession?Date.parse(storedSession.expiresAt):0;
let refreshInFlight:Promise<boolean>|undefined;

export async function readJsonResponse<T=unknown>(response:Response):Promise<T>{
  const text=await response.text();
  if(!text)return {} as T;
  try{return JSON.parse(text) as T;}catch{
    throw new Error('服务返回了非 JSON 响应，请确认前后端服务均已启动');
  }
}

function responseMessage(status:number,payload:unknown,statusText:string){
  const body=payload as {message?:string;msg?:string;error?:string};
  if(body?.message||body?.msg||body?.error)return body.message??body.msg??body.error!;
  if(status===401)return '请先登录后再使用此功能';
  if(status===403)return '当前账号没有权限执行此操作';
  return statusText||'请求失败，请稍后重试';
}

export function guestWatch():string[]{
  try{const raw=localStorage.getItem(GUEST_WATCH_KEY);return raw?JSON.parse(raw):[];}catch{return [];}
}
export function saveGuestWatch(codes:string[]){localStorage.setItem(GUEST_WATCH_KEY,JSON.stringify([...new Set(codes.filter(c=>/^\d{6}$/.test(c)))]));}
export function clearGuestWatch(){localStorage.removeItem(GUEST_WATCH_KEY);}
export function currentAccessToken(){return accessToken;}
export function setSession(token:string,expiresAt:string){accessToken=token;accessExpires=Date.parse(expiresAt);if(typeof window!=='undefined')window.sessionStorage.setItem(SESSION_KEY,JSON.stringify({token,expiresAt}));}
export function clearSession(){accessToken=undefined;accessExpires=0;if(typeof window!=='undefined')window.sessionStorage.removeItem(SESSION_KEY);}

async function refreshOnce(){
  const r=await fetch(`${API}/api/v1/auth/refresh`,{method:'POST',credentials:'include'});
  if(!r.ok){clearSession();return false;}
  const j=await readJsonResponse<{data?:{accessToken:string;accessTokenExpiresAt:string};accessToken?:string;accessTokenExpiresAt?:string}>(r);
  const data=j.data??j;
  if(!data.accessToken||!data.accessTokenExpiresAt)return false;
  setSession(data.accessToken,data.accessTokenExpiresAt);
  return true;
}

export async function api<T=any>(path:string,init:RequestInit={}):Promise<T>{
  const headers=new Headers(init.headers);
  if(accessToken)headers.set('Authorization',`Bearer ${accessToken}`);
  if(init.body&&!(init.body instanceof FormData)&&!headers.has('Content-Type'))headers.set('Content-Type','application/json');
  const send=()=>fetch(`${API}${path}`,{...init,headers,credentials:'include'});
  let r=await send();
  if((r.status===401||r.status===403)&&!path.startsWith('/api/v1/auth/')){
    refreshInFlight??=refreshOnce().finally(()=>{refreshInFlight=undefined;});
    if(await refreshInFlight){
      if(accessToken)headers.set('Authorization',`Bearer ${accessToken}`);
      r=await fetch(`${API}${path}`,{...init,headers,credentials:'include'});
    }
  }
  const json=await readJsonResponse<{data?:unknown;message?:string;msg?:string;error?:string}>(r);
  if(!r.ok){
    const sessionRequired=(r.status===401||r.status===403)&&!path.startsWith('/api/v1/auth/');
    throw new Error(sessionRequired?(r.status===401?'请先登录后再使用此功能':'当前账号没有权限执行此操作'):responseMessage(r.status,json,r.statusText));
  }
  return (json.data??json) as T;
}

export function hasFreshAccess(){return Boolean(accessToken)&&Date.now()<accessExpires-5000;}
export async function restoreSession(){if(hasFreshAccess())return true;return refreshOnce();}
