import { describe, expect, it } from 'vitest';
import { describeActivity, isMoney, unreadCount } from './activity';
import type { ActivityItem } from './api';

const names = new Map([[1, 'Anna'], [2, 'Ben'], [3, 'Lucy']]);
const nameOf = (id: number | null) => (id === null ? 'Someone' : names.get(id) ?? 'Someone');

function item(fields: Partial<ActivityItem>): ActivityItem {
  return {
    id: 'X1', kind: 'EXPENSE_ADDED', at: '2026-10-25T12:00:00Z', actorMemberId: null, expenseId: null, paymentId: null,
    memberId: null, description: null, amountCents: null, category: null, fromMemberId: null, toMemberId: null,
    myChangeCents: null, ...fields,
  };
}

describe('describeActivity', () => {
  it('writes from the reader’s point of view', () => {
    expect(describeActivity(item({ actorMemberId: 2, memberId: 2, description: 'Pizza' }), nameOf, 1)).toBe('Ben added Pizza');
    expect(describeActivity(item({ actorMemberId: 1, memberId: 2, description: 'REWE' }), nameOf, 1))
      .toBe('You added REWE, paid by Ben');
    expect(describeActivity(item({ kind: 'PAYMENT_RECORDED', fromMemberId: 2, toMemberId: 1 }), nameOf, 1)).toBe('Ben paid you');
    expect(describeActivity(item({ kind: 'PASSWORD_RESET', actorMemberId: 3, memberId: 1 }), nameOf, 1))
      .toBe('Lucy made a password reset link for you');
    expect(describeActivity(item({ kind: 'MEMBER_JOINED', memberId: 3 }), nameOf, 1)).toBe('Lucy joined');
    expect(describeActivity(item({ kind: 'EXPENSE_DELETED', actorMemberId: 2, description: 'Kino' }), nameOf, 1)).toBe('Ben deleted Kino');
  });

  it('handles entries from before the creator was recorded', () => {
    expect(describeActivity(item({ actorMemberId: null, memberId: 3, description: 'Bahn' }), nameOf, 1)).toBe('Lucy added Bahn');
  });
});

describe('isMoney', () => {
  it('keeps expenses and payments, not member events', () => {
    expect(isMoney(item({ kind: 'EXPENSE_DELETED' }))).toBe(true);
    expect(isMoney(item({ kind: 'PAYMENT_UNDONE' }))).toBe(true);
    expect(isMoney(item({ kind: 'MEMBER_JOINED' }))).toBe(false);
    expect(isMoney(item({ kind: 'GROUP_CREATED' }))).toBe(false);
  });

  it('does not count member events as new', () => {
    expect(unreadCount([item({ kind: 'MEMBER_JOINED', actorMemberId: 2 })], null, 1)).toBe(0);
  });
});

describe('unreadCount', () => {
  it('counts what others did since the last visit', () => {
    const items = [
      item({ id: 'a', at: '2026-10-25T12:00:00Z', actorMemberId: 2 }),
      item({ id: 'b', at: '2026-10-25T11:00:00Z', actorMemberId: 1 }),
      item({ id: 'c', at: '2026-10-24T10:00:00Z', actorMemberId: 3 }),
    ];
    expect(unreadCount(items, '2026-10-25T00:00:00Z', 1)).toBe(1);
    expect(unreadCount(items, null, 1)).toBe(2);
  });
});
