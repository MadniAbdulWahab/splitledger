/**
 * A random UUID (version 4) for Idempotency-Key headers.
 *
 * crypto.randomUUID() only exists on secure pages (HTTPS or localhost); the AWS demo is served over
 * plain HTTP, where it is undefined. crypto.getRandomValues() works everywhere, so the UUID is
 * built from 16 random bytes by hand in that case: same format, same randomness.
 */
export function newIdempotencyKey(source: Crypto = crypto): string {
  if (typeof source.randomUUID === 'function') {
    return source.randomUUID();
  }
  const bytes = source.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // RFC 4122 variant
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Today in the browser's time zone as yyyy-mm-dd (toISOString would give the UTC date). */
export function localToday(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}
