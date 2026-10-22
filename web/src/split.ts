// The same split rule as the API (SplitCalculator.java), so the form can show each person's exact
// share before saving. Every participant first gets floor(total * weight / totalWeight); the cents
// left over go one each to the largest dropped fractions, ties to the lower member id.

export interface Allocation { memberId: number; cents: number }

export function splitProportionally(totalCents: number, weights: { memberId: number; weight: number }[]): Allocation[] {
  const participants = weights.filter((w) => w.weight > 0).sort((a, b) => a.memberId - b.memberId);
  const totalWeight = participants.reduce((sum, p) => sum + p.weight, 0);
  if (totalCents <= 0 || totalWeight === 0) {
    return [];
  }
  // Safe integers: at most 100,000,000 cents x 1,000 shares, far below 2^53.
  const amounts = participants.map((p) => Math.floor((totalCents * p.weight) / totalWeight));
  const dropped = participants.map((p) => (totalCents * p.weight) % totalWeight);
  let leftover = totalCents - amounts.reduce((sum, a) => sum + a, 0);
  const order = participants
    .map((p, i) => ({ i, dropped: dropped[i], memberId: p.memberId }))
    .sort((a, b) => b.dropped - a.dropped || a.memberId - b.memberId);
  for (const { i } of order) {
    if (leftover === 0) break;
    amounts[i] += 1;
    leftover -= 1;
  }
  return participants.map((p, i) => ({ memberId: p.memberId, cents: amounts[i] }));
}

export function splitEqually(totalCents: number, memberIds: number[]): Allocation[] {
  return splitProportionally(totalCents, memberIds.map((memberId) => ({ memberId, weight: 1 })));
}

/**
 * For "exact amounts": spreads what is still unassigned over the members whose field is empty,
 * with the same rule. Members that already have an amount keep it.
 */
export function distributeRest(totalCents: number, assigned: Map<number, number | null>): Map<number, number> {
  const fixed = [...assigned.values()].reduce<number>((sum, cents) => sum + (cents ?? 0), 0);
  const open = [...assigned.entries()].filter(([, cents]) => cents === null).map(([id]) => id);
  const result = new Map<number, number>();
  assigned.forEach((cents, id) => { if (cents !== null) result.set(id, cents); });
  splitEqually(Math.max(0, totalCents - fixed), open).forEach((a) => result.set(a.memberId, a.cents));
  return result;
}
