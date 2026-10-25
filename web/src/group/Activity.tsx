import { useEffect, useState } from 'react';
import { describeActivity } from '../activity';
import type { ActivityItem } from '../api';
import { categoryOf } from '../categories';
import { relativeTime } from '../format';
import { Avatar, Empty, Money, SkeletonRows } from '../ui/parts';
import { useGroup } from './context';

const ICONS: Partial<Record<ActivityItem['kind'], string>> = {
  GROUP_CREATED: '🏁', PAYMENT_RECORDED: '💸', PAYMENT_UNDONE: '↩️', MEMBER_ADDED: '✉️', MEMBER_JOINED: '👋',
  PASSWORD_RESET: '🔑', EXPENSE_DELETED: '🗑️',
};

/** Everything that happened in the group, newest first; shown in a sheet from the home card. Opening it marks it as read. */
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

  const items = [...data.activity, ...older];
  return (
    <>
      {!loaded ? <SkeletonRows count={6} /> : items.length === 0
        ? <div className="list"><Empty emoji="🕊️" title="Nothing yet" text="Expenses, payments and new members show up here." /></div>
        : (
          <ul className="list">
            {items.map((a) => {
              const who = a.kind === 'PAYMENT_RECORDED' ? a.fromMemberId : a.kind === 'MEMBER_JOINED' ? a.memberId : a.actorMemberId ?? a.memberId;
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
                    <span className="sub">{relativeTime(a.at)}</span>
                  </span>
                  {a.amountCents !== null && <Money cents={a.amountCents} className={a.kind === 'EXPENSE_DELETED' || a.kind === 'PAYMENT_UNDONE' ? 'muted' : ''} />}
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
