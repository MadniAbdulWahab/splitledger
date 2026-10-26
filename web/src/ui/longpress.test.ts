import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createLongPress, LONG_PRESS_MS } from './longpress';

describe('createLongPress', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('fires after holding still, and swallows the click that ends the press', () => {
    const opened = vi.fn();
    const press = createLongPress(opened);
    press.start(100, 100);
    vi.advanceTimersByTime(LONG_PRESS_MS);
    expect(opened).toHaveBeenCalledOnce();
    expect(press.wasFired()).toBe(true); // the release must not become a click on the menu's backdrop
    press.end();
    expect(press.takeFired()).toBe(true);
    expect(press.takeFired()).toBe(false); // only that one click
  });

  it('does not fire for a short tap', () => {
    const opened = vi.fn();
    const press = createLongPress(opened);
    press.start(100, 100);
    vi.advanceTimersByTime(LONG_PRESS_MS - 100);
    press.end();
    vi.advanceTimersByTime(1000);
    expect(opened).not.toHaveBeenCalled();
    expect(press.takeFired()).toBe(false);
  });

  it('does not fire when the finger moves (scrolling)', () => {
    const opened = vi.fn();
    const press = createLongPress(opened);
    press.start(100, 100);
    press.move(100, 104); // a little wobble is fine
    press.move(100, 130); // that is a scroll
    vi.advanceTimersByTime(1000);
    expect(opened).not.toHaveBeenCalled();
  });
});
