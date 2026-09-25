export function calendarGap(earlier: string, later: string): number;
export function navChartSegments<T extends { navDate: string; unitNav: string | number }>(points: T[]): {
  segments: string[];
  min: number;
  max: number;
  points: Array<T & { x: number; y: number }>;
};
