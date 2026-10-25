import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { unreadCount } from '../activity';
import { ApiError, type Api, type Me, type SuggestedPayment } from '../api';
import { useInstall } from '../install';
import { groupHref, type Route, type Screen } from '../route';
import { Icon, type IconName } from '../ui/Icon';
import { Avatar, GroupMark, Sheet } from '../ui/parts';
import { useToast } from '../ui/toast';
import { AccountSheet } from './AccountSheet';
import { Activity } from './Activity';
import { Bank } from './Bank';
import { GroupProvider, useGroupData, type Draft, type GroupContextValue } from './context';
import { ExpenseSheet } from './ExpenseSheet';
import { ExpenseDetail, Expenses } from './Expenses';
import { GroupPage } from './GroupPage';
import { Home, People } from './Home';
import { Insights } from './Insights';
import { SettleSheet } from './SettleSheet';

type SheetState =
  | { kind: 'expense'; draft: Draft }
  | { kind: 'detail'; expenseId: number }
  | { kind: 'settle'; debt?: SuggestedPayment }
  | { kind: 'groups' }
  | { kind: 'account' }
  | null;

const SIDE_NAV: { screen: Screen; label: string; icon: IconName }[] = [
  { screen: 'home', label: 'Home', icon: 'home' },
  { screen: 'expenses', label: 'History', icon: 'receipt' },
  { screen: 'activity', label: 'Activity', icon: 'activity' },
  { screen: 'insights', label: 'Insights', icon: 'insights' },
  { screen: 'bank', label: 'Bank import', icon: 'bank' },
  { screen: 'group', label: 'Group', icon: 'group' },
];

function readSeen(groupId: number): string | null {
  try {
    return localStorage.getItem(`splitledger.seen.${groupId}`);
  } catch {
    return null;
  }
}

function writeSeen(groupId: number, at: string) {
  try {
    localStorage.setItem(`splitledger.seen.${groupId}`, at);
  } catch {
    // Without storage the dot simply shows again next time.
  }
}

/** Signed in: loads the account, picks the group from the URL (or the first one) and shows it. */
export function SignedIn({ api, route, navigate, onSignOut }: {
  api: Api; route: Route; navigate: (hash: string, replace?: boolean) => void; onSignOut: (everywhere: boolean) => void;
}) {
  const toast = useToast();
  const [me, setMe] = useState<Me | null>(null);

  const loadMe = useCallback(async () => {
    try {
      setMe(await api.me());
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) onSignOut(false);
      else toast((err as Error).message, 'error');
    }
  }, [api, onSignOut, toast]);

  useEffect(() => { void loadMe(); }, [loadMe]);

  const routed = route.kind === 'group' && me?.groups.some((g) => g.groupId === route.groupId) ? route : null;
  const membership = me?.groups.find((g) => g.groupId === (routed?.groupId ?? me.groups[0]?.groupId));

  useEffect(() => {
    if (me && membership && !routed) navigate(groupHref(membership.groupId), true);
  }, [me, membership, routed, navigate]);

  if (!me) return <div className="auth"><p className="muted">Loading…</p></div>;
  if (!membership) {
    return <NoGroup api={api} me={me} onCreated={(id) => { void loadMe(); navigate(groupHref(id)); }} onSignOut={onSignOut} />;
  }
  return (
    <GroupApp key={membership.groupId} api={api} me={me} groupId={membership.groupId} groupName={membership.groupName}
      myMemberId={membership.memberId} screen={routed?.screen ?? 'home'} navigate={navigate}
      onMeChanged={() => void loadMe()} onSignOut={onSignOut} />
  );
}

function GroupApp({ api, me, groupId, groupName, myMemberId, screen, navigate, onMeChanged, onSignOut }: {
  api: Api; me: Me; groupId: number; groupName: string; myMemberId: number; screen: Screen;
  navigate: (hash: string, replace?: boolean) => void; onMeChanged: () => void; onSignOut: (everywhere: boolean) => void;
}) {
  const toast = useToast();
  const install = useInstall();
  const onError = useCallback((message: string) => toast(message, 'error'), [toast]);
  const { data, loaded, version, reload, nameOf } = useGroupData(api, groupId, onError);
  const [sheet, setSheet] = useState<SheetState>(null);
  const [seen, setSeen] = useState(() => readSeen(groupId));
  const [installDismissed, setInstallDismissed] = useState(() => readSeen(-1) !== null);
  const closeSheet = useCallback(() => setSheet(null), []);

  const run = useCallback(async (action: () => Promise<unknown>, success?: string) => {
    try {
      await action();
      await reload();
      if (success) toast(success);
      return true;
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) onSignOut(false);
      toast((err as Error).message, 'error');
      return false;
    }
  }, [reload, toast, onSignOut]);

  const value: GroupContextValue = useMemo(() => ({
    api, groupId, groupName, myMemberId, data, loaded, version, reload, run, nameOf,
    go: (s: Screen) => navigate(groupHref(groupId, s)),
    openExpense: (draft: Draft) => setSheet({ kind: 'expense', draft }),
    openExpenseDetail: (expenseId: number) => setSheet({ kind: 'detail', expenseId }),
    openSettle: (debt?: SuggestedPayment) => setSheet({ kind: 'settle', debt }),
  }), [api, groupId, groupName, myMemberId, data, loaded, version, reload, run, nameOf, navigate]);

  const markSeen = useCallback(() => {
    const newest = data.activity[0]?.at;
    if (newest && newest !== seen) {
      writeSeen(groupId, newest);
      setSeen(newest);
    }
  }, [data.activity, groupId, seen]);
  const unread = screen === 'activity' ? 0 : unreadCount(data.activity, seen, myMemberId);
  const myName = me.groups.find((g) => g.groupId === groupId)?.displayName ?? me.displayName;

  const banners: ReactNode = (
    <>
      {!me.hasPassword && (
        <div className="banner">
          <div className="grow"><strong>Set a username and password</strong><p className="small muted">Then you can sign in on any device without your old token.</p></div>
          <button type="button" className="btn btn-primary btn-small" onClick={() => setSheet({ kind: 'account' })}>Set up</button>
        </div>
      )}
      {install.kind === 'prompt' && !installDismissed && (
        <div className="banner">
          <div className="grow"><strong>Install SplitLedger</strong><p className="small muted">Open it from your home screen like any other app.</p></div>
          <button type="button" className="btn btn-primary btn-small" onClick={() => void install.install()}>Install</button>
          <button type="button" className="close" aria-label="Not now" onClick={() => { writeSeen(-1, 'dismissed'); setInstallDismissed(true); }}>
            <Icon name="close" small />
          </button>
        </div>
      )}
    </>
  );

  const navLink = (s: Screen, label: string, icon: IconName, className: string) => (
    <a key={s} href={groupHref(groupId, s)} className={className} aria-current={screen === s ? 'page' : undefined}>
      <Icon name={icon} />
      <span>{label}</span>
      {s === 'activity' && unread > 0 && <span className="nav-dot" aria-label={`${unread} new`} />}
      {s === 'bank' && data.candidates.length > 0 && className !== 'nav-item' && <span className="pill quiet">{data.candidates.length}</span>}
    </a>
  );

  return (
    <GroupProvider value={value}>
      <div className="app">
        <aside className="sidebar">
          <span className="auth-brand"><img className="brand-icon" src="/icons/icon.svg" alt="" />SplitLedger</span>
          <button type="button" className="switcher" onClick={() => setSheet({ kind: 'groups' })} aria-label="Switch group">
            <GroupMark name={groupName} /><span className="name">{groupName}</span><Icon name="down" small />
          </button>
          <button type="button" className="btn btn-primary btn-block" onClick={() => setSheet({ kind: 'expense', draft: { kind: 'new' } })}>
            <Icon name="plus" small />Add expense
          </button>
          <nav className="side-nav" aria-label="Group">{SIDE_NAV.map((n) => navLink(n.screen, n.label, n.icon, ''))}</nav>
          <button type="button" className="switcher account" onClick={() => setSheet({ kind: 'account' })}>
            <Avatar id={myMemberId} name={myName} size="small" /><span className="name">{myName}</span>
          </button>
        </aside>

        <header className="topbar">
          <button type="button" className="switcher" onClick={() => setSheet({ kind: 'groups' })} aria-label="Switch group">
            <GroupMark name={groupName} /><span className="name">{groupName}</span><Icon name="down" small />
          </button>
          <button type="button" aria-label="Your account" onClick={() => setSheet({ kind: 'account' })}>
            <Avatar id={myMemberId} name={myName} />
          </button>
        </header>

        <main className="main">
          {screen === 'home' && <Home banners={banners} />}
          {screen === 'expenses' && <Expenses />}
          {screen === 'activity' && <Activity onSeen={markSeen} />}
          {screen === 'insights' && <Insights />}
          {screen === 'bank' && <Bank />}
          {screen === 'group' && <GroupPage />}
        </main>

        <aside className="rail" aria-label="People"><People /></aside>

        <nav className="bottom-nav" aria-label="Group">
          {navLink('home', 'Home', 'home', 'nav-item')}
          {navLink('activity', 'Activity', 'activity', 'nav-item')}
          <button type="button" className="fab" aria-label="Add expense" onClick={() => setSheet({ kind: 'expense', draft: { kind: 'new' } })}>
            <Icon name="plus" />
          </button>
          {navLink('insights', 'Insights', 'insights', 'nav-item')}
          {navLink('group', 'Group', 'group', 'nav-item')}
        </nav>
      </div>

      {sheet?.kind === 'expense' && <ExpenseSheet draft={sheet.draft} onClose={closeSheet} />}
      {sheet?.kind === 'detail' && <ExpenseDetail expenseId={sheet.expenseId} onClose={closeSheet} />}
      {sheet?.kind === 'settle' && <SettleSheet initial={sheet.debt} onClose={closeSheet} />}
      {sheet?.kind === 'groups' && (
        <GroupsSheet api={api} me={me} current={groupId} onClose={closeSheet}
          onPick={(id) => { closeSheet(); navigate(groupHref(id)); }}
          onCreated={(id) => { closeSheet(); onMeChanged(); navigate(groupHref(id)); }} />
      )}
      {sheet?.kind === 'account' && (
        <AccountSheet api={api} me={me} avatarId={myMemberId} onChanged={onMeChanged} onSignOut={onSignOut} onClose={closeSheet} />
      )}
    </GroupProvider>
  );
}

function NewGroupForm({ api, onCreated }: { api: Api; onCreated: (groupId: number) => void }) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      onCreated((await api.createGroup(name.trim())).id);
    } catch (err) {
      toast((err as Error).message, 'error');
    } finally {
      setBusy(false);
    }
  }
  return (
    <form onSubmit={submit} style={{ display: 'flex', gap: 8 }}>
      <input aria-label="Group name" placeholder="e.g. WG Kessenich, Lisbon trip" value={name} onChange={(e) => setName(e.target.value)}
        required maxLength={100} />
      <button className="btn btn-primary" disabled={busy}>Create</button>
    </form>
  );
}

function GroupsSheet({ api, me, current, onPick, onCreated, onClose }: {
  api: Api; me: Me; current: number; onPick: (id: number) => void; onCreated: (id: number) => void; onClose: () => void;
}) {
  return (
    <Sheet title="Your groups" onClose={onClose}>
      <div className="stack">
        <ul className="list">
          {me.groups.map((g) => (
            <li key={g.groupId}>
              <button type="button" className="row clickable" aria-current={g.groupId === current ? 'true' : undefined} onClick={() => onPick(g.groupId)}>
                <GroupMark name={g.groupName} />
                <span className="grow"><span className="title">{g.groupName}</span><span className="sub">You are {g.displayName}</span></span>
                {g.groupId === current && <Icon name="check" small />}
              </button>
            </li>
          ))}
        </ul>
        <h3>New group</h3>
        <NewGroupForm api={api} onCreated={onCreated} />
        <p className="hint">To join a friend’s group, open the invite link they sent you.</p>
      </div>
    </Sheet>
  );
}

function NoGroup({ api, me, onCreated, onSignOut }: { api: Api; me: Me; onCreated: (id: number) => void; onSignOut: (everywhere: boolean) => void }) {
  return (
    <main className="auth">
      <div className="auth-box">
        <span className="auth-brand"><img className="brand-icon" src="/icons/icon.svg" alt="" />SplitLedger</span>
        <div className="auth-panel">
          <h1>Welcome, {me.displayName}</h1>
          <p className="muted">Start a group for your flat or your next trip. Then invite your friends with a link.</p>
          <NewGroupForm api={api} onCreated={onCreated} />
          <p className="hint">Waiting to join a friend’s group? Open the invite link they sent you.</p>
        </div>
        <div className="auth-alt"><button type="button" className="btn btn-link" onClick={() => onSignOut(false)}>Sign out</button></div>
      </div>
    </main>
  );
}
