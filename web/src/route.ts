import { useCallback, useEffect, useState } from 'react';

// Where the page is, kept in the URL hash so the back button, reloads and shared links work
// without a router library and without the server knowing any page paths.
//
//   #/g/12/home          a screen of a group
//   #join=inv_…          an invite link
//   #reset=rst_…         a password reset link
//
// Invite and reset codes live in the fragment on purpose: browsers never send it to the server,
// so the codes never appear in request logs.

export const SCREENS = ['home', 'expenses', 'activity', 'insights', 'bank', 'group'] as const;
export type Screen = typeof SCREENS[number];

export type Route =
  | { kind: 'join'; code: string }
  | { kind: 'reset'; code: string }
  | { kind: 'group'; groupId: number; screen: Screen }
  | { kind: 'none' };

export function parseHash(hash: string): Route {
  const value = hash.replace(/^#/, '');
  const link = /^(join|reset)=([\w-]+)$/.exec(value);
  if (link) {
    return { kind: link[1] as 'join' | 'reset', code: link[2]! };
  }
  const group = /^\/g\/(\d+)(?:\/([a-z]+))?$/.exec(value);
  if (group) {
    const screen = SCREENS.find((s) => s === group[2]) ?? 'home';
    return { kind: 'group', groupId: Number(group[1]), screen };
  }
  return { kind: 'none' };
}

export function groupHref(groupId: number, screen: Screen = 'home'): string {
  return `#/g/${groupId}/${screen}`;
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
