import { describe, expect, it } from 'vitest';
import { distributeRest, splitEqually, splitProportionally } from './split';

// The cases mirror SplitCalculatorTest.java, so the preview in the form matches the server.
describe('split rule (same as the API)', () => {
  it('gives leftover cents to the lowest member ids when splitting equally', () => {
    expect(splitEqually(1000, [7, 3, 5])).toEqual([
      { memberId: 3, cents: 334 }, { memberId: 5, cents: 333 }, { memberId: 7, cents: 333 },
    ]);
    expect(splitEqually(1001, [1, 2, 3]).map((a) => a.cents)).toEqual([334, 334, 333]);
  });

  it('splits by shares', () => {
    expect(splitProportionally(1000, [{ memberId: 1, weight: 2 }, { memberId: 2, weight: 1 }]))
      .toEqual([{ memberId: 1, cents: 667 }, { memberId: 2, cents: 333 }]);
  });

  it('lets the largest dropped fraction beat the lower id', () => {
    expect(splitProportionally(10, [{ memberId: 1, weight: 1 }, { memberId: 2, weight: 2 }]).map((a) => a.cents))
      .toEqual([3, 7]);
  });

  it('ignores members with zero shares and empty input', () => {
    expect(splitProportionally(500, [{ memberId: 1, weight: 0 }, { memberId: 2, weight: 3 }]))
      .toEqual([{ memberId: 2, cents: 500 }]);
    expect(splitEqually(0, [1, 2])).toEqual([]);
    expect(splitEqually(100, [])).toEqual([]);
  });

  it('always adds up to the total and never differs by more than a cent', () => {
    let seed = 42;
    const random = (max: number) => { seed = (seed * 1103515245 + 12345) % 2 ** 31; return seed % max; };
    for (let run = 0; run < 5000; run++) {
      const total = 1 + random(1_000_000);
      const weights = Array.from({ length: 1 + random(8) }, (_, i) => ({ memberId: i + 1, weight: 1 + random(5) }));
      const result = splitProportionally(total, weights);
      const totalWeight = weights.reduce((s, w) => s + w.weight, 0);
      expect(result.reduce((s, a) => s + a.cents, 0)).toBe(total);
      result.forEach((a, i) => {
        const floor = Math.floor((total * weights[i].weight) / totalWeight);
        expect(a.cents === floor || a.cents === floor + 1).toBe(true);
      });
    }
  });
});

describe('distributeRest', () => {
  it('fills only the empty fields with what is left', () => {
    const result = distributeRest(1000, new Map([[1, 400], [2, null], [3, null]]));
    expect(Object.fromEntries(result)).toEqual({ 1: 400, 2: 300, 3: 300 });
  });

  it('keeps the rule for odd remainders', () => {
    const result = distributeRest(1000, new Map([[1, 0], [2, null], [3, null], [4, null]]));
    expect([...result.values()].reduce((s, c) => s + c, 0)).toBe(1000);
    expect(result.get(2)).toBe(334);
  });
});
