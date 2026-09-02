import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  async rewrites() {
    const api=process.env.API_PROXY;
    if(!api)return [];
    return [{source:'/api/:path*',destination:`${api}/api/:path*`}];
  },
};

export default nextConfig;
