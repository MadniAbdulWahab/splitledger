import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import {
  Api, register, type Balance, type BankTransaction, type ExpenseHit, type Me, type Member, type SuggestedPayment,
} from './api';
import { localToday } from './ids';
import { formatCents, parseEuroToCents } from './money';

const TOKEN_KEY = 'splitledger.token';

function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function App() {
  const [token, setToken] = useState<string | null>(readToken);
  // One client per token, so hooks that depend on it only re-run after signing in or out.
  const api = useMemo(() => (token ? new Api(token) : null), [token]);

  function signIn(newToken: string) {
    localStorage.setItem(TOKEN_KEY, newToken);
    setToken(newToken);
  }

  const signOut = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setToken(null);
  }, []);

  return api ? <Home api={api} onSignOut={signOut} /> : <Welcome onToken={signIn} />;
}

/* ---------- Signed out ---------- */

function Welcome({ onToken }: { onToken: (token: string) => void }) {
  const [mode, setMode] = useState<'register' | 'signin'>('register');
  const [existing, setExisting] = useState('');
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [newToken, setNewToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      setNewToken(await register(name, code));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <div className="welcome">
      <div className="welcome-text">
        <Brand />
        <h1>Shared expenses, straight from your bank statement.</h1>
        <ul className="features">
          <li><strong>Import instead of typing.</strong> Upload your Sparkasse export; nothing is counted twice.</li>
          <li><strong>Settle with a scan.</strong> Every payment comes with a GiroCode for your banking app.</li>
          <li><strong>History nobody can rewrite.</strong> Corrections are visible, every entry is hash-chained.</li>
          <li><strong>Search everything.</strong> "Einkauf" finds "Einkäufe", typos included.</li>
        </ul>
      </div>

      <div className="card auth">
        {newToken ? (
          <>
            <h2>Save your token</h2>
            <p className="muted">It is your login on every device and is shown only this once. Put it in your password manager.</p>
            <code className="token">{newToken}</code>
            <button className="primary wide" onClick={() => onToken(newToken)}>I saved it, continue</button>
          </>
        ) : (
          <>
            <div className="segmented">
              <button className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')}>Create account</button>
              <button className={mode === 'signin' ? 'active' : ''} onClick={() => setMode('signin')}>Sign in</button>
            </div>
            {mode === 'register' ? (
              <form onSubmit={create} className="stack">
                <Field label="Your name">
                  <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} autoComplete="name" />
                </Field>
                <Field label="Registration code" hint="Ask the friend who runs SplitLedger.">
                  <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" />
                </Field>
                <button className="primary wide">Create account</button>
              </form>
            ) : (
              <form onSubmit={(e) => { e.preventDefault(); onToken(existing.trim()); }} className="stack">
                <Field label="Your token">
                  <input placeholder="slt_…" value={existing} onChange={(e) => setExisting(e.target.value)} required autoComplete="current-password" />
                </Field>
                <button className="primary wide">Sign in</button>
              </form>
            )}
            {error && <Alert kind="error" text={error} onClose={() => setError(null)} />}
          </>
        )}
      </div>
    </div>
  );
}

/* ---------- Signed in ---------- */

function Home({ api, onSignOut }: { api: Api; onSignOut: () => void }) {
  const [me, setMe] = useState<Me | null>(null);
  const [groupId, setGroupId] = useState<number | null>(null);
  const [showGroups, setShowGroups] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadMe = useCallback(async () => {
    try {
      const loaded = await api.me();
      setMe(loaded);
      setGroupId((current) => current ?? loaded.groups[0]?.groupId ?? null);
    } catch (err) {
      if ((err as { status?: number }).status === 401) {
        onSignOut();
      } else {
        setError((err as Error).message);
      }
    }
  }, [api, onSignOut]);

  useEffect(() => { void loadMe(); }, [loadMe]);

  async function switchTo(action: () => Promise<number>) {
    setError(null);
    try {
      const id = await action();
      await loadMe();
      setGroupId(id);
      setShowGroups(false);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (!me) {
    return <div className="loading">{error ?? 'Loading…'}</div>;
  }
  const membership = me.groups.find((g) => g.groupId === groupId);

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Brand />
          <div className="topbar-actions">
            {me.groups.length > 0 && (
              <select aria-label="Group" value={groupId ?? ''} onChange={(e) => setGroupId(Number(e.target.value))}>
                {me.groups.map((g) => <option key={g.groupId} value={g.groupId}>{g.groupName}</option>)}
              </select>
            )}
            <button className="ghost" onClick={() => setShowGroups((v) => !v)}>{showGroups ? 'Close' : '+ Group'}</button>
            <span className="avatar small" title={me.displayName}>{initials(me.displayName)}</span>
            <button className="ghost" onClick={onSignOut}>Sign out</button>
          </div>
        </div>
      </header>

      <main className="page">
        {error && <Alert kind="error" text={error} onClose={() => setError(null)} />}
        {(showGroups || me.groups.length === 0) && (
          <div className="card">
            <h2>{me.groups.length === 0 ? `Welcome, ${me.displayName}` : 'Groups'}</h2>
            <p className="muted">Start a group for your flat or trip, or join one with the invite code a friend sent you.</p>
            <div className="split">
              <InlineForm label="New group" placeholder="e.g. WG Kessenich" button="Create"
                onSubmit={(name) => switchTo(async () => (await api.createGroup(name)).id)} />
              <InlineForm label="Invite code" placeholder="inv_…" button="Join"
                onSubmit={(code) => switchTo(async () => (await api.acceptInvitation(code)).groupId)} />
            </div>
          </div>
        )}
        {groupId && membership && (
          <Group key={groupId} api={api} groupId={groupId} groupName={membership.groupName} myMemberId={membership.memberId} />
        )}
      </main>
    </>
  );
}

type Tab = 'overview' | 'add' | 'bank' | 'history' | 'members';
const TABS: { id: Tab; label: string }[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'add', label: 'Add' },
  { id: 'bank', label: 'Bank' },
  { id: 'history', label: 'History' },
  { id: 'members', label: 'Members' },
];

/** Simple line icons (24x24, drawn with currentColor), so no icon library is needed. */
const TAB_ICONS: Record<Tab, ReactNode> = {
  overview: <><path d="M3 10.5 12 3l9 7.5" /><path d="M5 9v11h5v-6h4v6h5V9" /></>,
  add: <><circle cx="12" cy="12" r="9" /><path d="M12 8v8M8 12h8" /></>,
  bank: <><path d="M3 9.5 12 4l9 5.5" /><path d="M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 20.5h18" /></>,
  history: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3.5 2" /></>,
  members: <><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20a6.5 6.5 0 0 1 13 0" /><circle cx="17.5" cy="9" r="2.5" /><path d="M16 14.3a5 5 0 0 1 5.5 5.2" /></>,
};

function TabIcon({ tab }: { tab: Tab }) {
  return (
    <svg className="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {TAB_ICONS[tab]}
    </svg>
  );
}

function Group({ api, groupId, groupName, myMemberId }: { api: Api; groupId: number; groupName: string; myMemberId: number }) {
  const [tab, setTab] = useState<Tab>('overview');
  const [members, setMembers] = useState<Member[]>([]);
  const [balances, setBalances] = useState<Balance[]>([]);
  const [payments, setPayments] = useState<SuggestedPayment[]>([]);
  const [candidates, setCandidates] = useState<BankTransaction[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  const reload = useCallback(async () => {
    const [m, b, s, c] = await Promise.all([
      api.members(groupId), api.balances(groupId), api.settlement(groupId), api.candidates(groupId),
    ]);
    setMembers(m);
    setBalances(b.balances);
    setPayments(s.payments);
    setCandidates(c);
  }, [api, groupId]);

  useEffect(() => { reload().catch((err) => setError(err.message)); }, [reload]);

  async function run(action: () => Promise<unknown>, success?: string): Promise<boolean> {
    setError(null);
    setNotice(null);
    try {
      await action();
      await reload();
      if (success) setNotice({ text: success });
      return true;
    } catch (err) {
      setError((err as Error).message);
      return false;
    }
  }

  const myBalance = balances.find((b) => b.memberId === myMemberId)?.balanceCents ?? 0;

  return (
    <>
      <section className="summary">
        <div>
          <p className="eyebrow">{groupName} · {members.length} {members.length === 1 ? 'member' : 'members'}</p>
          <p className={`headline ${myBalance > 0 ? 'positive' : myBalance < 0 ? 'negative' : ''}`}>
            {myBalance > 0 ? `You get back ${formatCents(myBalance)}`
              : myBalance < 0 ? `You owe ${formatCents(-myBalance)}`
                : 'You are all settled'}
          </p>
        </div>
        <button className="primary" onClick={() => setTab('add')}>+ Add expense</button>
      </section>

      <nav className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} className={tab === t.id ? 'active' : ''} onClick={() => setTab(t.id)}>
            <span className="tab-icon">
              <TabIcon tab={t.id} />
              {t.id === 'bank' && candidates.length > 0 && <span className="badge">{candidates.length}</span>}
            </span>
            <span>{t.label}</span>
          </button>
        ))}
      </nav>

      {error && <Alert kind="error" text={error} onClose={() => setError(null)} />}
      {notice && <Alert kind="success" text={notice.text} sticky={notice.sticky} onClose={() => setNotice(null)} />}

      {tab === 'overview' && (
        <div className="columns">
          <div className="card">
            <h2>Balances</h2>
            <BalanceList balances={balances} myMemberId={myMemberId} />
          </div>
          <div className="card">
            <h2>Settle up</h2>
            {payments.length === 0
              ? <Empty title="Nothing to settle" text="Everyone has paid their share." />
              : payments.map((p) => (
                <PaymentCard key={`${p.fromMemberId}-${p.toMemberId}`} api={api} groupId={groupId} payment={p}
                  onPaid={() => run(() => api.recordPayment(groupId, p), `Recorded: ${p.fromName} paid ${p.toName} ${formatCents(p.amountCents)}`)} />
              ))}
          </div>
        </div>
      )}

      {tab === 'add' && (
        <ExpenseForm members={members} myMemberId={myMemberId}
          onSubmit={(body) => run(() => api.addExpense(groupId, body), 'Expense added')}
          onDone={() => setTab('overview')} />
      )}

      {tab === 'bank' && (
        <div className="card">
          <h2>Bank import</h2>
          <p className="muted">Upload the CSV export of your Sparkasse account. Your transactions stay private until you share one;
            importing the same file again adds nothing.</p>
          <label className="dropzone">
            <input type="file" accept=".csv,text/csv" onChange={(e) => {
              const file = e.target.files?.[0];
              e.target.value = '';
              if (file) {
                void run(async () => {
                  const r = await api.importStatement(groupId, file);
                  setNotice({ text: `${r.newTransactions} new · ${r.duplicates} already imported · ${r.skippedCount} skipped (incoming or pending)` });
                });
              }
            }} />
            <span><strong>Choose a CSV file</strong> or drop it here</span>
          </label>
          {candidates.length === 0
            ? <Empty title="No open transactions" text="Imported payments appear here so you can share them with the group." />
            : (
              <ul className="rows">
                {candidates.map((t) => (
                  <li key={t.id}>
                    <div className="grow">
                      <strong>{t.counterpartyName ?? 'Unknown'}</strong>
                      <span className="muted small">{formatDate(t.bookingDate)}{t.purpose ? ` · ${t.purpose}` : ''}</span>
                    </div>
                    <span className="amount">{formatCents(-t.amountCents)}</span>
                    <div className="actions">
                      <button className="primary" onClick={() => run(() => api.confirm(groupId, t.id, members.map((m) => m.id)), 'Shared equally with everyone')}>Share</button>
                      <button className="ghost" onClick={() => run(() => api.ignore(groupId, t.id))}>Ignore</button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
        </div>
      )}

      {tab === 'history' && <History api={api} groupId={groupId} members={members} />}

      {tab === 'members' && (
        <div className="columns">
          <div className="card">
            <h2>Members</h2>
            <ul className="rows">
              {members.map((m) => (
                <li key={m.id}>
                  <span className="avatar">{initials(m.displayName)}</span>
                  <div className="grow">
                    <strong>{m.displayName}{m.id === myMemberId ? ' (you)' : ''}</strong>
                    <span className="muted small">{m.iban ? formatIban(m.iban) : 'No IBAN yet'}</span>
                  </div>
                  {!m.joined && <span className="pill">invited</span>}
                </li>
              ))}
            </ul>
          </div>
          <div className="card">
            <h2>Invite a friend</h2>
            <p className="muted">They get a one-time code to link their account.</p>
            <InlineForm label="Name" placeholder="e.g. Ben" button="Invite"
              onSubmit={(name) => run(async () => {
                const added = await api.addMember(groupId, name);
                // Stays until closed: the code has to be copied and sent to the friend.
                setNotice({ text: `Send ${name} this invite code: ${added.inviteCode}`, sticky: true });
              })} />
            <h2 className="spaced">Your IBAN</h2>
            <p className="muted">Shown to others when they pay you, and used for the GiroCode.</p>
            <InlineForm label="IBAN" placeholder={members.find((m) => m.id === myMemberId)?.iban ?? 'DE…'} button="Save"
              onSubmit={(iban) => run(() => api.setMyIban(groupId, iban), 'IBAN saved')} />
          </div>
        </div>
      )}
    </>
  );
}

function BalanceList({ balances, myMemberId }: { balances: Balance[]; myMemberId: number }) {
  const largest = Math.max(1, ...balances.map((b) => Math.abs(b.balanceCents)));
  return (
    <ul className="rows">
      {balances.map((b) => (
        <li key={b.memberId}>
          <span className="avatar">{initials(b.displayName)}</span>
          <div className="grow">
            <strong>{b.displayName}{b.memberId === myMemberId ? ' (you)' : ''}</strong>
            <div className="bar">
              <span className={b.balanceCents < 0 ? 'negative' : 'positive'}
                style={{ width: `${(Math.abs(b.balanceCents) / largest) * 100}%` }} />
            </div>
          </div>
          <span className={`amount ${b.balanceCents < 0 ? 'negative' : b.balanceCents > 0 ? 'positive' : ''}`}>
            {b.balanceCents > 0 ? '+' : ''}{formatCents(b.balanceCents)}
          </span>
        </li>
      ))}
    </ul>
  );
}

function PaymentCard({ api, groupId, payment, onPaid }:
  { api: Api; groupId: number; payment: SuggestedPayment; onPaid: () => void }) {
  const [qr, setQr] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => () => { if (qr) URL.revokeObjectURL(qr); }, [qr]);

  return (
    <div className="payment">
      <div className="payment-line">
        <span className="avatar">{initials(payment.fromName)}</span>
        <span className="arrow" aria-hidden="true">→</span>
        <span className="avatar">{initials(payment.toName)}</span>
        <div className="grow">
          <strong>{payment.fromName} pays {payment.toName}</strong>
          <span className="muted small">{payment.toIban ? formatIban(payment.toIban) : `${payment.toName} has not added an IBAN yet`}</span>
        </div>
        <span className="amount">{formatCents(payment.amountCents)}</span>
      </div>
      <div className="actions">
        {payment.toIban && (
          <button className="ghost" onClick={() => (qr ? setQr(null)
            : api.giroCode(groupId, payment).then(setQr, (e) => setError(e.message)))}>
            {qr ? 'Hide GiroCode' : 'Show GiroCode'}
          </button>
        )}
        <button className="primary" onClick={onPaid}>Mark as paid</button>
      </div>
      {qr && (
        <div className="qr-box">
          <img src={qr} alt={`GiroCode: ${formatCents(payment.amountCents)} to ${payment.toName}`} />
          <p className="muted small">Scan with your banking app (e.g. Sparkasse "Scan2Bank"), check name, IBAN and amount,
            send the transfer, then mark it as paid.</p>
        </div>
      )}
      {error && <Alert kind="error" text={error} onClose={() => setError(null)} />}
    </div>
  );
}

function ExpenseForm({ members, myMemberId, onSubmit, onDone }: {
  members: Member[]; myMemberId: number; onSubmit: (body: unknown) => Promise<boolean>; onDone: () => void;
}) {
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState(localToday);
  const [payerId, setPayerId] = useState(myMemberId);
  const [excluded, setExcluded] = useState<number[]>([]);
  const participants = members.map((m) => m.id).filter((id) => !excluded.includes(id));
  const cents = parseEuroToCents(amount);
  const share = cents !== null && participants.length > 0 ? Math.floor(cents / participants.length) : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (cents === null || participants.length === 0) return;
    const ok = await onSubmit({
      payerId, amountCents: cents, description, expenseDate: date, splitMode: 'EQUAL',
      participants: participants.map((memberId) => ({ memberId })),
    });
    if (ok) {
      setDescription('');
      setAmount('');
      onDone();
    }
  }

  return (
    <form className="card stack" onSubmit={submit}>
      <h2>Add an expense</h2>
      <div className="form-grid">
        <Field label="What for?">
          <input value={description} onChange={(e) => setDescription(e.target.value)} required maxLength={200} placeholder="e.g. Groceries" />
        </Field>
        <Field label="Amount (€)" hint={amount !== '' && cents === null ? 'Use a format like 12,50' : undefined}>
          <input value={amount} onChange={(e) => setAmount(e.target.value)} required inputMode="decimal" placeholder="0,00"
            aria-invalid={amount !== '' && cents === null} />
        </Field>
        <Field label="Date">
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
        </Field>
        <Field label="Paid by">
          <select value={payerId} onChange={(e) => setPayerId(Number(e.target.value))}>
            {members.map((m) => <option key={m.id} value={m.id}>{m.displayName}{m.id === myMemberId ? ' (you)' : ''}</option>)}
          </select>
        </Field>
      </div>
      <div>
        <span className="label">Split equally between</span>
        <div className="chips">
          {members.map((m) => {
            const included = !excluded.includes(m.id);
            return (
              <button type="button" key={m.id} className={`chip ${included ? 'on' : ''}`} aria-pressed={included}
                onClick={() => setExcluded(included ? [...excluded, m.id] : excluded.filter((id) => id !== m.id))}>
                {m.displayName}
              </button>
            );
          })}
        </div>
        {share !== null && <p className="muted small">About {formatCents(share)} each; leftover cents go to the first members.</p>}
      </div>
      <button className="primary wide" disabled={cents === null || participants.length === 0}>
        Add {cents !== null ? formatCents(cents) : 'expense'}
      </button>
    </form>
  );
}

/** The whole history, newest first, 20 at a time; the search box narrows it down. */
function History({ api, groupId, members }: { api: Api; groupId: number; members: Member[] }) {
  const [query, setQuery] = useState('');
  const [activeQuery, setActiveQuery] = useState('');
  const [items, setItems] = useState<ExpenseHit[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const names = new Map(members.map((m) => [m.id, m.displayName]));

  const load = useCallback(async (q: string, after?: string) => {
    setError(null);
    try {
      const page = await api.search(groupId, q, after);
      setItems((current) => (after && current ? [...current, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setActiveQuery(q);
    } catch (err) {
      setError((err as Error).message);
    }
  }, [api, groupId]);

  useEffect(() => { void load(''); }, [load]);

  return (
    <div className="card">
      <h2>History</h2>
      <form className="inline" onSubmit={(e) => { e.preventDefault(); void load(query.trim()); }}>
        <input aria-label="Search" placeholder="Search: Einkauf, Pizza, Lidl, Berlin…" value={query}
          onChange={(e) => setQuery(e.target.value)} />
        <button className="primary">Search</button>
        {activeQuery && (
          <button type="button" className="ghost" onClick={() => { setQuery(''); void load(''); }}>Clear</button>
        )}
      </form>
      {activeQuery && items && <p className="muted small spaced-small">Results for “{activeQuery}”</p>}
      {error && <Alert kind="error" text={error} onClose={() => setError(null)} />}
      {items === null && <p className="muted spaced-small">Loading…</p>}
      {items !== null && items.length === 0 && (
        activeQuery
          ? <Empty title="Nothing found" text="Try another word; small typos are fine." />
          : <Empty title="No expenses yet" text="Add one or import your bank statement." />
      )}
      {items && items.length > 0 && (
        <ul className="rows">
          {items.map((h) => (
            <li key={h.id} className={h.reversed ? 'reversed' : ''}>
              <div className="grow">
                <strong>{h.description}</strong>
                <span className="muted small">
                  {formatDate(h.expenseDate)} · paid by {names.get(h.payerId) ?? '?'}{h.reversed ? ' · reversed' : ''}
                </span>
              </div>
              <span className="amount">{formatCents(h.amountCents)}</span>
            </li>
          ))}
        </ul>
      )}
      {cursor && (
        <button className="ghost wide spaced-small" onClick={() => void load(activeQuery, cursor)}>Load more</button>
      )}
    </div>
  );
}

/* ---------- Small building blocks ---------- */

function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark" aria-hidden="true">S</span>
      SplitLedger
    </span>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="label">{label}</span>
      {children}
      {hint && <span className="muted small">{hint}</span>}
    </label>
  );
}

function InlineForm({ label, placeholder, button, onSubmit }:
  { label: string; placeholder: string; button: string; onSubmit: (value: string) => Promise<unknown> }) {
  const [value, setValue] = useState('');
  return (
    <form className="inline" onSubmit={(e) => {
      e.preventDefault();
      void onSubmit(value.trim()).then(() => setValue(''));
    }}>
      <input aria-label={label} placeholder={placeholder} value={value} onChange={(e) => setValue(e.target.value)} required />
      <button className="primary">{button}</button>
    </form>
  );
}

interface Notice { text: string; sticky?: boolean }

/** Success messages disappear after five seconds unless sticky; errors stay until they are closed. */
function Alert({ kind, text, sticky, onClose }:
  { kind: 'success' | 'error'; text: string; sticky?: boolean; onClose: () => void }) {
  useEffect(() => {
    if (kind !== 'success' || sticky) {
      return undefined;
    }
    const timer = setTimeout(onClose, 5000);
    return () => clearTimeout(timer);
  }, [kind, sticky, text, onClose]);

  return (
    <div className={`alert ${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <span>{text}</span>
      <button type="button" className="close" aria-label="Dismiss" onClick={onClose}>×</button>
    </div>
  );
}

function Empty({ title, text }: { title: string; text: string }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      <span className="muted">{text}</span>
    </div>
  );
}

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]!.toUpperCase()).join('');
}

function formatIban(iban: string): string {
  return iban.replace(/(.{4})/g, '$1 ').trim();
}

function formatDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-');
  return `${day}.${month}.${year}`;
}
