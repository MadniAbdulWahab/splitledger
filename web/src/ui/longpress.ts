import { useMemo, useRef, type MouseEvent, type TouchEvent } from 'react';

// Press and hold (or right-click) on a list row opens its menu, as in phone apps. A finger that
// moves is scrolling, not holding, and the click that ends a long press does not also open the row.

export const LONG_PRESS_MS = 500;
export const MOVE_TOLERANCE = 10;

interface Timers {
  set: (callback: () => void, ms: number) => ReturnType<typeof setTimeout>;
  clear: (timer: ReturnType<typeof setTimeout>) => void;
}

/** The press logic without React or the DOM, so it can be tested with fake timers. */
export function createLongPress(onLongPress: () => void,
  timers: Timers = { set: (cb, ms) => setTimeout(cb, ms), clear: (t) => clearTimeout(t) }) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let startX = 0;
  let startY = 0;
  let fired = false;

  const cancel = () => {
    if (timer !== null) timers.clear(timer);
    timer = null;
  };

  return {
    start(x: number, y: number) {
      cancel();
      fired = false;
      startX = x;
      startY = y;
      timer = timers.set(() => {
        timer = null;
        fired = true;
        onLongPress();
      }, LONG_PRESS_MS);
    },
    move(x: number, y: number) {
      if (timer !== null && Math.hypot(x - startX, y - startY) > MOVE_TOLERANCE) cancel();
    },
    end: cancel,
    /** Whether the current press has opened the menu. */
    wasFired: () => fired,
    /** True once after a long press: the click that follows must not open the row as well. */
    takeFired() {
      const was = fired;
      fired = false;
      return was;
    },
  };
}

/** Handlers for a row: touch hold on phones, right-click (context menu) with a mouse. */
export function useLongPress(onLongPress: () => void) {
  const callback = useRef(onLongPress);
  callback.current = onLongPress;

  return useMemo(() => {
    const press = createLongPress(() => callback.current());
    return {
      onTouchStart: (e: TouchEvent) => press.start(e.touches[0]!.clientX, e.touches[0]!.clientY),
      onTouchMove: (e: TouchEvent) => press.move(e.touches[0]!.clientX, e.touches[0]!.clientY),
      onTouchEnd: (e: TouchEvent) => {
        // After a hold the browser would still send a mouse press and a click where the finger was,
        // which would land on the menu's backdrop and close it right away.
        if (press.wasFired()) e.preventDefault();
        press.end();
      },
      onTouchCancel: () => press.end(),
      onContextMenu: (e: MouseEvent) => {
        e.preventDefault();
        // Android also sends a context menu on a long touch; the timer has opened the menu already.
        if (!press.wasFired()) callback.current();
      },
      onClickCapture: (e: MouseEvent) => {
        if (press.takeFired()) {
          e.preventDefault();
          e.stopPropagation();
        }
      },
    };
  }, []);
}
