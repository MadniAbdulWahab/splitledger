// Amounts are integer cents everywhere, as in the API. Parsing works on the text, never through a
// floating-point number, so "0,1 + 0,2" problems cannot appear.

const AMOUNT = /^(\d{1,9})(?:[.,](\d{1,2}))?$/;

/** "12,50", "12.5", "12" -> 1250, 1250, 1200; null for anything else (also for 0). */
export function parseEuroToCents(input: string): number | null {
  const match = AMOUNT.exec(input.trim().replace(/\s|€/g, ''));
  if (!match) {
    return null;
  }
  const cents = Number(match[1]) * 100 + Number((match[2] ?? '0').padEnd(2, '0'));
  return cents > 0 ? cents : null;
}

const euro = new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' });

/** 1250 -> "12,50 €", -333 -> "-3,33 €" (German format, as on a bank statement). */
export function formatCents(cents: number): string {
  return euro.format(cents / 100);
}
