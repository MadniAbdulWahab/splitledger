// Dates and bank details in the formats people in Germany read on statements and receipts.

/** "2026-10-21" -> "21.10.2026". */
export function formatDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-');
  return `${day}.${month}.${year}`;
}

/** "2026-10-21" -> "21 Oct" (this year) or "21 Oct 2025"; for lists where the year is mostly noise. */
export function formatShortDate(isoDate: string, now: Date = new Date()): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  const date = new Date(year!, month! - 1, day!);
  return date.toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', ...(year === now.getFullYear() ? {} : { year: 'numeric' }),
  });
}

/** "2026-10" -> "October 2026". */
export function formatMonth(yearMonth: string): string {
  const [year, month] = yearMonth.split('-').map(Number);
  return new Date(year!, month! - 1, 1).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

/** "2026-10" -> "Oct". */
export function formatMonthShort(yearMonth: string): string {
  const [year, month] = yearMonth.split('-').map(Number);
  return new Date(year!, month! - 1, 1).toLocaleDateString('en-GB', { month: 'short' });
}

export function formatDateTime(isoInstant: string): string {
  return new Date(isoInstant).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' });
}

/** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", then the date. */
export function relativeTime(isoInstant: string, now: Date = new Date()): string {
  const then = new Date(isoInstant);
  const minutes = Math.floor((now.getTime() - then.getTime()) / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24 && then.getDate() === now.getDate()) return `${hours} h ago`;
  const days = Math.round((startOfDay(now) - startOfDay(then)) / 86_400_000);
  if (days <= 1) return 'yesterday';
  if (days < 7) return `${days} days ago`;
  return then.toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', ...(then.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
  });
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

/** "DE89370400440532013000" -> "DE89 3704 0044 0532 0130 00", as printed on bank cards. */
export function formatIban(iban: string): string {
  return iban.replace(/(.{4})/g, '$1 ').trim();
}

/** "Anna", "Anna and Ben", "Anna, Ben and Lucy". */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}
