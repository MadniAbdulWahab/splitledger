import { describe, expect, it } from 'vitest';
import { groupHref, inviteCodeFrom, linkFor, parseHash } from './route';
import { initials, memberColour, NOTE_COLOURS } from './avatar';
import { formatShortDate, joinNames, relativeTime } from './format';

describe('parseHash', () => {
  it('reads invite and reset links', () => {
    expect(parseHash('#join=inv_Ab-9_x')).toEqual({ kind: 'join', code: 'inv_Ab-9_x' });
    expect(parseHash('#reset=rst_xyz')).toEqual({ kind: 'reset', code: 'rst_xyz' });
  });

  it('reads group screens and falls back to home', () => {
    expect(parseHash('#/g/12/insights')).toEqual({ kind: 'group', groupId: 12, screen: 'insights' });
    expect(parseHash('#/g/12')).toEqual({ kind: 'group', groupId: 12, screen: 'home' });
    expect(parseHash('#/g/12/nonsense')).toEqual({ kind: 'group', groupId: 12, screen: 'home' });
    expect(parseHash('#/g/12/expenses')).toEqual({ kind: 'group', groupId: 12, screen: 'activity' });
  });

  it('ignores anything else', () => {
    expect(parseHash('')).toEqual({ kind: 'none' });
    expect(parseHash('#join=<script>')).toEqual({ kind: 'none' });
    expect(parseHash('#/g/abc')).toEqual({ kind: 'none' });
  });

  it('round-trips the links it builds', () => {
    expect(parseHash(groupHref(7, 'bank'))).toEqual({ kind: 'group', groupId: 7, screen: 'bank' });
    const link = linkFor('join', 'inv_abc', 'https://example.org');
    expect(link).toBe('https://example.org/#join=inv_abc');
    expect(parseHash(new URL(link).hash)).toEqual({ kind: 'join', code: 'inv_abc' });
  });
});

describe('inviteCodeFrom', () => {
  it('takes the code from a pasted link or the code alone', () => {
    expect(inviteCodeFrom('https://splitledger.example.org/#join=inv_Ab-9_x')).toBe('inv_Ab-9_x');
    expect(inviteCodeFrom('  inv_Ab-9_x \n')).toBe('inv_Ab-9_x');
  });

  it('refuses anything else', () => {
    expect(inviteCodeFrom('https://splitledger.example.org/#reset=rst_x')).toBeNull();
    expect(inviteCodeFrom('hello')).toBeNull();
    expect(inviteCodeFrom('')).toBeNull();
  });
});

describe('avatars', () => {
  it('gives each member a stable banknote colour', () => {
    expect(memberColour(3)).toBe(memberColour(3 + NOTE_COLOURS.length));
    expect(new Set([1, 2, 3, 4, 5, 6, 7].map(memberColour)).size).toBe(7);
  });

  it('uses up to two initials', () => {
    expect(initials('Anna Beispiel')).toBe('AB');
    expect(initials('ben')).toBe('B');
    expect(initials('  ')).toBe('?');
  });
});

describe('format', () => {
  const now = new Date(2026, 9, 25, 15, 0);

  it('says how long ago something happened', () => {
    expect(relativeTime(new Date(2026, 9, 25, 14, 59, 30).toISOString(), now)).toBe('just now');
    expect(relativeTime(new Date(2026, 9, 25, 14, 20).toISOString(), now)).toBe('40 min ago');
    expect(relativeTime(new Date(2026, 9, 25, 9, 0).toISOString(), now)).toBe('6 h ago');
    expect(relativeTime(new Date(2026, 9, 24, 23, 0).toISOString(), now)).toBe('yesterday');
    expect(relativeTime(new Date(2026, 9, 21, 12, 0).toISOString(), now)).toBe('4 days ago');
    expect(relativeTime(new Date(2026, 8, 2, 12, 0).toISOString(), now)).toBe('2 Sept');
  });

  it('leaves out the year only for this year', () => {
    expect(formatShortDate('2026-03-05', now)).toBe('5 Mar');
    expect(formatShortDate('2025-03-05', now)).toBe('5 Mar 2025');
  });

  it('joins names like a sentence', () => {
    expect(joinNames(['Ben'])).toBe('Ben');
    expect(joinNames(['Ben', 'Lucy'])).toBe('Ben and Lucy');
    expect(joinNames(['Ben', 'Lucy', 'Tim'])).toBe('Ben, Lucy and Tim');
  });
});
