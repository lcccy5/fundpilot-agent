import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'FundPilot | 基金智能研究台',
  description: '基于基金数据与 Agent 的个人研究工作台。',
};

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
