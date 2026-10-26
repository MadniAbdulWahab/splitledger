import { useMemo, useRef, type FormEvent } from 'react';

// A second tap while an action is still running must not run it again (a double-booked expense).
// Disabling the button is not enough: it only takes effect after the next render, and two quick
// taps can both arrive before that. This lock is set synchronously on the first call.

/** Wraps an async action: calls while it is still running are ignored and resolve to undefined. */
export function singleFlight<A extends unknown[], R>(action: (...args: A) => Promise<R>): (...args: A) => Promise<R | undefined> {
  let running = false;
  return async (...args: A) => {
    if (running) return undefined;
    running = true;
    try {
      return await action(...args);
    } finally {
      running = false;
    }
  };
}

/** singleFlight for a component: one lock for its lifetime, always running the latest action. */
export function useSingleFlight<A extends unknown[], R>(action: (...args: A) => Promise<R>): (...args: A) => Promise<R | undefined> {
  const latest = useRef(action);
  latest.current = action;
  return useMemo(() => singleFlight((...args: A) => latest.current(...args)), []);
}

/** A form's onSubmit: cancels the browser's own submit every time, runs the action once at a time. */
export function useFormSubmit(action: () => Promise<unknown>): (e: FormEvent) => void {
  const guarded = useSingleFlight(action);
  return (e: FormEvent) => {
    e.preventDefault();
    void guarded();
  };
}
