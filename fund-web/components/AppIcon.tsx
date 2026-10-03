import type { CSSProperties } from "react";

export type IconName =
  | "home"
  | "star"
  | "wallet"
  | "tasks"
  | "sparkles"
  | "report"
  | "bell"
  | "settings"
  | "arrow"
  | "search"
  | "menu"
  | "close"
  | "shield"
  | "user";

// 共用线性图标，统一笔画与尺寸；装饰图标不重复向读屏软件播报文字。
const paths: Record<IconName, string[]> = {
  user: ["M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8", "M4 21v-2a8 8 0 0 1 16 0v2"],
  home: ["M3 10 12 3l9 7", "M5 9v11h5v-6h4v6h5V9"],
  star: [
    "m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-2.9-5.6 2.9 1.1-6.2L3 9.6l6.2-.9Z",
  ],
  wallet: [
    "M4 6h15v14H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h13",
    "M19 10h-5v6h5",
    "M16 13h.01",
  ],
  tasks: ["M8 5h12M8 12h12M8 19h12", "m2 5 1 1 2-2m-3 8 1 1 2-2m-3 8 1 1 2-2"],
  sparkles: [
    "m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z",
    "M20 2v4m-2-2h4",
  ],
  report: ["M6 3h9l4 4v14H6Z", "M14 3v5h5M9 12h7M9 16h7"],
  bell: ["M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9", "M10 21h4"],
  settings: [
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8",
    "M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2",
  ],
  arrow: ["M5 12h14m-5-5 5 5-5 5"],
  search: ["M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14", "m15 15 6 6"],
  menu: ["M4 6h16M4 12h16M4 18h16"],
  close: ["m6 6 12 12M6 18 18 6"],
  shield: ["m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z", "m8 12 3 3 5-6"],
};

export default function AppIcon({
  name,
  size = 20,
  style,
}: {
  name: IconName;
  size?: number;
  style?: CSSProperties;
}) {
  return (
    <svg
      aria-hidden="true"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={style}
    >
      {paths[name].map((path, index) => (
        <path key={index} d={path} />
      ))}
    </svg>
  );
}
