import { describe, expect, it } from 'vitest';
import { CLOSE_DISTANCE, FLICK_MIN_DISTANCE, lockAxis, shouldClose } from './swipe';

describe('lockAxis', () => {
  it('waits for a few pixels before deciding', () => {
    expect(lockAxis(3, 4)).toBeNull();
  });

  it('keeps a sideways swipe sideways even when the finger drifts down', () => {
    expect(lockAxis(30, 12)).toBe('x');
    expect(lockAxis(-20, 18)).toBe('x'); // nearly diagonal still counts as sideways
  });

  it('is vertical only when clearly more down than sideways', () => {
    expect(lockAxis(4, 20)).toBe('y');
  });
});

describe('shouldClose', () => {
  it('closes after a long enough drag, however slow', () => {
    expect(shouldClose(CLOSE_DISTANCE, 2000)).toBe(true);
    expect(shouldClose(CLOSE_DISTANCE - 1, 2000)).toBe(false);
  });

  it('needs a third of a tall sheet', () => {
    expect(shouldClose(200, 2000, 750)).toBe(false); // a third is 250
    expect(shouldClose(250, 2000, 750)).toBe(true);
  });

  it('closes on a clear, fast flick', () => {
    expect(shouldClose(FLICK_MIN_DISTANCE, 80)).toBe(true); // 1.1 px/ms
    expect(shouldClose(FLICK_MIN_DISTANCE, 400)).toBe(false); // slow: springs back
  });

  it('springs back after a small pull, even a quick one', () => {
    expect(shouldClose(20, 5)).toBe(false);
    expect(shouldClose(60, 40)).toBe(false); // 1.5 px/ms, but only 60px
  });
});
