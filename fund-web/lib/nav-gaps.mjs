/** Calendar days between YYYY-MM-DD dates. */
export function calendarGap(earlier, later) {
  return Math.round((Date.parse(later) - Date.parse(earlier)) / 86400000);
}

/**
 * Places observations by their real dates and joins every available NAV point.
 * A calendar gap is not enough evidence that the series is missing data: funds
 * do not publish NAV on weekends, exchange holidays, or suspension days.
 */
export function navChartSegments(points) {
  const ordered = [...points].filter(point => point?.navDate && point.unitNav != null)
    .sort((a, b) => a.navDate.localeCompare(b.navDate));
  if (ordered.length < 2) return { segments: [], min: 0, max: 0, points: ordered };
  const start = Date.parse(ordered[0].navDate);
  const span = Math.max(Date.parse(ordered.at(-1).navDate) - start, 86400000);
  const values = ordered.map(point => Number(point.unitNav));
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const placed = ordered.map(point => ({
    ...point,
    x: ((Date.parse(point.navDate) - start) / span) * 100,
    y: 90 - ((Number(point.unitNav) - min) / range) * 72,
  }));
  let path = `M ${placed[0].x.toFixed(2)} ${placed[0].y.toFixed(2)}`;
  for (let index = 1; index < placed.length; index += 1) {
    path += ` L ${placed[index].x.toFixed(2)} ${placed[index].y.toFixed(2)}`;
  }
  return { segments: [path], min, max, points: placed };
}
