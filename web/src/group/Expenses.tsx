import { useCallback, useEffect, useState } from 'react';
import type { Expense, ExpenseHit } from '../api';
import { CATEGORIES, categoryOf, type Category } from '../categories';
import { formatDate, formatDateTime, formatMonth } from '../format';
import { formatCents } from '../money';
import { Icon } from '../ui/Icon';
import { Avatar, Empty, Money, Sheet, SkeletonRows } from '../ui/parts';
import { useGroup } from './context';
import { ExpenseRow } from './Home';

/**
 * The whole history, newest first, 20 at a time, searchable ("Einkauf" finds "Einkäufe", typos
 * are fine) and filterable by category. Edits and deletions never remove anything: the old
 * version stays, crossed out, with who changed it.
 */
export function Expenses() {
  const { api, groupId, version } = useGroup();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState('');
  const [category, setCategory] = useState<Category | null>(null);
  const [items, setItems] = useState<ExpenseHit[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<'expenses' | 'payments'>('expenses');

  const load = useCallback(async (q: string, c: Category | null, after?: string) => {
    setError(null);
    try {
      const page = await api.search(groupId, q, { cursor: after, category: c });
      setItems((current) => (after && current ? [...current, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setActive(q);
    } catch (err) {
      setError((err as Error).message);
    }
  }, [api, groupId]);

  // Reload after every change in the group (an edit or delete in the detail sheet), keeping the
  // current search and filter; typing alone does not reload.
  useEffect(() => { void load(active, category); }, [load, version]);

  const pick = (c: Category | null) => {
    setCategory(c);
    void load(active, c);
  };

  // Without a search the list is grouped by month of the expense date (the server returns newest
  // entries first, which can be an old receipt); search results keep their relevance order.
  const ordered = active ? items ?? [] : [...(items ?? [])].sort((a, b) =>
    b.expenseDate.localeCompare(a.expenseDate) || b.id - a.id);
  const months: { month: string; hits: ExpenseHit[] }[] = [];
  for (const hit of ordered) {
    const month = active ? '' : hit.expenseDate.slice(0, 7);
    const last = months[months.length - 1];
    if (last?.month === month) last.hits.push(hit);
    else months.push({ month, hits: [hit] });
  }

  if (view === 'payments') {
    return <><ExpensesHead view={view} onView={setView} /><Payments /></>;
  }
  return (
    <>
      <ExpensesHead view={view} onView={setView} />
      <form role="search" onSubmit={(e) => { e.preventDefault(); void load(query.trim(), category); }}
        style={{ display: 'flex', gap: 8 }}>
        <input aria-label="Search expenses" type="search" placeholder="Search: Einkauf, Pizza, Lidl, Berlin…" value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            if (!e.target.value && active) void load('', category);
          }} />
        <button className="btn btn-primary" aria-label="Search"><Icon name="search" /></button>
      </form>
      <div className="chip-row" role="group" aria-label="Filter by category">
        <button type="button" className="chip" aria-pressed={category === null} onClick={() => pick(null)}>All</button>
        {CATEGORIES.map((c) => (
          <button type="button" key={c.id} className="chip" aria-pressed={category === c.id} onClick={() => pick(c.id)}>
            <span aria-hidden="true">{c.emoji}</span>{c.label}
          </button>
        ))}
      </div>
      {error && <p className="error-text" role="alert">{error}</p>}
      {items === null ? <SkeletonRows count={5} /> : items.length === 0 ? (
        <div className="list">
          {active || category
            ? <Empty emoji="🔍" title="Nothing found" text="Try another word or category; small typos are fine." />
            : <Empty emoji="🧾" title="No expenses yet" text="Add one, or import your bank statement." />}
        </div>
      ) : (
        <div>
          {active && <p className="muted small" style={{ padding: '0 4px 8px' }}>Best matches for “{active}”</p>}
          {months.map((group) => (
            <section key={group.month + group.hits[0]!.id}>
              {!active && <h2 className="month-head"><span>{formatMonth(group.month)}</span></h2>}
              <ul className="list">{group.hits.map((h) => <ExpenseRow key={h.id} hit={h} showChange />)}</ul>
            </section>
          ))}
          {cursor && (
            <div className="list-foot"><button type="button" className="btn btn-quiet btn-block" onClick={() => void load(active, category, cursor)}>Load more</button></div>
          )}
        </div>
      )}
    </>
  );
}

function ExpensesHead({ view, onView }: { view: 'expenses' | 'payments'; onView: (v: 'expenses' | 'payments') => void }) {
  const { data } = useGroup();
  return (
    <div className="section-head" style={{ alignItems: 'center', padding: 0 }}>
      <h1>Activity</h1>
      <div className="segmented" role="group" aria-label="Show" style={{ width: 220 }}>
        <button type="button" aria-pressed={view === 'expenses'} onClick={() => onView('expenses')}>Expenses</button>
        <button type="button" aria-pressed={view === 'payments'} onClick={() => onView('payments')}>
          Payments{data.payments.length > 0 ? ` (${data.payments.length})` : ''}
        </button>
      </div>
    </div>
  );
}

/** Recorded settle-up payments; undoing one brings the debt back and keeps the row, marked. */
function Payments() {
  const { api, groupId, data, myMemberId, nameOf, run } = useGroup();
  const name = (id: number, start: boolean) => (id === myMemberId ? (start ? 'You' : 'you') : nameOf(id));
  if (data.payments.length === 0) {
    return <div className="list"><Empty emoji="💸" title="No payments yet" text="When someone settles up and marks it as paid, it shows up here." /></div>;
  }
  return (
    <ul className="list">
      {data.payments.map((p) => (
        <li key={p.id} className={`row ${p.reversed ? 'struck' : ''}`}>
          <Avatar id={p.fromMemberId} name={nameOf(p.fromMemberId)} />
          <span className="grow">
            <span className="title">{name(p.fromMemberId, true)} paid {name(p.toMemberId, false)}</span>
            <span className="sub">{formatDateTime(p.createdAt)}{p.reversed ? `, undone by ${nameOf(p.undoneBy)}` : ''}</span>
          </span>
          <Money cents={p.amountCents} />
          {!p.reversed && (
            <button type="button" className="btn btn-quiet btn-small" onClick={() => {
              if (window.confirm(`Undo “${nameOf(p.fromMemberId)} paid ${nameOf(p.toMemberId)} ${formatCents(p.amountCents)}”? The debt comes back.`)) {
                void run(() => api.undoPayment(groupId, p.id), 'Payment undone');
              }
            }}>Undo</button>
          )}
        </li>
      ))}
    </ul>
  );
}

/** One expense with everyone's share, and edit or delete. */
export function ExpenseDetail({ expenseId, onClose }: { expenseId: number; onClose: () => void }) {
  const { api, groupId, myMemberId, nameOf, run, openExpense, version } = useGroup();
  const [expense, setExpense] = useState<Expense | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getExpense(groupId, expenseId).then(setExpense, (e: Error) => setError(e.message));
  }, [api, groupId, expenseId, version]);

  const info = categoryOf(expense?.category);
  return (
    <Sheet title={expense?.description ?? 'Expense'} onClose={onClose}>
      {error && <p className="error-text">{error}</p>}
      {!expense ? <SkeletonRows count={2} /> : (
        <div className="stack">
          <div style={{ display: 'grid', justifyItems: 'center', gap: 6, textAlign: 'center' }}>
            <span className="tile" style={{ width: 56, height: 56, fontSize: 28, borderRadius: 18 }} aria-hidden="true">{info.emoji}</span>
            <Money cents={expense.amountCents} big className={expense.reversed ? 'muted' : ''} />
            <p className="muted small">{info.label}, {formatDate(expense.expenseDate)}</p>
            {expense.reversed && <span className="pill">Deleted or replaced by an edit</span>}
          </div>
          <ul className="list">
            <li className="row">
              <Avatar id={expense.payerId} name={nameOf(expense.payerId)} />
              <span className="grow"><span className="title">{expense.payerId === myMemberId ? 'You' : nameOf(expense.payerId)} paid</span></span>
              <Money cents={expense.amountCents} />
            </li>
            {expense.shares.map((s) => (
              <li key={s.memberId} className="row">
                <Avatar id={s.memberId} name={nameOf(s.memberId)} size="small" />
                <span className="grow">
                  <span className="title">{s.memberId === myMemberId ? 'Your share' : `${nameOf(s.memberId)}’s share`}</span>
                  {s.shareUnits !== null && <span className="sub">{s.shareUnits} {s.shareUnits === 1 ? 'share' : 'shares'}</span>}
                </span>
                <Money cents={s.amountCents} />
              </li>
            ))}
          </ul>
          {expense.notes && <p className="muted small">{expense.notes}</p>}
          {!expense.reversed && (
            <div className="form-row">
              <button type="button" className="btn btn-quiet" onClick={() => { onClose(); openExpense({ kind: 'edit', expense }); }}>Edit</button>
              <button type="button" className="btn btn-danger" onClick={() => {
                if (window.confirm(`Delete “${expense.description}”? It stays in the history, crossed out.`)) {
                  void run(() => api.deleteExpense(groupId, expense.id), `Deleted “${expense.description}”`).then((ok) => { if (ok) onClose(); });
                }
              }}>Delete</button>
            </div>
          )}
        </div>
      )}
    </Sheet>
  );
}
