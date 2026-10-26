import { useState } from 'react';
import type { Api, Me } from '../api';
import { useInstall } from '../install';
import { Icon } from '../ui/Icon';
import { useFormSubmit } from '../ui/once';
import { Avatar, Field, Sheet } from '../ui/parts';
import { useToast } from '../ui/toast';

export type Theme = 'system' | 'light' | 'dark';
const THEME_KEY = 'splitledger.theme';

export function readTheme(): Theme {
  try {
    const value = localStorage.getItem(THEME_KEY);
    return value === 'light' || value === 'dark' ? value : 'system';
  } catch {
    return 'system';
  }
}

export function applyTheme(theme: Theme) {
  if (theme === 'system') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    // Private mode: the choice lasts until the page is closed.
  }
}

/** Your name, username and password, the theme, installing the app, and signing out. */
export function AccountSheet({ api, me, avatarId, onChanged, onSignOut, onClose }: {
  api: Api; me: Me; avatarId: number; onChanged: () => void; onSignOut: (everywhere: boolean) => void; onClose: () => void;
}) {
  const toast = useToast();
  const install = useInstall();
  const [theme, setTheme] = useState<Theme>(readTheme);
  const [username, setUsername] = useState(me.username ?? '');
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [error, setError] = useState<string | null>(null);

  const save = useFormSubmit(async () => {
    setError(null);
    try {
      if (username.trim() && username.trim().toLowerCase() !== me.username) await api.changeUsername(username.trim());
      if (next) await api.changePassword(me.hasPassword ? current : null, next);
      setCurrent('');
      setNext('');
      onChanged();
      toast(next ? 'Saved. Your other devices are signed out.' : 'Saved');
    } catch (err) {
      setError((err as Error).message);
    }
  });

  return (
    <Sheet title="Your account" onClose={onClose}>
      <div className="stack">
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <Avatar id={avatarId} name={me.displayName} size="large" />
          <div><h2>{me.displayName}</h2><p className="muted small">{me.username ? `Signed in as ${me.username}` : 'No username yet'}</p></div>
        </div>

        <form className="stack" onSubmit={save}>
          {!me.hasPassword && <p className="banner small">Choose a username and password, so you can sign in on any device without your old token.</p>}
          <Field label="Username">
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoCapitalize="none" maxLength={30} required />
          </Field>
          {me.hasPassword && (
            <Field label="Current password">
              <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required={next !== ''} />
            </Field>
          )}
          <Field label={me.hasPassword ? 'New password' : 'Password'} hint="At least 8 characters. Leave empty to keep it.">
            <input type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" minLength={8}
              required={!me.hasPassword} />
          </Field>
          {error && <p className="error-text" role="alert">{error}</p>}
          <button className="btn btn-primary btn-block">Save</button>
        </form>

        <div className="field">
          <span className="label">Appearance</span>
          <div className="segmented" role="group" aria-label="Appearance">
            {(['system', 'light', 'dark'] as const).map((t) => (
              <button type="button" key={t} aria-pressed={theme === t} onClick={() => { setTheme(t); applyTheme(t); }}>
                {t === 'system' ? 'Automatic' : t === 'light' ? 'Light' : 'Dark'}
              </button>
            ))}
          </div>
        </div>

        {install.kind === 'prompt' && (
          <button type="button" className="btn btn-quiet btn-block" onClick={() => void install.install()}><Icon name="install" small />Install the app</button>
        )}
        {install.kind === 'ios' && (
          <p className="hint">To install it on your iPhone: tap the share button in Safari, then “Add to Home Screen”.</p>
        )}

        <div className="form-row">
          <button type="button" className="btn btn-quiet" onClick={() => onSignOut(false)}>Sign out</button>
          <button type="button" className="btn btn-danger" onClick={() => {
            if (window.confirm('Sign out on all your devices, including this one?')) onSignOut(true);
          }}>Sign out everywhere</button>
        </div>
      </div>
    </Sheet>
  );
}
