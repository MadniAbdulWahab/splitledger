import { useEffect, useState } from 'react';
import { describeActivity, isMoney } from '../activity';
import type { ActivityItem } from '../api';
import { categoryOf } from '../categories';
import { relativeTime } from '../format';
import { formatCents } from '../money';
import { Avatar, Empty, Money, SkeletonRows } from '../ui/parts';
import { useGroup } from './context';

const ICONS: Partial<Record<ActivityItem['kind'], string>> = {
  PAYMENT_RECORDED: '💸', PAYMENT_UNDONE: '↩️', EXPENSE_DELETED: '🗑️',
};

/**
 * How your balance came about: every expense and payment, newest first, each with what it did to
 * your balance (+ the group owes you more, − you owe more). Opened from the home card; opening it
 * marks it as read. Member events (invited, joined) are left out: they move no money.
 */
export function ActivityFeed({ onSeen }: { onSeen: () => void }) {
  const { api, groupId, data, loaded, myMemberId, nameOf, openExpenseDetail } = useGroup();
  const [older, setOlder] = useState<ActivityItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(data.activityCursor);

  useEffect(() => { if (loaded) onSeen(); }, [loaded, data.activity, onSeen]);
  // The first page comes with the group data and is fresh after every change; older pages are fetched here.
  useEffect(() => {
    setOlder([]);
    setCursor(data.activityCursor);
  }, [data.activity, data.activityCursor]);

  async function loadMore() {
    if (!cursor) return;
    const page = await api.activity(groupId, cursor);
    setOlder((current) => [...current, ...page.items]);
    setCursor(page.nextCursor);
  }

  const items = [...data.activity, ...older].filter(isMoney);
  return (
    <>
      {!loaded ? <SkeletonRows count={6} /> : items.length === 0
        ? <div className="list"><Empty emoji="🧾" title="No money moved yet" text="Every expense and payment shows up here, with what it did to your balance." /></div>
        : (
          <ul className="list">
            {items.map((a) => {
              const who = a.kind === 'PAYMENT_RECORDED' ? a.fromMemberId : a.actorMemberId ?? a.memberId;
              const mine = a.myChangeCents !== null && a.myChangeCents !== 0;
              const emoji = a.kind.startsWith('EXPENSE') && a.kind !== 'EXPENSE_DELETED' ? categoryOf(a.category).emoji : ICONS[a.kind];
              const opensExpense = a.expenseId !== null && a.kind !== 'EXPENSE_DELETED';
              const body = (
                <>
                  <span style={{ position: 'relative' }}>
                    {who !== null ? <Avatar id={who} name={nameOf(who)} /> : <span className="tile" aria-hidden="true">{emoji}</span>}
                    {who !== null && emoji && <span aria-hidden="true" style={{ position: 'absolute', right: -6, bottom: -4, fontSize: 16 }}>{emoji}</span>}
                  </span>
                  <span className="grow">
                    <span className="title" style={{ whiteSpace: 'normal' }}>{describeActivity(a, nameOf, myMemberId)}</span>
                    <span className="sub">
                      {relativeTime(a.at)}
                      {a.amountCents !== null && (mine ? `, ${formatCents(a.amountCents)} in total` : ', you are not in it')}
                    </span>
                  </span>
                  {mine
                    // Being paid back lowers what you are owed, but settling up is not a loss: payments stay
                    // neutral, green and red are for expenses.
                    ? <Money cents={a.myChangeCents!} signed
                      className={a.kind.startsWith('PAYMENT') ? 'muted' : a.myChangeCents! > 0 ? 'owed' : 'owe'} />
                    : a.amountCents !== null && <Money cents={a.amountCents} className="muted" />}
                </>
              );
              return (
                <li key={a.id}>
                  {opensExpense
                    ? <button type="button" className="row clickable" onClick={() => openExpenseDetail(a.expenseId!)}>{body}</button>
                    : <div className="row">{body}</div>}
                </li>
              );
            })}
          </ul>
        )}
      {loaded && cursor && (
        <button type="button" className="btn btn-quiet btn-block" onClick={() => void loadMore()}>Show older</button>
      )}
    </>
  );
}
