import { useCallback, useMemo, useState } from 'react';
import { Api } from './api';
import { Join, ResetPassword, SignIn } from './auth/AuthScreens';
import { SignedIn } from './group/Shell';
import { groupHref, useRoute } from './route';
import { ToastProvider } from './ui/toast';

const TOKEN_KEY = 'splitledger.token';

function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function writeToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // Private mode: the session lasts until the page is closed.
  }
}

export function App() {
  return <ToastProvider><Root /></ToastProvider>;
}

function Root() {
  const [token, setToken] = useState<string | null>(readToken);
  const [route, navigate] = useRoute();
  // One client per token, so hooks that depend on it only re-run after signing in or out.
  const api = useMemo(() => (token ? new Api(token) : null), [token]);

  const signIn = useCallback((newToken: string) => {
    writeToken(newToken);
    setToken(newToken);
  }, []);

  const signOut = useCallback((everywhere: boolean) => {
    // Revoke the session on the server first; if that fails (offline, already expired) the
    // token is dropped here anyway.
    const current = token ? new Api(token) : null;
    void (everywhere ? current?.logoutEverywhere() : current?.logout())?.catch(() => {});
    writeToken(null);
    setToken(null);
  }, [token]);

  // Invite and reset links are handled first, signed in or not; afterwards the code leaves the URL.
  if (route.kind === 'join') {
    return (
      <Join code={route.code} api={api} onToken={signIn} onSignOut={() => signOut(false)}
        onJoined={(groupId) => navigate(groupId ? groupHref(groupId) : '', true)} />
    );
  }
  if (route.kind === 'reset') {
    return <ResetPassword code={route.code} onToken={signIn} onDone={() => navigate('', true)} />;
  }
  if (!api) return <SignIn onToken={signIn} />;
  return <SignedIn api={api} route={route} navigate={navigate} onSignOut={signOut} />;
}
