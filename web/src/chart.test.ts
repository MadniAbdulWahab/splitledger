import { describe, expect, it } from 'vitest';
import { columnPath, niceTicks } from './chart';

describe('niceTicks', () => {
  it('steps in 1, 2 or 5 times a power of ten and covers the maximum', () => {
    expect(niceTicks(36_000)).toEqual([0, 10_000, 20_000, 30_000, 40_000]);
    expect(niceTicks(9_000)).toEqual([0, 5_000, 10_000]);
    expect(niceTicks(150_000)).toEqual([0, 50_000, 100_000, 150_000]);
  });

  it('keeps whole euros for tiny amounts and a lone zero for nothing', () => {
    expect(niceTicks(250)).toEqual([0, 100, 200, 300]);
    expect(niceTicks(0)).toEqual([0]);
  });
});

describe('columnPath', () => {
  it('rounds the top and leaves the foot square', () => {
    expect(columnPath(10, 20, 50, 100)).toBe('M10,100V54Q10,50 14,50H26Q30,50 30,54V100Z');
    expect(columnPath(10, 20, 100, 100)).toBe('');
  });
});
