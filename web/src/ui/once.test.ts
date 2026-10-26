import { describe, expect, it } from 'vitest';
import { singleFlight } from './once';

describe('singleFlight', () => {
  it('ignores a second call while the first is running, then allows the next one', async () => {
    let calls = 0;
    let finish: () => void = () => {};
    const save = singleFlight(() => {
      calls++;
      return new Promise<string>((resolve) => { finish = () => resolve('saved'); });
    });

    const first = save();
    const second = save(); // the double tap
    finish();
    expect(await first).toBe('saved');
    expect(await second).toBeUndefined();
    expect(calls).toBe(1);

    const third = save(); // a new tap after the first finished
    finish();
    expect(await third).toBe('saved');
    expect(calls).toBe(2);
  });

  it('unlocks after a failure, so the user can try again', async () => {
    let fail = true;
    const save = singleFlight(async () => {
      if (fail) throw new Error('offline');
      return 'saved';
    });
    await expect(save()).rejects.toThrow('offline');
    fail = false;
    expect(await save()).toBe('saved');
  });
});
