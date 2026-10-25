// Small helpers for the insight charts, kept apart from the drawing so they can be tested.

/** Clean axis ticks from 0 up to at least maxCents: 0 / 50 € / 100 € ... in steps of 1, 2 or 5 × 10^n euros. */
export function niceTicks(maxCents: number, count = 4): number[] {
  if (maxCents <= 0) return [0];
  const rough = maxCents / count;
  const power = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((f) => f * power).find((s) => s >= rough) ?? 10 * power;
  const stepCents = Math.max(100, Math.ceil(step / 100) * 100); // whole euros on the axis
  const ticks = [];
  for (let t = 0; t < maxCents + stepCents; t += stepCents) {
    ticks.push(t);
    if (t >= maxCents) break;
  }
  return ticks;
}

/** 125000 -> "1.250 €", 120000000 -> "1,2 Mio. €": short euro labels for an axis. */
export function compactEuro(cents: number): string {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR', notation: 'compact', maximumFractionDigits: 1 })
    .format(cents / 100).replace(/\s/g, ' ');
}

/**
 * A column with a 4px rounded top and a square foot on the baseline, as an SVG path.
 * Zero-height columns draw nothing.
 */
export function columnPath(x: number, width: number, top: number, base: number, radius = 4): string {
  const height = base - top;
  if (height <= 0) return '';
  const r = Math.min(radius, width / 2, height);
  return `M${x},${base}V${top + r}Q${x},${top} ${x + r},${top}H${x + width - r}Q${x + width},${top} ${x + width},${top + r}V${base}Z`;
}
