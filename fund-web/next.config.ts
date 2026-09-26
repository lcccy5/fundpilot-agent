import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  /**
   * 把 /api 转发到 API_PROXY。
   * 没配置代理地址时返回空规则，浏览器会打到前端自己的源，接口表现为 404 而不是连接错误。
   */
  async rewrites() {
    const api = process.env.API_PROXY;
    if (!api) return [];
    return [{ source: '/api/:path*', destination: `${api}/api/:path*` }];
  },
};

export default nextConfig;
