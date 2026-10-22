import { describe, expect, it } from 'vitest';
import { localToday, newIdempotencyKey } from './ids';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('newIdempotencyKey', () => {
  it('uses crypto.randomUUID when the page is a secure context', () => {
    expect(newIdempotencyKey()).toMatch(UUID_V4);
  });

  it('still works on a plain-HTTP page, where randomUUID does not exist', () => {
    // What a browser offers on http://<ip>: getRandomValues, but no randomUUID.
    const insecure = { getRandomValues: crypto.getRandomValues.bind(crypto) } as unknown as Crypto;

    const keys = new Set(Array.from({ length: 1000 }, () => newIdempotencyKey(insecure)));

    expect(keys.size).toBe(1000);
    keys.forEach((key) => expect(key).toMatch(UUID_V4));
  });
});

describe('localToday', () => {
  it('uses the local date, not the UTC date', () => {
    // 00:30 on 23 October in the local time zone is still 22 October in UTC east of Greenwich.
    expect(localToday(new Date(2026, 9, 23, 0, 30))).toBe('2026-10-23');
    expect(localToday(new Date(2026, 0, 5, 12, 0))).toBe('2026-01-05');
  });
});
