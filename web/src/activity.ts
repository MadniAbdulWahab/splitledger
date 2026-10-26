import type { ActivityItem } from './api';

/**
 * One feed entry as a sentence from the reader's point of view: "Ben added Pizza", "You paid
 * Lucy", "Anna made a password reset link for you".
 */
export function describeActivity(item: ActivityItem, nameOf: (id: number | null) => string, me: number): string {
  const subject = (id: number | null) => (id === me ? 'You' : nameOf(id));
  const object = (id: number | null) => (id === me ? 'you' : nameOf(id));
  const what = item.description ?? 'an expense';
  switch (item.kind) {
    case 'GROUP_CREATED':
      return `${subject(item.actorMemberId)} started ${what}`;
    case 'EXPENSE_ADDED':
      return item.memberId !== null && item.memberId !== item.actorMemberId && item.actorMemberId !== null
        ? `${subject(item.actorMemberId)} added ${what}, paid by ${object(item.memberId)}`
        : `${subject(item.actorMemberId ?? item.memberId)} added ${what}`;
    case 'EXPENSE_EDITED':
      return `${subject(item.actorMemberId)} edited ${what}`;
    case 'EXPENSE_DELETED':
      return `${subject(item.actorMemberId)} deleted ${what}`;
    case 'PAYMENT_RECORDED':
      return `${subject(item.fromMemberId)} paid ${object(item.toMemberId)}`;
    case 'PAYMENT_UNDONE':
      return `${subject(item.actorMemberId)} undid a payment from ${object(item.fromMemberId)} to ${object(item.toMemberId)}`;
    case 'MEMBER_ADDED':
      return `${subject(item.actorMemberId)} invited ${object(item.memberId)}`;
    case 'MEMBER_JOINED':
      return `${subject(item.memberId)} joined`;
    case 'PASSWORD_RESET':
      return `${subject(item.actorMemberId)} made a password reset link for ${object(item.memberId)}`;
  }
}

const MONEY_KINDS: ActivityItem['kind'][] = ['EXPENSE_ADDED', 'EXPENSE_EDITED', 'EXPENSE_DELETED', 'PAYMENT_RECORDED', 'PAYMENT_UNDONE'];

/** Expenses and payments: the entries that explain how a balance came about. */
export function isMoney(item: ActivityItem): boolean {
  return MONEY_KINDS.includes(item.kind);
}

/** Money entries by others since the reader last opened the feed. */
export function unreadCount(items: ActivityItem[], lastSeen: string | null, me: number): number {
  return items.filter((i) => isMoney(i) && (lastSeen === null || i.at > lastSeen) && i.actorMemberId !== me).length;
}
