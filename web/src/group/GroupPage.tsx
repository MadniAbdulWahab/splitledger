import { useState, type FormEvent, type ReactNode } from 'react';
import { formatIban } from '../format';
import { linkFor } from '../route';
import { Icon } from '../ui/Icon';
import { Avatar, Field, Sheet } from '../ui/parts';
import { useToast } from '../ui/toast';
import { useGroup } from './context';

interface LinkToSend { kind: 'join' | 'reset'; name: string; url: string }

/**
 * Invite and password reset links for a member, shared by the Group page and a person's page:
 * newLink makes one and shows it in the send sheet ({@code sheet}, to render on the page).
 */
export function useMemberLinks(): {
  newLink: (memberId: number, memberName: string, kind: 'join' | 'reset') => Promise<void>;
  show: (link: LinkToSend) => void;
  sheet: ReactNode;
} {
  const { api, groupId, reload } = useGroup();
  const toast = useToast();
  const [link, setLink] = useState<LinkToSend | null>(null);

  async function newLink(memberId: number, memberName: string, kind: 'join' | 'reset') {
    try {
      const url = kind === 'join'
        ? linkFor('join', (await api.reissueInvitation(groupId, memberId)).inviteCode)
        : linkFor('reset', (await api.createPasswordReset(groupId, memberId)).resetCode);
      setLink({ kind, name: memberName, url });
      if (kind === 'reset') await reload(); // the reset shows up in the activity
    } catch (err) {
      toast((err as Error).message, 'error');
    }
  }

  return { newLink, show: setLink, sheet: link && <SendLink link={link} onClose={() => setLink(null)} /> };
}

/** Members, invite links, password reset links and your own IBAN. */
export function GroupPage() {
  const { api, groupId, groupName, data, myMemberId, run, reload, openMember } = useGroup();
  const toast = useToast();
  const links = useMemberLinks();
  const [name, setName] = useState('');
  const [iban, setIban] = useState('');
  const me = data.members.find((m) => m.id === myMemberId);

  async function invite(e: FormEvent) {
    e.preventDefault();
    const friend = name.trim();
    try {
      const added = await api.addMember(groupId, friend);
      setName('');
      await reload();
      links.show({ kind: 'join', name: friend, url: linkFor('join', added.inviteCode) });
    } catch (err) {
      toast((err as Error).message, 'error');
    }
  }

  return (
    <>
      <h1>{groupName}</h1>

      <section className="section">
        <div className="section-head"><h2>Members</h2><span className="muted small">{data.members.length}</span></div>
        <ul className="list">
          {data.members.map((m) => (
            <li key={m.id} className="row">
              <button type="button" className="row-link" onClick={() => openMember(m.id)}>
                <Avatar id={m.id} name={m.displayName} />
                <span className="grow">
                  <span className="title">{m.displayName}{m.id === myMemberId ? ' (you)' : ''}</span>
                  <span className="sub">{!m.joined ? 'Invited, not joined yet' : m.iban ? formatIban(m.iban) : 'No IBAN yet'}</span>
                </span>
              </button>
              {!m.joined && (
                <button type="button" className="btn btn-quiet btn-small" onClick={() => void links.newLink(m.id, m.displayName, 'join')}>
                  <Icon name="share" small />Invite link
                </button>
              )}
              {m.joined && m.id !== myMemberId && (
                <button type="button" className="btn btn-quiet btn-small" title={`Make a password reset link for ${m.displayName}`}
                  onClick={() => {
                    if (window.confirm(`Make a one-time password reset link for ${m.displayName}? Everyone in the group sees that you made it.`)) {
                      void links.newLink(m.id, m.displayName, 'reset');
                    }
                  }}>
                  <Icon name="key" small /><span className="visually-hidden">Reset password link for {m.displayName}</span>
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>

      <form className="section" onSubmit={invite}>
        <h2>Invite a friend</h2>
        <p className="muted small">Add them by name, then send them their link. They pick a username and password and are in.</p>
        <div style={{ display: 'flex', gap: 8 }}>
          <input aria-label="Friend's name" placeholder="e.g. Ben" value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} />
          <button className="btn btn-primary">Invite</button>
        </div>
      </form>

      <form className="section" onSubmit={(e) => {
        e.preventDefault();
        void run(() => api.setMyIban(groupId, iban.trim()), 'IBAN saved').then((ok) => { if (ok) setIban(''); });
      }}>
        <h2>Your IBAN</h2>
        <p className="muted small">The others see it when they pay you, and it goes into the GiroCode.</p>
        <Field label="IBAN" hint={me?.iban ? `Saved: ${formatIban(me.iban)}` : undefined}>
          <div style={{ display: 'flex', gap: 8 }}>
            <input value={iban} onChange={(e) => setIban(e.target.value)} placeholder={me?.iban ? formatIban(me.iban) : 'DE…'} autoComplete="off" />
            <button className="btn btn-quiet">Save</button>
          </div>
        </Field>
      </form>

      {links.sheet}
    </>
  );
}

/** The link a friend needs, with the phone's share sheet, WhatsApp, or copy. */
function SendLink({ link, onClose }: { link: LinkToSend; onClose: () => void }) {
  const toast = useToast();
  const { groupName } = useGroup();
  const text = link.kind === 'join'
    ? `Join our group "${groupName}" on SplitLedger:`
    : `Your SplitLedger password reset link (works once, for 24 hours):`;

  async function share() {
    if (navigator.share) {
      try {
        await navigator.share({ title: 'SplitLedger', text, url: link.url });
        return;
      } catch (err) {
        if ((err as Error).name === 'AbortError') return;
      }
    }
    await copy();
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(link.url);
      toast('Link copied');
    } catch {
      toast('Copying is blocked here; select the link and copy it by hand.', 'error');
    }
  }

  return (
    <Sheet title={link.kind === 'join' ? `Invite ${link.name}` : `Reset link for ${link.name}`} onClose={onClose}>
      <div className="stack">
        <p className="muted">
          {link.kind === 'join'
            ? `Send ${link.name} this link. It works once; if it gets lost, make a new one from the member list.`
            : `Send ${link.name} this link. It works once and for 24 hours, and signs out all of their devices.`}
        </p>
        <input readOnly value={link.url} aria-label="Link" onFocus={(e) => e.target.select()} />
        <button type="button" className="btn btn-primary btn-block" onClick={() => void share()}><Icon name="share" small />Share link</button>
        <div className="form-row">
          <a className="btn btn-quiet" href={`https://wa.me/?text=${encodeURIComponent(`${text} ${link.url}`)}`} target="_blank" rel="noreferrer">WhatsApp</a>
          <button type="button" className="btn btn-quiet" onClick={() => void copy()}><Icon name="copy" small />Copy</button>
        </div>
      </div>
    </Sheet>
  );
}
