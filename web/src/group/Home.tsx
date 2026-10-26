import type { ReactNode } from 'react';
import type { ExpenseHit } from '../api';
import { formatShortDate, joinNames } from '../format';
import { Icon } from '../ui/Icon';
import { useLongPress } from '../ui/longpress';
import { Avatar, CategoryTile, Empty, Guilloche, Money, SkeletonRows } from '../ui/parts';
import { useGroup } from './context';

/** {@code unread}: feed entries by others since you last opened it; the card opens the feed. */
export function Home({ banners, unread, onOpenFeed }: { banners?: ReactNode; unread: number; onOpenFeed: () => void }) {
  const { groupName, data, loaded, myMemberId, openExpense, openSettle, go } = useGroup();
  const myBalance = data.balances.find((b) => b.memberId === myMemberId)?.balanceCents ?? 0;
  const owesMe = data.debts.filter((d) => d.toMemberId === myMemberId);
  const iOwe = data.debts.filter((d) => d.fromMemberId === myMemberId);
  const alone = loaded && data.members.length < 2;

  // Money can flow both ways: Ben owes you while you owe Kyle. The big number is the net; the
  // bottom line then shows both sides, so the card never hides a debt.
  const incoming = owesMe.reduce((sum, d) => sum + d.amountCents, 0);
  const outgoing = iOwe.reduce((sum, d) => sum + d.amountCents, 0);
  const mixed = incoming > 0 && outgoing > 0;
  const fromNames = (list: typeof owesMe, side: 'from' | 'to') => {
    const names = list.map((d) => (side === 'from' ? d.fromName : d.toName));
    return names.length > 2 ? `${names.length} people` : joinNames(names);
  };
  const people = (myBalance > 0 || (myBalance === 0 && incoming > 0) ? owesMe : iOwe)
    .map((d) => (d.toMemberId === myMemberId ? { id: d.fromMemberId, name: d.fromName } : { id: d.toMemberId, name: d.toName }));
  const cardLine = incoming > 0 && outgoing === 0 ? `${fromNames(owesMe, 'from')} ${owesMe.length === 1 ? 'owes' : 'owe'} you`
    : outgoing > 0 && incoming === 0 ? `You owe ${fromNames(iOwe, 'to')}`
      : data.debts.length > 0 ? 'The others still have amounts open' : 'Nobody owes anybody';
  const label = mixed
    ? (myBalance > 0 ? 'Overall you get back' : myBalance < 0 ? 'Overall you owe' : 'Overall you are even')
    : myBalance > 0 ? 'You get back' : myBalance < 0 ? 'You owe' : 'Your balance';
  // The chip goes straight to the one person involved, or to the list when there are more.
  const onlyDebt = owesMe.length + iOwe.length === 1 ? (owesMe[0] ?? iOwe[0]) : undefined;
  const chipLabel = mixed ? 'Settle' : outgoing > 0 ? 'Pay' : 'Request';

  return (
    <>
      {/* Green when you get money back, red when you owe, purple when settled. */}
      <section className={`group-card clickable ${myBalance > 0 ? 'owed' : myBalance < 0 ? 'owe' : ''}`}
        role="button" tabIndex={0} aria-label="Your balance; show what happened in the group"
        onClick={onOpenFeed} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpenFeed(); } }}>
        <Guilloche />
        <div className="card-top">
          <span className="card-name">{groupName}</span>
          {/* The card's "chip" pays: straight to the GiroCode when only one person is involved. */}
          {loaded && (incoming > 0 || outgoing > 0) && (
            <button type="button" className="card-chip"
              onClick={(e) => { e.stopPropagation(); openSettle(onlyDebt); }}
              onKeyDown={(e) => e.stopPropagation()}>
              <Icon name="qr" small />{chipLabel}
            </button>
          )}
        </div>
        <div>
          <p className="card-label">{label}</p>
          {!loaded ? <span className="money big">&nbsp;</span>
            : myBalance === 0 && !mixed ? <p className="money big">All settled</p>
              : <Money cents={Math.abs(myBalance)} big />}
        </div>
        <div className="card-bottom">
          {mixed ? (
            <span className="card-flows">
              <span><Money cents={incoming} signed /> from {fromNames(owesMe, 'from')}</span>
              <span><Money cents={-outgoing} signed /> to {fromNames(iOwe, 'to')}</span>
            </span>
          ) : (
            <>
              {people.length > 0 && (
                <span className="avatar-stack">{people.slice(0, 4).map((p) => <Avatar key={p.id} id={p.id} name={p.name} size="small" />)}</span>
              )}
              <span>{loaded ? cardLine : ''}</span>
            </>
          )}
          {unread > 0
            ? <span className="card-new">{unread} new</span>
            : <span className="card-more" aria-hidden="true">›</span>}
        </div>
      </section>

      <nav className="quick" aria-label="Quick actions">
        <button type="button" onClick={() => openExpense({ kind: 'new' })}><span className="bubble"><Icon name="plus" /></span>Add expense</button>
        <button type="button" onClick={() => openSettle()}><span className="bubble"><Icon name="settle" /></span>Settle up</button>
        <button type="button" onClick={() => go('bank')}>
          <span className="bubble"><Icon name="upload" />{data.candidates.length > 0 && <span className="count">{data.candidates.length}</span>}</span>
          Import
        </button>
      </nav>

      {banners}

      {alone && (
        <div className="banner">
          <div className="grow"><strong>Invite your friends</strong><p className="small muted">Add them on the Group page and send each one their link.</p></div>
          <button type="button" className="btn btn-primary btn-small" onClick={() => go('group')}>Invite</button>
        </div>
      )}

      <div className="phone-only"><People /></div>

      <section className="section">
        <div className="section-head">
          <h2>Latest</h2>
          {data.latest.length > 0 && <button type="button" className="btn btn-link" onClick={() => go('activity')}>See all</button>}
        </div>
        {!loaded ? <SkeletonRows /> : data.latest.length === 0
          ? <div className="list"><Empty emoji="🧾" title="No expenses yet" text="Add the first one, or import your bank statement and share payments from it."
            action={<button type="button" className="btn btn-primary btn-small" onClick={() => openExpense({ kind: 'new' })}>Add expense</button>} /></div>
          : <ul className="list">{data.latest.map((h) => <ExpenseRow key={h.id} hit={h} />)}</ul>}
      </section>
    </>
  );
}

/**
 * Everyone else in the group and where you stand with them, as tiles rather than list rows: tinted
 * green when they owe you, red when you owe them. A tile with an open amount opens settle-up.
 */
export function People() {
  const { data, loaded, myMemberId, openSettle, go } = useGroup();
  const others = data.members.filter((m) => m.id !== myMemberId);
  if (!loaded) return <section className="section"><h2>People</h2><SkeletonRows count={2} /></section>;
  return (
    <section className="section">
      <div className="section-head"><h2>People</h2><button type="button" className="btn btn-link" onClick={() => go('group')}>Manage</button></div>
      {others.length === 0
        ? <div className="list"><Empty emoji="👋" title="Just you so far" text="Invite your flatmates or travel buddies from the Group page." /></div>
        : (
          <ul className="people">
            {others.map((m) => {
              const theyOwe = data.debts.find((d) => d.fromMemberId === m.id && d.toMemberId === myMemberId);
              const iOwe = data.debts.find((d) => d.fromMemberId === myMemberId && d.toMemberId === m.id);
              const debt = theyOwe ?? iOwe;
              const tone = theyOwe ? 'owed' : iOwe ? 'owe' : '';
              const content = (
                <>
                  <span className="person-top">
                    <Avatar id={m.id} name={m.displayName} size="medium" />
                    <span className="person-name">{m.displayName}</span>
                  </span>
                  {debt ? (
                    <span className="person-amount">
                      <span className="person-status">{theyOwe ? 'owes you' : 'you owe'}</span>
                      <Money cents={debt.amountCents} className={tone} />
                    </span>
                  ) : (
                    <span className="person-status">{m.joined ? 'Settled up ✓' : 'Invited, not joined yet'}</span>
                  )}
                </>
              );
              return (
                <li key={m.id}>
                  {debt
                    ? <button type="button" className={`person-tile ${tone}`} onClick={() => openSettle(debt)}>{content}</button>
                    : <div className={`person-tile ${m.joined ? '' : 'pending'}`}>{content}</div>}
                </li>
              );
            })}
          </ul>
        )}
    </section>
  );
}

/** One expense in a list: what, who paid, when, how much. Corrected ones are crossed out. */
export function ExpenseRow({ hit, showChange }: { hit: ExpenseHit; showChange?: boolean }) {
  const { nameOf, myMemberId, openExpenseDetail, openExpenseActions } = useGroup();
  // Press and hold (or right-click) for edit and delete; crossed-out versions cannot be changed.
  const press = useLongPress(() => { if (!hit.reversed) openExpenseActions(hit); });
  const payer = hit.payerId === myMemberId ? 'You paid' : `${nameOf(hit.payerId)} paid`;
  const change = !showChange ? null
    : hit.change === 'DELETED' ? `deleted by ${nameOf(hit.changedBy)}`
      : hit.change === 'EDITED' ? `old version, edited by ${nameOf(hit.changedBy)}`
        : hit.reversed ? 'deleted' : hit.replacesExpenseId !== null ? 'edited' : null;
  return (
    <li>
      <button type="button" className={`row clickable pressable ${hit.reversed ? 'struck' : ''}`} {...press}
        onClick={() => openExpenseDetail(hit.id)}>
        <CategoryTile category={hit.category} />
        <span className="grow">
          <span className="title">{hit.description}</span>
          <span className="sub">{payer}, {formatShortDate(hit.expenseDate)}{change ? `. ${change[0]!.toUpperCase()}${change.slice(1)}` : ''}</span>
        </span>
        <Money cents={hit.amountCents} />
      </button>
    </li>
  );
}
