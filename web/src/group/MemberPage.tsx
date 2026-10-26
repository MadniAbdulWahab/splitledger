import { useCallback, useEffect, useState } from 'react';
import type { ExpenseHit } from '../api';
import { formatDateTime, formatIban } from '../format';
import { Icon } from '../ui/Icon';
import { Avatar, Empty, Money, SkeletonRows } from '../ui/parts';
import { useGroup } from './context';
import { useMemberLinks } from './GroupPage';
import { ExpenseRow } from './Home';

/**
 * One person in the group: who they are and where you two stand (a compact card), then their
 * history, switchable between expenses (every expense they paid or share) and the payments
 * between you, so neither list is hidden below the other. Opened from People and the Group page.
 */
export function MemberPage({ memberId }: { memberId: number }) {
  const { api, groupId, data, loaded, myMemberId, version, nameOf, openSettle, go } = useGroup();
  const links = useMemberLinks();
  const [view, setView] = useState<'expenses' | 'payments'>('expenses');
  const [items, setItems] = useState<ExpenseHit[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const member = data.members.find((m) => m.id === memberId);

  const load = useCallback(async (after?: string) => {
    const page = await api.search(groupId, '', { member: memberId, cursor: after });
    setItems((current) => (after && current ? [...current, ...page.items] : page.items));
    setCursor(page.nextCursor);
  }, [api, groupId, memberId]);

  useEffect(() => { void load(); }, [load, version]);

  const theyOwe = data.debts.find((d) => d.fromMemberId === memberId && d.toMemberId === myMemberId);
  const iOwe = data.debts.find((d) => d.fromMemberId === myMemberId && d.toMemberId === memberId);
  const debt = theyOwe ?? iOwe;
  const payments = data.payments.filter((p) =>
    (p.fromMemberId === memberId && p.toMemberId === myMemberId) || (p.fromMemberId === myMemberId && p.toMemberId === memberId));
  const isMe = memberId === myMemberId;
  const back = () => (window.history.length > 1 ? window.history.back() : go('home'));

  if (loaded && !member) {
    return <div className="list"><Empty emoji="🤷" title="Not in this group" text="This person is not a member of the group." /></div>;
  }

  return (
    <>
      <button type="button" className="btn btn-link back-link" onClick={back}>‹ Back</button>

      <section className={`member-card ${theyOwe ? 'owed' : iOwe ? 'owe' : ''}`} aria-label={member?.displayName}>
        <div className="member-top">
          {member && <Avatar id={member.id} name={member.displayName} />}
          <div className="member-who">
            <h1>{member?.displayName ?? ' '}{isMe ? ' (you)' : ''}</h1>
            <p className="muted small">
              {!member ? ' ' : !member.joined ? 'Invited, not joined yet' : member.iban ? formatIban(member.iban) : 'No IBAN yet'}
            </p>
          </div>
        </div>
        {!isMe && loaded && (
          <div className="member-balance">
            {debt ? (
              <>
                <span className="person-status">{theyOwe ? `${member?.displayName} owes you` : `You owe ${member?.displayName}`}</span>
                <Money cents={debt.amountCents} className={theyOwe ? 'owed' : 'owe'} />
              </>
            ) : <span className="person-status">{member?.joined ? 'You are settled up ✓' : 'Nothing between you yet'}</span>}
          </div>
        )}
        {!isMe && member && (
          <div className="member-actions">
            {debt && (
              <button type="button" className="btn btn-primary btn-small" onClick={() => openSettle(debt)}>
                <Icon name="qr" small />Settle up
              </button>
            )}
            {!member.joined && (
              <button type="button" className="btn btn-quiet btn-small" onClick={() => void links.newLink(member.id, member.displayName, 'join')}>
                <Icon name="share" small />Send invite link
              </button>
            )}
            {member.joined && (
              <button type="button" className="btn btn-quiet btn-small" onClick={() => {
                if (window.confirm(`Make a one-time password reset link for ${member.displayName}? Everyone in the group sees that you made it.`)) {
                  void links.newLink(member.id, member.displayName, 'reset');
                }
              }}>
                <Icon name="key" small />Password reset link
              </button>
            )}
          </div>
        )}
      </section>

      <div className="segmented" role="group" aria-label="Show">
        <button type="button" aria-pressed={view === 'expenses'} onClick={() => setView('expenses')}>Expenses</button>
        <button type="button" aria-pressed={view === 'payments'} onClick={() => setView('payments')}>
          Payments{payments.length > 0 ? ` (${payments.length})` : ''}
        </button>
      </div>

      {view === 'expenses' ? (
        <section className="section" aria-label="Expenses">
          {items === null ? <SkeletonRows count={3} /> : items.length === 0
            ? <div className="list"><Empty emoji="🧾" title="No expenses yet" text="Expenses they pay or share show up here." /></div>
            : <ul className="list">{items.map((h) => <ExpenseRow key={h.id} hit={h} showChange />)}</ul>}
          {cursor && <button type="button" className="btn btn-quiet btn-block" onClick={() => void load(cursor)}>Load more</button>}
        </section>
      ) : (
        <section className="section" aria-label="Payments">
          {payments.length === 0
            ? <div className="list"><Empty emoji="💸" title="No payments yet" text={isMe ? 'Payments you made or received show up on each person’s page.' : 'When one of you pays the other back, it shows up here.'} /></div>
            : (
              <ul className="list">
                {payments.map((p) => (
                  <li key={p.id} className={`row ${p.reversed ? 'struck' : ''}`}>
                    <span className="tile" aria-hidden="true">💸</span>
                    <span className="grow">
                      <span className="title">{p.fromMemberId === myMemberId ? 'You' : nameOf(p.fromMemberId)} paid {p.toMemberId === myMemberId ? 'you' : nameOf(p.toMemberId)}</span>
                      <span className="sub">{formatDateTime(p.createdAt)}{p.reversed ? ', undone' : ''}</span>
                    </span>
                    <Money cents={p.amountCents} />
                  </li>
                ))}
              </ul>
            )}
        </section>
      )}

      {links.sheet}
    </>
  );
}
