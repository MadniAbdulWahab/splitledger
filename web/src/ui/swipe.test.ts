import { describe, expect, it } from 'vitest';
import { CLOSE_DISTANCE, shouldClose } from './swipe';

describe('shouldClose', () => {
  it('closes after a long enough drag, however slow', () => {
    expect(shouldClose(CLOSE_DISTANCE, 2000)).toBe(true);
    expect(shouldClose(CLOSE_DISTANCE - 1, 2000)).toBe(false);
  });

  it('closes on a quick flick over a shorter distance', () => {
    expect(shouldClose(60, 80)).toBe(true); // 0.75 px/ms
    expect(shouldClose(60, 400)).toBe(false); // slow: springs back
  });

  it('ignores a tiny twitch even when fast', () => {
    expect(shouldClose(20, 5)).toBe(false);
  });
});
