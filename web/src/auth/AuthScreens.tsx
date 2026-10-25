import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import {
  Api, login, previewInvitation, previewReset, redeemReset, registerAccount, signUpWithInvitation,
  type InvitationPreview, type ResetPreview,
} from '../api';
import { Field, Guilloche } from '../ui/parts';

/** "Ben Beispiel" -> "ben.beispiel": a username suggestion that fits the server's rule. */
export function suggestUsername(name: string): string {
  return name.trim().toLowerCase()
    .replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss')
    .replace(/\s+/g, '.').replace(/[^a-z0-9._-]/g, '').slice(0, 30);
}

function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <main className="auth">
      <div className="auth-box">
        <span className="auth-brand"><img className="brand-icon" src="/icons/icon.svg" alt="" />SplitLedger</span>
        {children}
      </div>
    </main>
  );
}

function useSubmit(action: () => Promise<void>): [(e: FormEvent) => void, boolean, string | null] {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    action().catch((err: Error) => setError(err.message)).finally(() => setBusy(false));
  };
  return [submit, busy, error];
}

function PasswordInput({ value, onChange, isNew }: { value: string; onChange: (v: string) => void; isNew?: boolean }) {
  return (
    <input type="password" value={value} onChange={(e) => onChange(e.target.value)} required
      minLength={isNew ? 8 : undefined} autoComplete={isNew ? 'new-password' : 'current-password'} />
  );
}

/* ---------- Sign in (the default for anyone without a session) ---------- */

export function SignIn({ onToken }: { onToken: (token: string) => void }) {
  const [view, setView] = useState<'signin' | 'start' | 'token'>('signin');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submit, busy, error] = useSubmit(async () => onToken(await login(username, password)));

  if (view === 'start') return <StartGroup onToken={onToken} onBack={() => setView('signin')} />;
  if (view === 'token') return <TokenSignIn onToken={onToken} onBack={() => setView('signin')} />;

  return (
    <AuthFrame>
      <div className="group-card invite-card">
        <Guilloche />
        <p className="welcome-line">Who paid what, and who owes whom.</p>
        <p className="card-sub">Shared costs for your flat or trip, settled with a scan in your banking app.</p>
      </div>
      <form className="auth-panel" onSubmit={submit}>
        <h1>Sign in</h1>
        <Field label="Username">
          <input value={username} onChange={(e) => setUsername(e.target.value)} required autoComplete="username"
            autoCapitalize="none" spellCheck={false} />
        </Field>
        <Field label="Password"><PasswordInput value={password} onChange={setPassword} /></Field>
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        <p className="hint">Forgot your password? Ask someone in your group to make you a reset link on their Group page.</p>
      </form>
      <div className="auth-alt">
        <p className="muted small">Got an invite link? Open it and you can sign up from there.</p>
        <button type="button" className="btn btn-link" onClick={() => setView('start')}>Start a new group</button>
        <button type="button" className="btn btn-link small" onClick={() => setView('token')}>Sign in with an access token</button>
      </div>
    </AuthFrame>
  );
}

function StartGroup({ onToken, onBack }: { onToken: (token: string) => void; onBack: () => void }) {
  const [name, setName] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [submit, busy, error] = useSubmit(async () =>
    onToken(await registerAccount(name.trim(), username || suggestUsername(name), password, code.trim())));

  return (
    <AuthFrame>
      <form className="auth-panel" onSubmit={submit}>
        <h1>Start a new group</h1>
        <p className="muted small">Create your account, then your group, then send your friends their invite links.</p>
        <Field label="Your name" hint="How your friends see you.">
          <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} autoComplete="name" />
        </Field>
        <Field label="Username" hint="3 to 30 letters, digits, dots or dashes. You sign in with it.">
          <input value={username} placeholder={suggestUsername(name) || 'e.g. anna'} onChange={(e) => setUsername(e.target.value)}
            autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={30} />
        </Field>
        <Field label="Password" hint="At least 8 characters."><PasswordInput value={password} onChange={setPassword} isNew /></Field>
        <Field label="Host code" hint="The person who runs this SplitLedger gives it to you.">
          <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" />
        </Field>
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Creating…' : 'Create account'}</button>
      </form>
      <div className="auth-alt"><button type="button" className="btn btn-link" onClick={onBack}>I already have an account</button></div>
    </AuthFrame>
  );
}

/** For accounts from before passwords existed: the slt_… token, once more. */
function TokenSignIn({ onToken, onBack }: { onToken: (token: string) => void; onBack: () => void }) {
  const [token, setToken] = useState('');
  const [submit, busy, error] = useSubmit(async () => {
    await new Api(token.trim()).me(); // checks the token before keeping it
    onToken(token.trim());
  });
  return (
    <AuthFrame>
      <form className="auth-panel" onSubmit={submit}>
        <h1>Sign in with a token</h1>
        <p className="muted small">For accounts from before usernames. After signing in, choose a username and password in your account.</p>
        <Field label="Access token"><input value={token} onChange={(e) => setToken(e.target.value)} placeholder="slt_…" required autoComplete="off" /></Field>
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="btn btn-primary btn-block" disabled={busy}>Sign in</button>
      </form>
      <div className="auth-alt"><button type="button" className="btn btn-link" onClick={onBack}>Back</button></div>
    </AuthFrame>
  );
}

/* ---------- Invite link: #join=inv_… ---------- */

export function Join({ code, api, onToken, onJoined, onSignOut }: {
  code: string; api: Api | null;
  onToken: (token: string) => void; onJoined: (groupId: number | null) => void; onSignOut: () => void;
}) {
  const [preview, setPreview] = useState<InvitationPreview | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [signedInAs, setSignedInAs] = useState<string | null>(null);

  useEffect(() => {
    api?.me().then((me) => setSignedInAs(me.username ?? me.displayName), () => setSignedInAs(null));
  }, [api]);

  useEffect(() => {
    previewInvitation(code).then((p) => {
      setPreview(p);
      setUsername(suggestUsername(p.memberName));
    }, (err: Error) => setLookupError(err.message));
  }, [code]);

  const [signUp, busy, error] = useSubmit(async () => {
    onToken(await signUpWithInvitation(code, username, password));
    onJoined(null);
  });
  const [accept, accepting, acceptError] = useSubmit(async () => {
    onJoined((await api!.acceptInvitation(code)).groupId);
  });

  if (lookupError) {
    return (
      <AuthFrame>
        <div className="auth-panel">
          <h1>This invite link does not work</h1>
          <p className="muted">It was already used, or a newer link replaced it. Ask the friend who sent it for a new one.</p>
          <button type="button" className="btn btn-quiet btn-block" onClick={() => onJoined(null)}>Go to SplitLedger</button>
        </div>
      </AuthFrame>
    );
  }
  if (!preview) return <AuthFrame><div className="auth-panel"><p className="muted">Opening your invitation…</p></div></AuthFrame>;

  return (
    <AuthFrame>
      <div className="group-card invite-card">
        <Guilloche />
        <p className="card-sub">{preview.invitedByName ? `${preview.invitedByName} invited you to` : 'You are invited to'}</p>
        <p className="welcome-line">{preview.groupName}</p>
        <p className="card-sub">You join as {preview.memberName}.</p>
      </div>
      {api ? (
        <form className="auth-panel" onSubmit={accept}>
          <h1>Join with this account?</h1>
          <p className="muted">You are signed in{signedInAs ? ` as ${signedInAs}` : ''}.</p>
          {acceptError && <p className="error-text" role="alert">{acceptError}</p>}
          <button className="btn btn-primary btn-block" disabled={accepting}>Join {preview.groupName}</button>
          <button type="button" className="btn btn-link" onClick={onSignOut}>That is not me, sign out</button>
        </form>
      ) : (
        <form className="auth-panel" onSubmit={signUp}>
          <h1>Create your account</h1>
          <Field label="Username" hint="You sign in with it on every device.">
            <input value={username} onChange={(e) => setUsername(e.target.value)} required maxLength={30}
              autoComplete="username" autoCapitalize="none" spellCheck={false} />
          </Field>
          <Field label="Password" hint="At least 8 characters."><PasswordInput value={password} onChange={setPassword} isNew /></Field>
          {error && <p className="error-text" role="alert">{error}</p>}
          <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Joining…' : `Join ${preview.groupName}`}</button>
        </form>
      )}
    </AuthFrame>
  );
}

/* ---------- Password reset link: #reset=rst_… ---------- */

export function ResetPassword({ code, onToken, onDone }: { code: string; onToken: (token: string) => void; onDone: () => void }) {
  const [preview, setPreview] = useState<ResetPreview | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  useEffect(() => {
    previewReset(code).then((p) => {
      setPreview(p);
      setUsername(p.username ?? suggestUsername(p.displayName));
    }, (err: Error) => setLookupError(err.message));
  }, [code]);

  const [submit, busy, error] = useSubmit(async () => {
    onToken(await redeemReset(code, preview?.username ? null : username, password));
    onDone();
  });

  if (lookupError) {
    return (
      <AuthFrame>
        <div className="auth-panel">
          <h1>This reset link does not work</h1>
          <p className="muted">Reset links work once and for 24 hours. Ask someone in your group for a new one.</p>
          <button type="button" className="btn btn-quiet btn-block" onClick={onDone}>Go to sign in</button>
        </div>
      </AuthFrame>
    );
  }
  if (!preview) return <AuthFrame><div className="auth-panel"><p className="muted">Checking the link…</p></div></AuthFrame>;

  return (
    <AuthFrame>
      <form className="auth-panel" onSubmit={submit}>
        <h1>New password for {preview.displayName}</h1>
        {preview.username
          ? <p className="muted">Your username is <strong>{preview.username}</strong>. All your devices are signed out once you save.</p>
          : (
            <Field label="Choose a username" hint="You sign in with it from now on.">
              <input value={username} onChange={(e) => setUsername(e.target.value)} required maxLength={30} autoComplete="username" autoCapitalize="none" />
            </Field>
          )}
        <Field label="New password" hint="At least 8 characters."><PasswordInput value={password} onChange={setPassword} isNew /></Field>
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="btn btn-primary btn-block" disabled={busy}>Save password and sign in</button>
      </form>
    </AuthFrame>
  );
}
