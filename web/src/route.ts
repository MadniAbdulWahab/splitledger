import { useCallback, useEffect, useState } from 'react';

// Where the page is, kept in the URL hash so the back button, reloads and shared links work
// without a router library and without the server knowing any page paths.
//
//   #/g/12/home          a screen of a group
//   #/g/12/member/34     one person's page
//   #join=inv_…          an invite link
//   #reset=rst_…         a password reset link
//
// Invite and reset codes live in the fragment on purpose: browsers never send it to the server,
// so the codes never appear in request logs.

export const SCREENS = ['home', 'activity', 'insights', 'bank', 'group', 'member'] as const;
export type Screen = typeof SCREENS[number];

export type Route =
  | { kind: 'join'; code: string }
  | { kind: 'reset'; code: string }
  | { kind: 'group'; groupId: number; screen: Screen; memberId?: number }
  | { kind: 'none' };

export function parseHash(hash: string): Route {
  const value = hash.replace(/^#/, '');
  const link = /^(join|reset)=([\w-]+)$/.exec(value);
  if (link) {
    return { kind: link[1] as 'join' | 'reset', code: link[2]! };
  }
  const group = /^\/g\/(\d+)(?:\/([a-z]+)(?:\/(\d+))?)?$/.exec(value);
  if (group) {
    // "expenses" was the history's old name; links and bookmarks to it still land there.
    const name = group[2] === 'expenses' ? 'activity' : group[2];
    const screen = SCREENS.find((s) => s === name) ?? 'home';
    if (screen === 'member') {
      return group[3] ? { kind: 'group', groupId: Number(group[1]), screen, memberId: Number(group[3]) }
        : { kind: 'group', groupId: Number(group[1]), screen: 'home' };
    }
    return { kind: 'group', groupId: Number(group[1]), screen };
  }
  return { kind: 'none' };
}

/** The invite code from what someone pasted: the whole link, or just "inv_…". Null if neither. */
export function inviteCodeFrom(text: string): string | null {
  const match = /(?:^|#join=)(inv_[\w-]+)\s*$/.exec(text.trim());
  return match ? match[1]! : null;
}

export function groupHref(groupId: number, screen: Screen = 'home', memberId?: number): string {
  return `#/g/${groupId}/${screen}${memberId !== undefined ? `/${memberId}` : ''}`;
}

/** The full link a friend receives, e.g. https://host/#join=inv_… */
export function linkFor(kind: 'join' | 'reset', code: string, origin: string = window.location.origin): string {
  return `${origin}/#${kind}=${code}`;
}

export function useRoute(): [Route, (hash: string, replace?: boolean) => void] {
  const [route, setRoute] = useState<Route>(() => parseHash(window.location.hash));

  useEffect(() => {
    const update = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);

  const navigate = useCallback((hash: string, replace = false) => {
    if (replace) {
      // Used after an invite or reset link was used: the code should not stay in the history.
      window.history.replaceState(null, '', hash || window.location.pathname);
      setRoute(parseHash(hash));
    } else {
      window.location.hash = hash;
    }
  }, []);

  return [route, navigate];
}
