import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import {
  Api, register, type Balance, type BankTransaction, type ExpenseHit, type Me, type Member, type PaymentRecord,
  type SuggestedPayment,
} from './api';
import { localToday } from './ids';
import { formatCents, parseEuroToCents } from './money';
import { distributeRest, splitEqually, splitProportionally, type Allocation } from './split';

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
  const [debts, setDebts] = useState<SuggestedPayment[]>([]);
  const [payments, setPayments] = useState<SuggestedPayment[]>([]);
  const [candidates, setCandidates] = useState<BankTransaction[]>([]);
  const [paid, setPaid] = useState<PaymentRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  const reload = useCallback(async () => {
    const [m, b, d, s, c, p] = await Promise.all([
      api.members(groupId), api.balances(groupId), api.debts(groupId), api.settlement(groupId),
      api.candidates(groupId), api.payments(groupId),
    ]);
    setMembers(m);
    setBalances(b.balances);
    setDebts(d.debts);
    setPayments(s.payments);
    setCandidates(c);
    setPaid(p);
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
        <Overview api={api} groupId={groupId} myMemberId={myMemberId} members={members} debts={debts}
          simplified={payments} paid={paid} onShowAllPayments={() => setTab('history')}
          record={(t) => run(() => api.recordPayment(groupId, t),
            t.toMemberId === myMemberId ? `Recorded: ${t.fromName} paid you ${formatCents(t.amountCents)}`
              : `Recorded: ${t.fromName} paid ${t.toName} ${formatCents(t.amountCents)}`)} />
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

      {tab === 'history' && <History api={api} groupId={groupId} members={members} payments={paid} />}

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

type SplitMode = 'EQUAL' | 'EXACT' | 'SHARES';
const SPLIT_MODES: { id: SplitMode; label: string; hint: string }[] = [
  { id: 'EQUAL', label: 'Equally', hint: 'Everyone selected pays the same.' },
  { id: 'EXACT', label: 'Exact amounts', hint: 'Enter what each person owes; it has to add up to the total.' },
  { id: 'SHARES', label: 'Shares', hint: 'E.g. 2 for someone who had two portions, 0 for someone who had none.' },
];

/**
 * Who owes whom. First everything between the signed-in member and the others ("You owe Lucy",
 * "Tim owes you"), then what is open between the others. The simplified plan (fewest transfers
 * for the whole group) is offered as a tip only when it actually saves transfers.
 */
function Overview({ api, groupId, myMemberId, members, debts, simplified, paid, record, onShowAllPayments }: {
  api: Api; groupId: number; myMemberId: number; members: Member[];
  debts: SuggestedPayment[]; simplified: SuggestedPayment[]; paid: PaymentRecord[];
  record: (transfer: SuggestedPayment) => Promise<boolean>; onShowAllPayments: () => void;
}) {
  const [showSimplified, setShowSimplified] = useState(false);
  const mine = debts.filter((d) => d.fromMemberId === myMemberId || d.toMemberId === myMemberId);
  const others = debts.filter((d) => d.fromMemberId !== myMemberId && d.toMemberId !== myMemberId);
  const me = members.find((m) => m.id === myMemberId);
  const names = new Map(members.map((m) => [m.id, m.displayName]));

  return (
    <div className="columns">
      <div className="card">
        <h2>Between you and the others</h2>
        {mine.length === 0
          ? <Empty title="You are settled with everyone" text="Nobody owes you anything and you owe nobody." />
          : mine.map((d) => (
            <DebtRow key={`${d.fromMemberId}-${d.toMemberId}`} api={api} groupId={groupId} debt={d}
              myMemberId={myMemberId} myIban={me?.iban ?? null} onSettled={() => record(d)} />
          ))}
      </div>

      <div className="card">
        <h2>Between the others</h2>
        {others.length === 0
          ? <Empty title="Nothing open" text="The others are settled among themselves." />
          : (
            <ul className="rows">
              {others.map((d) => (
                <li key={`${d.fromMemberId}-${d.toMemberId}`}>
                  <span className="avatar">{initials(d.fromName)}</span>
                  <span className="arrow" aria-hidden="true">→</span>
                  <span className="avatar">{initials(d.toName)}</span>
                  <div className="grow"><strong>{d.fromName} owes {d.toName}</strong></div>
                  <span className="amount">{formatCents(d.amountCents)}</span>
                </li>
              ))}
            </ul>
          )}

        {simplified.length > 0 && simplified.length < debts.length && (
          <div className="tip">
            <p>
              <strong>Tip:</strong> the group can settle everything with {simplified.length} {simplified.length === 1 ? 'transfer' : 'transfers'} instead
              of {debts.length}. The amounts change, the end result is the same.
            </p>
            <button className="ghost" onClick={() => setShowSimplified((v) => !v)}>{showSimplified ? 'Hide' : 'Show'} fewer transfers</button>
            {showSimplified && simplified.map((t) => (
              <DebtRow key={`s-${t.fromMemberId}-${t.toMemberId}`} api={api} groupId={groupId} debt={t}
                myMemberId={myMemberId} myIban={me?.iban ?? null} onSettled={() => record(t)} />
            ))}
          </div>
        )}

        {paid.length > 0 && (
          <>
            <h3 className="subhead">Recent payments</h3>
            <PaymentList payments={paid.slice(0, 3)} names={names} />
            {paid.length > 3 && (
              <button className="ghost wide spaced-small" onClick={onShowAllPayments}>All {paid.length} payments</button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/** One open amount between two people, phrased from the signed-in member's point of view. */
function DebtRow({ api, groupId, debt, myMemberId, myIban, onSettled }: {
  api: Api; groupId: number; debt: SuggestedPayment; myMemberId: number; myIban: string | null;
  onSettled: () => Promise<boolean>;
}) {
  const [qr, setQr] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => () => { if (qr) URL.revokeObjectURL(qr); }, [qr]);

  const iOwe = debt.fromMemberId === myMemberId;
  const theyOweMe = debt.toMemberId === myMemberId;
  const title = iOwe ? `You owe ${debt.toName}` : theyOweMe ? `${debt.fromName} owes you` : `${debt.fromName} pays ${debt.toName}`;
  const receiverIban = theyOweMe ? myIban : debt.toIban;
  const qrHint = iOwe
    ? 'Scan with your banking app, check name, IBAN and amount, send, then mark it as paid.'
    : theyOweMe
      ? `Let ${debt.fromName} scan this with their banking app; mark it as received when the money is there.`
      : `${debt.fromName} scans this with their banking app.`;

  return (
    <div className="payment">
      <div className="payment-line">
        <span className="avatar">{initials(iOwe ? debt.toName : debt.fromName)}</span>
        <div className="grow">
          <strong>{title}</strong>
          <span className="muted small">
            {receiverIban ? `to ${formatIban(receiverIban)}` : `${debt.toName} has not added an IBAN yet`}
          </span>
        </div>
        <span className={`amount ${iOwe ? 'negative' : theyOweMe ? 'positive' : ''}`}>{formatCents(debt.amountCents)}</span>
      </div>
      <div className="actions">
        {receiverIban && (
          <button className="ghost" onClick={() => (qr ? setQr(null)
            : api.giroCode(groupId, debt).then(setQr, (e) => setError(e.message)))}>
            {qr ? 'Hide GiroCode' : 'Show GiroCode'}
          </button>
        )}
        <button className="primary" onClick={() => void onSettled()}>{theyOweMe ? 'Mark as received' : 'Mark as paid'}</button>
      </div>
      {qr && (
        <div className="qr-box">
          <img src={qr} alt={`GiroCode: ${formatCents(debt.amountCents)} to ${debt.toName}`} />
          <p className="muted small">{qrHint}</p>
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
  const [mode, setMode] = useState<SplitMode>('EQUAL');
  const [excluded, setExcluded] = useState<number[]>([]);
  const [exact, setExact] = useState<Record<number, string>>({});
  const [shares, setShares] = useState<Record<number, string>>({});
  const total = parseEuroToCents(amount);
  const name = (id: number) => members.find((m) => m.id === id)?.displayName ?? '?';

  // What each person would owe, computed with the server's own rule (split.ts).
  const exactCents = new Map(members.map((m) => {
    const text = (exact[m.id] ?? '').trim();
    return [m.id, text === '' || text === '0' ? 0 : parseEuroToCents(text)] as const;
  }));
  const invalidExact = [...exactCents.values()].some((cents) => cents === null);
  const shareUnits = new Map(members.map((m) => {
    const text = (shares[m.id] ?? '1').trim();
    const units = /^\d{1,4}$/.test(text) ? Number(text) : NaN;
    return [m.id, units] as const;
  }));
  const invalidShares = [...shareUnits.values()].some((units) => Number.isNaN(units) || units > 1000);

  let allocations: Allocation[] = [];
  if (total !== null) {
    if (mode === 'EQUAL') {
      allocations = splitEqually(total, members.map((m) => m.id).filter((id) => !excluded.includes(id)));
    } else if (mode === 'SHARES' && !invalidShares) {
      allocations = splitProportionally(total, members.map((m) => ({ memberId: m.id, weight: shareUnits.get(m.id) ?? 0 })));
    } else if (mode === 'EXACT' && !invalidExact) {
      allocations = members.map((m) => ({ memberId: m.id, cents: exactCents.get(m.id) ?? 0 })).filter((a) => a.cents > 0);
    }
  }
  const assigned = allocations.reduce((sum, a) => sum + a.cents, 0);
  const remaining = total === null ? 0 : total - assigned;

  // Why the button is disabled, in words.
  let problem: string | null = null;
  if (total === null) problem = amount === '' ? 'Enter an amount.' : 'Use an amount like 12,50.';
  else if (mode === 'EXACT' && invalidExact) problem = 'One of the amounts is not a valid number.';
  else if (mode === 'SHARES' && invalidShares) problem = 'Shares must be whole numbers from 0 to 1000.';
  else if (allocations.length === 0) problem = 'Choose who shares this expense.';
  else if (mode === 'EXACT' && remaining !== 0) {
    problem = remaining > 0 ? `${formatCents(remaining)} still has to be assigned.` : `${formatCents(-remaining)} too much assigned.`;
  } else if (allocations.every((a) => a.memberId === payerId)) {
    problem = `Only ${name(payerId)} takes part, so nothing would be shared. Add someone else.`;
  }

  function splitRest() {
    if (total === null) return;
    const current = new Map(members.map((m) => {
      const text = (exact[m.id] ?? '').trim();
      return [m.id, text === '' ? null : exactCents.get(m.id) ?? null] as const;
    }));
    const filled = distributeRest(total, current);
    setExact(Object.fromEntries(members.map((m) => [m.id, formatPlain(filled.get(m.id) ?? 0)])));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (problem || total === null) return;
    const participants = mode === 'EQUAL'
      ? allocations.map((a) => ({ memberId: a.memberId }))
      : mode === 'EXACT'
        ? allocations.map((a) => ({ memberId: a.memberId, value: a.cents }))
        : members.filter((m) => (shareUnits.get(m.id) ?? 0) > 0).map((m) => ({ memberId: m.id, value: shareUnits.get(m.id) }));
    const ok = await onSubmit({ payerId, amountCents: total, description, expenseDate: date, splitMode: mode, participants });
    if (ok) {
      setDescription('');
      setAmount('');
      setExact({});
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
        <Field label="Amount (€)">
          <input value={amount} onChange={(e) => setAmount(e.target.value)} required inputMode="decimal" placeholder="0,00"
            aria-invalid={amount !== '' && total === null} />
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

      <div className="stack-small">
        <span className="label">How is it split?</span>
        <div className="segmented three">
          {SPLIT_MODES.map((m) => (
            <button type="button" key={m.id} className={mode === m.id ? 'active' : ''} onClick={() => setMode(m.id)}>{m.label}</button>
          ))}
        </div>
        <p className="muted small">{SPLIT_MODES.find((m) => m.id === mode)!.hint}</p>
      </div>

      <ul className="rows split-rows">
        {members.map((m) => {
          const share = allocations.find((a) => a.memberId === m.id)?.cents ?? 0;
          const included = !excluded.includes(m.id);
          return (
            <li key={m.id}>
              <span className="avatar">{initials(m.displayName)}</span>
              <div className="grow"><strong>{m.displayName}{m.id === myMemberId ? ' (you)' : ''}</strong></div>
              {mode === 'EQUAL' && (
                <button type="button" className={`chip ${included ? 'on' : ''}`} aria-pressed={included}
                  aria-label={`${m.displayName} shares`}
                  onClick={() => setExcluded(included ? [...excluded, m.id] : excluded.filter((id) => id !== m.id))}>
                  {included ? 'Shares' : 'Not in'}
                </button>
              )}
              {mode === 'EXACT' && (
                <input className="amount-input" inputMode="decimal" placeholder="0,00" aria-label={`Amount for ${m.displayName}`}
                  value={exact[m.id] ?? ''} aria-invalid={exactCents.get(m.id) === null}
                  onChange={(e) => setExact({ ...exact, [m.id]: e.target.value })} />
              )}
              {mode === 'SHARES' && (
                <input className="amount-input" inputMode="numeric" aria-label={`Shares of ${m.displayName}`}
                  value={shares[m.id] ?? '1'} aria-invalid={Number.isNaN(shareUnits.get(m.id) ?? 0)}
                  onChange={(e) => setShares({ ...shares, [m.id]: e.target.value })} />
              )}
              {mode !== 'EXACT' && <span className="amount share-preview">{share > 0 ? formatCents(share) : '–'}</span>}
            </li>
          );
        })}
      </ul>

      {mode === 'EXACT' && total !== null && !invalidExact && (
        <div className={`assigned ${remaining === 0 ? 'done' : remaining < 0 ? 'over' : ''}`}>
          <span>{formatCents(assigned)} of {formatCents(total)} assigned
            {remaining !== 0 && ` · ${remaining > 0 ? `${formatCents(remaining)} left` : `${formatCents(-remaining)} too much`}`}</span>
          {remaining > 0 && <button type="button" className="ghost" onClick={splitRest}>Split the rest</button>}
        </div>
      )}

      {problem && amount !== '' && <p className="muted small">{problem}</p>}
      <button className="primary wide" disabled={problem !== null}>
        Add {total !== null ? formatCents(total) : 'expense'}
      </button>
    </form>
  );
}

/** 1250 -> "12,50" for an input field (no currency sign, no thousands separator). */
function formatPlain(cents: number): string {
  return `${Math.floor(cents / 100)},${String(cents % 100).padStart(2, '0')}`;
}

/** Expenses (newest first, 20 at a time, searchable) and recorded payments. */
function History({ api, groupId, members, payments }:
  { api: Api; groupId: number; members: Member[]; payments: PaymentRecord[] }) {
  const [view, setView] = useState<'expenses' | 'payments'>('expenses');
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
      <div className="card-head">
        <h2>History</h2>
        <div className="segmented compact">
          <button className={view === 'expenses' ? 'active' : ''} onClick={() => setView('expenses')}>Expenses</button>
          <button className={view === 'payments' ? 'active' : ''} onClick={() => setView('payments')}>
            Payments{payments.length > 0 ? ` (${payments.length})` : ''}
          </button>
        </div>
      </div>

      {view === 'payments' && (
        payments.length === 0
          ? <Empty title="No payments yet" text="When someone settles up and marks it as paid, it appears here." />
          : <PaymentList payments={payments} names={names} />
      )}

      {view === 'expenses' && (
        <>
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
        </>
      )}
    </div>
  );
}

function PaymentList({ payments, names }: { payments: PaymentRecord[]; names: Map<number, string> }) {
  return (
    <ul className="rows">
      {payments.map((p) => (
        <li key={p.id}>
          <span className="avatar">{initials(names.get(p.fromMemberId) ?? '?')}</span>
          <span className="arrow" aria-hidden="true">→</span>
          <span className="avatar">{initials(names.get(p.toMemberId) ?? '?')}</span>
          <div className="grow">
            <strong>{names.get(p.fromMemberId) ?? '?'} paid {names.get(p.toMemberId) ?? '?'}</strong>
            <span className="muted small">{formatDateTime(p.createdAt)}{p.note ? ` · ${p.note}` : ''}</span>
          </div>
          <span className="amount positive">{formatCents(p.amountCents)}</span>
        </li>
      ))}
    </ul>
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

function formatDateTime(isoInstant: string): string {
  return new Date(isoInstant).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' });
}

function formatDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-');
  return `${day}.${month}.${year}`;
}
