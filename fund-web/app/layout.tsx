import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'FundPilot | 基金智能研究台',
  description: '基于基金数据与 Agent 的个人研究工作台。',
};

/**
 * 包住所有页面的文档外壳，只输出语言和正文。
 * 这里不请求接口；子页面加载失败、列表为空或校验不通过时，由各自的提示承担，本层不会拦截。
 */
export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
