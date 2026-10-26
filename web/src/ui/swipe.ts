import { useEffect, useRef, type RefObject } from 'react';

// Swipe down to close a bottom sheet, as in phone apps: the sheet follows the finger and closes when
// dragged far enough or flicked down quickly; otherwise it springs back.

/**
 * Closes only when meant: dragged a third of the sheet's height (at least 140px), or flicked fast
 * over a clear distance. A small or slow pull, even a quick little one, springs back.
 */
export const CLOSE_DISTANCE = 140;
export const CLOSE_SHARE = 1 / 3;
export const FLICK_SPEED = 0.9;
export const FLICK_MIN_DISTANCE = 90;

export function shouldClose(distance: number, milliseconds: number, sheetHeight = 0): boolean {
  if (distance >= Math.max(CLOSE_DISTANCE, sheetHeight * CLOSE_SHARE)) return true;
  return distance >= FLICK_MIN_DISTANCE && distance / Math.max(1, milliseconds) >= FLICK_SPEED;
}

/** Movement in px before a gesture's direction is decided. */
export const AXIS_LOCK_DISTANCE = 8;

/**
 * The direction of a gesture, decided once after the first few pixels and kept for the rest of it,
 * as native apps do: a sideways swipe over the categories stays sideways even if the finger drifts
 * down. Vertical only when clearly more down/up than sideways. Null while still undecided.
 */
export function lockAxis(dx: number, dy: number): 'x' | 'y' | null {
  if (Math.hypot(dx, dy) < AXIS_LOCK_DISTANCE) return null;
  return Math.abs(dy) > Math.abs(dx) * 1.2 ? 'y' : 'x';
}

/**
 * A drag only starts while the sheet's content is scrolled to the very top, so scrolling a long
 * sheet works as usual and the sheet moves only once there is nothing left to scroll up.
 */
export function useSwipeToClose(sheet: RefObject<HTMLElement | null>, onClose: () => void) {
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    const el = sheet.current;
    if (!el) return undefined;
    let startX = 0;
    let startY = 0;
    let startTime = 0;
    let distance = 0;
    let dragging = false;
    let axis: 'x' | 'y' | null = null;

    const start = (e: TouchEvent) => {
      dragging = el.scrollTop <= 0;
      startX = e.touches[0]!.clientX;
      startY = e.touches[0]!.clientY;
      startTime = Date.now();
      distance = 0;
      axis = null;
    };
    const move = (e: TouchEvent) => {
      if (!dragging) return;
      const dx = e.touches[0]!.clientX - startX;
      const dy = e.touches[0]!.clientY - startY;
      axis ??= lockAxis(dx, dy);
      if (axis === null) return; // too early to tell; let the browser start whatever it does
      if (axis === 'x') {
        dragging = false; // a sideways swipe (categories, people): never moves the sheet
        return;
      }
      if (dy <= 0 || el.scrollTop > 0) {
        // Moving up: the content scrolls as usual.
        if (distance > 0) el.style.transform = '';
        distance = 0;
        if (el.scrollTop > 0) dragging = false;
        return;
      }
      e.preventDefault(); // the sheet moves instead of the page stretching
      distance = dy;
      el.style.transition = 'none';
      el.style.transform = `translateY(${dy}px)`;
    };
    const end = () => {
      if (!dragging || distance === 0) return;
      dragging = false;
      el.style.transition = 'transform 200ms cubic-bezier(0.2, 0.8, 0.2, 1)';
      if (shouldClose(distance, Date.now() - startTime, el.offsetHeight)) {
        el.style.transform = 'translateY(100%)';
        setTimeout(() => close.current(), 180);
      } else {
        el.style.transform = '';
      }
    };

    el.addEventListener('touchstart', start, { passive: true });
    el.addEventListener('touchmove', move, { passive: false });
    el.addEventListener('touchend', end);
    el.addEventListener('touchcancel', end);
    return () => {
      el.removeEventListener('touchstart', start);
      el.removeEventListener('touchmove', move);
      el.removeEventListener('touchend', end);
      el.removeEventListener('touchcancel', end);
    };
  }, [sheet]);
}
