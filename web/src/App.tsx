import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  Api, register, type Balance, type BankTransaction, type ExpenseHit, type ImportReport, type Me, type Member,
  type SuggestedPayment,
} from './api';
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

  return (
    <main>
      <header>
        <h1>SplitLedger</h1>
        {api && <button className="link" onClick={signOut}>Sign out</button>}
      </header>
      {api ? <Home api={api} onInvalidToken={signOut} /> : <SignIn onToken={signIn} />}
    </main>
  );
}

function SignIn({ onToken }: { onToken: (token: string) => void }) {
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

  if (newToken) {
    return (
      <section>
        <h2>Your token</h2>
        <p>It is shown only once. Keep it in your password manager; it is your login on every device.</p>
        <code className="token">{newToken}</code>
        <button onClick={() => onToken(newToken)}>I saved it, continue</button>
      </section>
    );
  }

  return (
    <>
      <section>
        <h2>Sign in</h2>
        <form onSubmit={(e) => { e.preventDefault(); onToken(existing.trim()); }}>
          <input placeholder="slt_..." value={existing} onChange={(e) => setExisting(e.target.value)} required />
          <button>Sign in</button>
        </form>
      </section>
      <section>
        <h2>New here?</h2>
        <form onSubmit={create}>
          <input placeholder="Your name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} />
          <input placeholder="Registration code (from your friend)" value={code} onChange={(e) => setCode(e.target.value)} />
          <button>Create account</button>
        </form>
        {error && <p className="error">{error}</p>}
      </section>
    </>
  );
}

function Home({ api, onInvalidToken }: { api: Api; onInvalidToken: () => void }) {
  const [me, setMe] = useState<Me | null>(null);
  const [groupId, setGroupId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadMe = useCallback(async () => {
    try {
      const loaded = await api.me();
      setMe(loaded);
      setGroupId((current) => current ?? loaded.groups[0]?.groupId ?? null);
    } catch (err) {
      if ((err as { status?: number }).status === 401) {
        onInvalidToken();
      } else {
        setError((err as Error).message);
      }
    }
  }, [api, onInvalidToken]);

  useEffect(() => { void loadMe(); }, [loadMe]);

  async function run(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
      await loadMe();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (!me) {
    return <p>{error ?? 'Loading…'}</p>;
  }

  return (
    <>
      <section className="row">
        <span>Hello {me.displayName}</span>
        <select value={groupId ?? ''} onChange={(e) => setGroupId(Number(e.target.value))}>
          {me.groups.length === 0 && <option value="">No group yet</option>}
          {me.groups.map((g) => <option key={g.groupId} value={g.groupId}>{g.groupName}</option>)}
        </select>
        <InlineForm placeholder="New group name" button="Create group"
          onSubmit={(name) => run(async () => setGroupId((await api.createGroup(name)).id))} />
        <InlineForm placeholder="Invite code" button="Join"
          onSubmit={(code) => run(async () => setGroupId((await api.acceptInvitation(code)).groupId))} />
      </section>
      {error && <p className="error">{error}</p>}
      {groupId && <Group key={groupId} api={api} groupId={groupId} myMemberId={me.groups.find((g) => g.groupId === groupId)?.memberId} />}
    </>
  );
}

function Group({ api, groupId, myMemberId }: { api: Api; groupId: number; myMemberId?: number }) {
  const [members, setMembers] = useState<Member[]>([]);
  const [balances, setBalances] = useState<Balance[]>([]);
  const [payments, setPayments] = useState<SuggestedPayment[]>([]);
  const [candidates, setCandidates] = useState<BankTransaction[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

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

  async function run(action: () => Promise<unknown>, success?: string) {
    setError(null);
    setNotice(null);
    try {
      await action();
      await reload();
      if (success) setNotice(success);
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const memberIds = useMemo(() => members.map((m) => m.id), [members]);
  const me = members.find((m) => m.id === myMemberId);

  return (
    <>
      {error && <p className="error">{error}</p>}
      {notice && <p className="notice">{notice}</p>}

      <section>
        <h2>Balances</h2>
        <table>
          <tbody>
            {balances.map((b) => (
              <tr key={b.memberId}>
                <td>{b.displayName}</td>
                <td className={b.balanceCents < 0 ? 'amount negative' : 'amount'}>{formatCents(b.balanceCents)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <h3>Settle up</h3>
        {payments.length === 0 && <p>Everyone is even.</p>}
        {payments.map((p) => (
          <Payment key={`${p.fromMemberId}-${p.toMemberId}`} api={api} groupId={groupId} payment={p}
            onPaid={() => run(() => api.recordPayment(groupId, p), `Recorded: ${p.fromName} paid ${p.toName}`)} />
        ))}
      </section>

      <ExpenseForm members={members} myMemberId={myMemberId}
        onSubmit={(body) => run(() => api.addExpense(groupId, body), 'Expense added')} />

      <section>
        <h2>Bank statement</h2>
        <p className="hint">Upload your Sparkasse CSV export. Only you see your transactions until you confirm one.</p>
        <input type="file" accept=".csv,text/csv"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) {
              void run(async () => {
                const report: ImportReport = await api.importStatement(groupId, file);
                setNotice(`${report.newTransactions} new, ${report.duplicates} already imported, ${report.skippedCount} skipped`);
              });
              e.target.value = '';
            }
          }} />
        <ul className="list">
          {candidates.map((t) => (
            <li key={t.id}>
              <span>{t.bookingDate} · {t.counterpartyName ?? 'Unknown'} · {formatCents(-t.amountCents)}</span>
              <span>
                <button onClick={() => run(() => api.confirm(groupId, t.id, memberIds), 'Shared with everyone')}>Share</button>
                <button className="secondary" onClick={() => run(() => api.ignore(groupId, t.id))}>Ignore</button>
              </span>
            </li>
          ))}
        </ul>
      </section>

      <Search api={api} groupId={groupId} members={members} />

      <section>
        <h2>Members</h2>
        <ul className="list">
          {members.map((m) => <li key={m.id}><span>{m.displayName}{m.joined ? '' : ' (invited)'}</span><span>{m.iban ?? ''}</span></li>)}
        </ul>
        <InlineForm placeholder="Friend's name" button="Invite"
          onSubmit={(name) => run(async () => {
            const added = await api.addMember(groupId, name);
            setNotice(`Send ${name} this invite code: ${added.inviteCode}`);
          })} />
        <InlineForm placeholder={me?.iban ?? 'Your IBAN (so others can pay you)'} button="Save IBAN"
          onSubmit={(iban) => run(() => api.setMyIban(groupId, iban), 'IBAN saved')} />
      </section>
    </>
  );
}

function Payment({ api, groupId, payment, onPaid }:
  { api: Api; groupId: number; payment: SuggestedPayment; onPaid: () => void }) {
  const [qr, setQr] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => () => { if (qr) URL.revokeObjectURL(qr); }, [qr]);

  return (
    <div className="payment">
      <p><strong>{payment.fromName}</strong> pays <strong>{payment.toName}</strong> {formatCents(payment.amountCents)}</p>
      <div className="row">
        {payment.toIban && !qr && (
          <button className="secondary" onClick={() => api.giroCode(groupId, payment).then(setQr, (e) => setError(e.message))}>
            Show GiroCode
          </button>
        )}
        {!payment.toIban && <span className="hint">{payment.toName} has no IBAN yet</span>}
        <button onClick={onPaid}>Mark as paid</button>
      </div>
      {qr && <img className="qr" src={qr} alt={`GiroCode: ${formatCents(payment.amountCents)} to ${payment.toName}`} />}
      {qr && <p className="hint">Scan with your banking app, check the amount, send. Then mark it as paid.</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function ExpenseForm({ members, myMemberId, onSubmit }:
  { members: Member[]; myMemberId?: number; onSubmit: (body: unknown) => Promise<void> }) {
  const today = new Date().toISOString().slice(0, 10);
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState(today);
  const [payerId, setPayerId] = useState<number | undefined>(myMemberId);
  const [participants, setParticipants] = useState<number[] | null>(null);
  const chosen = participants ?? members.map((m) => m.id);
  const cents = parseEuroToCents(amount);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (cents === null || chosen.length === 0) return;
    await onSubmit({
      payerId: payerId ?? myMemberId, amountCents: cents, description, expenseDate: date, splitMode: 'EQUAL',
      participants: chosen.map((memberId) => ({ memberId })),
    });
    setDescription('');
    setAmount('');
  }

  return (
    <section>
      <h2>Add an expense</h2>
      <form onSubmit={submit} className="grid">
        <input placeholder="What for?" value={description} onChange={(e) => setDescription(e.target.value)} required maxLength={200} />
        <input placeholder="Amount, e.g. 12,50" value={amount} onChange={(e) => setAmount(e.target.value)} required inputMode="decimal"
          aria-invalid={amount !== '' && cents === null} />
        <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required />
        <label>Paid by{' '}
          <select value={payerId ?? myMemberId ?? ''} onChange={(e) => setPayerId(Number(e.target.value))}>
            {members.map((m) => <option key={m.id} value={m.id}>{m.displayName}</option>)}
          </select>
        </label>
        <fieldset>
          <legend>Split equally between</legend>
          {members.map((m) => (
            <label key={m.id} className="check">
              <input type="checkbox" checked={chosen.includes(m.id)}
                onChange={(e) => setParticipants(e.target.checked ? [...chosen, m.id] : chosen.filter((id) => id !== m.id))} />
              {m.displayName}
            </label>
          ))}
        </fieldset>
        <button disabled={cents === null || chosen.length === 0}>Add {cents !== null ? formatCents(cents) : ''}</button>
      </form>
    </section>
  );
}

function Search({ api, groupId, members }: { api: Api; groupId: number; members: Member[] }) {
  const [hits, setHits] = useState<ExpenseHit[] | null>(null);
  const names = new Map(members.map((m) => [m.id, m.displayName]));

  return (
    <section>
      <h2>Search the history</h2>
      <InlineForm placeholder="e.g. Einkauf, Pizza, Lidl" button="Search"
        onSubmit={(q) => api.search(groupId, q).then((r) => setHits(r.items))} />
      {hits && hits.length === 0 && <p>Nothing found.</p>}
      <ul className="list">
        {hits?.map((h) => (
          <li key={h.id} className={h.reversed ? 'reversed' : ''}>
            <span>{h.expenseDate} · {h.description} · paid by {names.get(h.payerId) ?? '?'}</span>
            <span className="amount">{formatCents(h.amountCents)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function InlineForm({ placeholder, button, onSubmit }:
  { placeholder: string; button: string; onSubmit: (value: string) => Promise<unknown> }) {
  const [value, setValue] = useState('');
  return (
    <form className="inline" onSubmit={(e) => {
      e.preventDefault();
      void onSubmit(value.trim()).then(() => setValue(''));
    }}>
      <input placeholder={placeholder} value={value} onChange={(e) => setValue(e.target.value)} required />
      <button>{button}</button>
    </form>
  );
}
