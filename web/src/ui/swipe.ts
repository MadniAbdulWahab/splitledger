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
    let startY = 0;
    let startTime = 0;
    let distance = 0;
    let dragging = false;

    const start = (e: TouchEvent) => {
      dragging = el.scrollTop <= 0;
      startY = e.touches[0]!.clientY;
      startTime = Date.now();
      distance = 0;
    };
    const move = (e: TouchEvent) => {
      if (!dragging) return;
      const dy = e.touches[0]!.clientY - startY;
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
