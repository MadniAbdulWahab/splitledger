import { describe, expect, it } from 'vitest';
import { formatCents, parseEuroToCents } from './money';

describe('parseEuroToCents', () => {
  it.each([
    ['12,50', 1250],
    ['12.50', 1250],
    ['12,5', 1250],
    ['12', 1200],
    ['0,01', 1],
    [' 1 240,00 € ', 124000],
    ['0,10', 10],
  ])('reads %s as %d cents', (input, cents) => {
    expect(parseEuroToCents(input)).toBe(cents);
  });

  it.each(['', '0', '0,00', '-5', '12,505', 'abc', '1.234,56', '12,'])('rejects %j', (input) => {
    expect(parseEuroToCents(input)).toBeNull();
  });

  it('never rounds through floating point', () => {
    // 0.29 * 100 is 28.999999999999996 in JavaScript.
    expect(parseEuroToCents('0,29')).toBe(29);
    expect(parseEuroToCents('1,13')).toBe(113);
  });
});

describe('formatCents', () => {
  it('formats like a German bank statement', () => {
    expect(formatCents(1250).replace(/\s/g, ' ')).toBe('12,50 €');
    expect(formatCents(-333).replace(/\s/g, ' ')).toBe('-3,33 €');
    expect(formatCents(124000).replace(/\s/g, ' ')).toBe('1.240,00 €');
  });
});
