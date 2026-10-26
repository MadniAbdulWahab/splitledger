import type { Category } from './categories';
import { newIdempotencyKey } from './ids';

// Thin client for the SplitLedger API. Every call sends the user's token; errors arrive as
// RFC 9457 problem details and are turned into an ApiError with the server's explanation.

export interface Membership { groupId: number; groupName: string; memberId: number; displayName: string }
export interface Me { id: number; displayName: string; username: string | null; hasPassword: boolean; groups: Membership[] }
export interface Member { id: number; displayName: string; iban: string | null; bic: string | null; joined: boolean }
export interface Balance { memberId: number; displayName: string; balanceCents: number }
export interface SuggestedPayment {
  fromMemberId: number; fromName: string; toMemberId: number; toName: string;
  toIban: string | null; toBic: string | null; amountCents: number;
}
export type SplitMode = 'EQUAL' | 'EXACT' | 'SHARES';
/** A history row. change/changedBy say who deleted or edited it; replacesExpenseId marks a new version. */
export interface ExpenseHit {
  id: number; payerId: number; amountCents: number; description: string; notes: string | null;
  expenseDate: string; reversed: boolean; category: Category | null;
  change: 'DELETED' | 'EDITED' | null; changedBy: number | null; changedAt: string | null; replacesExpenseId: number | null;
}
export interface Expense {
  id: number; payerId: number; amountCents: number; description: string; notes: string | null;
  expenseDate: string; splitMode: SplitMode; reversed: boolean; category: Category | null;
  shares: { memberId: number; amountCents: number; shareUnits: number | null }[];
}
/** What the expense form sends: a new expense, an edit, or a shared bank payment. */
export interface ExpenseBody {
  payerId: number; amountCents: number; description: string; notes: string | null; expenseDate: string;
  splitMode: SplitMode; participants: { memberId: number; value?: number }[]; category: Category | null;
}
export interface BankTransaction {
  id: number; bookingDate: string; amountCents: number; counterpartyName: string | null;
  purpose: string | null; status: string;
}
export interface PaymentRecord {
  id: number; fromMemberId: number; toMemberId: number; amountCents: number; note: string | null; createdAt: string;
  reversed: boolean; undoneBy: number | null; undoneAt: string | null;
}
export interface ImportReport {
  rowsRead: number; newTransactions: number; duplicates: number; skippedCount: number;
  skipped: { line: number; reason: string }[];
}
export type ActivityKind =
  | 'GROUP_CREATED' | 'EXPENSE_ADDED' | 'EXPENSE_EDITED' | 'EXPENSE_DELETED' | 'PAYMENT_RECORDED' | 'PAYMENT_UNDONE'
  | 'MEMBER_ADDED' | 'MEMBER_JOINED' | 'PASSWORD_RESET';
/** One entry of the group's timeline; which fields are set depends on the kind. */
export interface ActivityItem {
  id: string; kind: ActivityKind; at: string; actorMemberId: number | null; expenseId: number | null;
  paymentId: number | null; memberId: number | null; description: string | null; amountCents: number | null;
  category: Category | null; fromMemberId: number | null; toMemberId: number | null;
  /** What this entry did to the reader's balance; null when it moved no money for them. */
  myChangeCents: number | null;
}
export interface Insights {
  from: string; to: string; totalCents: number; myShareCents: number;
  months: { month: string; totalCents: number; myShareCents: number }[];
  categories: { category: Category; totalCents: number; myShareCents: number; expenses: number }[];
  payers: { memberId: number; paidCents: number }[];
}
export interface InvitationPreview { groupName: string; memberName: string; invitedByName: string | null }
export interface ResetPreview { displayName: string; username: string | null }

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export class Api {
  constructor(private readonly token: string) {}

  me = () => this.json<Me>('GET', '/me');
  logout = () => this.json('POST', '/logout');
  logoutEverywhere = () => this.json('POST', '/me/sessions/revocation');
  changeUsername = (username: string) => this.json('PUT', '/me/username', { username });
  /** currentPassword is only needed once a password is set. */
  changePassword = (currentPassword: string | null, newPassword: string) =>
    this.json('PUT', '/me/password', { currentPassword, newPassword });

  createGroup = (name: string) => this.json<{ id: number }>('POST', '/groups', { name });
  acceptInvitation = (code: string) => this.json<Membership>('POST', '/invitations/accept', { code });
  members = (g: number) => this.json<Member[]>('GET', `/groups/${g}/members`);
  addMember = (g: number, displayName: string) =>
    this.json<{ member: Member; inviteCode: string }>('POST', `/groups/${g}/members`, { displayName });
  /** A fresh link for a member who has not joined; the previous link stops working. */
  reissueInvitation = (g: number, memberId: number) =>
    this.json<{ memberId: number; inviteCode: string }>('POST', `/groups/${g}/members/${memberId}/invitation`);
  createPasswordReset = (g: number, memberId: number) =>
    this.json<{ memberId: number; resetCode: string; expiresAt: string }>('POST', `/groups/${g}/members/${memberId}/password-reset`);
  setMyIban = (g: number, iban: string) =>
    this.json<Member>('PUT', `/groups/${g}/members/me/bank-details`, { iban: iban || null });
  balances = (g: number) => this.json<{ balances: Balance[] }>('GET', `/groups/${g}/balances`);
  /** The fewest transfers that settle the whole group. */
  settlement = (g: number) => this.json<{ payments: SuggestedPayment[] }>('GET', `/groups/${g}/settlement`);
  /** Who owes whom, pair by pair. */
  debts = (g: number) => this.json<{ debts: SuggestedPayment[] }>('GET', `/groups/${g}/debts`);

  // Every create sends a fresh Idempotency-Key, so a retried click never books twice.
  addExpense = (g: number, body: ExpenseBody) =>
    this.json('POST', `/groups/${g}/expenses`, body, { 'Idempotency-Key': newIdempotencyKey() });
  getExpense = (g: number, id: number) => this.json<Expense>('GET', `/groups/${g}/expenses/${id}`);
  /** The old version stays in the history, crossed out; the body becomes the new version. */
  editExpense = (g: number, id: number, body: ExpenseBody) =>
    this.json('POST', `/groups/${g}/expenses/${id}/correction`, body, { 'Idempotency-Key': newIdempotencyKey() });
  /** Reversing entries cancel it; the history keeps it crossed out with who deleted it. */
  deleteExpense = (g: number, id: number) => this.json('POST', `/groups/${g}/expenses/${id}/reversal`);
  undoPayment = (g: number, id: number) => this.json('POST', `/groups/${g}/payments/${id}/reversal`);
  /** Recorded settle-up payments, newest first. */
  payments = (g: number) => this.json<PaymentRecord[]>('GET', `/groups/${g}/payments`);
  recordPayment = (g: number, p: SuggestedPayment) =>
    this.json('POST', `/groups/${g}/payments`,
      { fromMemberId: p.fromMemberId, toMemberId: p.toMemberId, amountCents: p.amountCents },
      { 'Idempotency-Key': newIdempotencyKey() });

  /** Without q: the whole history, newest first. Pass nextCursor to get the following page. */
  search = (g: number, q: string, options: { cursor?: string; category?: Category | null; limit?: number } = {}) => {
    const params = new URLSearchParams({ limit: String(options.limit ?? 20), q });
    if (options.cursor) params.set('cursor', options.cursor);
    if (options.category) params.set('category', options.category);
    return this.json<{ items: ExpenseHit[]; nextCursor: string | null }>('GET', `/groups/${g}/expenses/search?${params}`);
  };
  activity = (g: number, cursor?: string) =>
    this.json<{ items: ActivityItem[]; nextCursor: string | null }>('GET',
      `/groups/${g}/activity?limit=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
  insights = (g: number, months: number) => this.json<Insights>('GET', `/groups/${g}/insights?months=${months}`);
  candidates = (g: number) => this.json<BankTransaction[]>('GET', `/groups/${g}/bank-transactions`);
  /** Shares a bank payment with what the user chose in the form (payer, amount, date, split). */
  confirm = (g: number, id: number, body: ExpenseBody) =>
    this.json('POST', `/groups/${g}/bank-transactions/${id}/confirmation`, body);
  ignore = (g: number, id: number) => this.json('POST', `/groups/${g}/bank-transactions/${id}/ignore`);

  importStatement(g: number, file: File): Promise<ImportReport> {
    const form = new FormData();
    form.append('file', file);
    return this.send('POST', `/groups/${g}/imports`, form).then((r) => r.json());
  }

  /** The QR code as an object URL; an <img src> cannot send the Authorization header itself. */
  async giroCode(g: number, p: SuggestedPayment): Promise<string> {
    const response = await this.send('GET',
      `/groups/${g}/settlement/girocode?from=${p.fromMemberId}&to=${p.toMemberId}&amount=${p.amountCents}`);
    return URL.createObjectURL(await response.blob());
  }

  private async json<T = unknown>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
    const response = await this.send(method, path, body === undefined ? undefined : JSON.stringify(body),
      body === undefined ? headers : { 'Content-Type': 'application/json', ...headers });
    return readJson<T>(response);
  }

  private async send(method: string, path: string, body?: BodyInit, headers: Record<string, string> = {}): Promise<Response> {
    const response = await fetch(path, {
      method,
      body,
      headers: { Authorization: `Bearer ${this.token}`, ...headers },
    });
    if (!response.ok) {
      throw new ApiError(response.status, await problemText(response));
    }
    return response;
  }
}

/* ---------- Before signing in: no token yet ---------- */

/** Signs in on this device; returns the session token. */
export const login = (username: string, password: string) =>
  publicPost<{ token: string }>('/sessions', { username, password }).then((r) => r.token);

/** A new account for someone starting a group; needs the host's registration code if one is set. */
export const registerAccount = (displayName: string, username: string, password: string, registrationCode: string) =>
  publicPost<{ token: string }>('/users', { displayName, username, password },
    registrationCode ? { 'X-Registration-Code': registrationCode } : {}).then((r) => r.token);

/** Whether signing up needs the host's code; without one, anyone can create an account. */
export async function registrationPolicy(): Promise<{ codeRequired: boolean }> {
  const response = await fetch('/users/registration');
  if (!response.ok) throw new ApiError(response.status, await problemText(response));
  return readJson(response);
}

export const previewInvitation = (code: string) => publicPost<InvitationPreview>('/invitations/preview', { code });

/** Opening an invite link without an account: creates it and joins the group. */
export const signUpWithInvitation = (code: string, username: string, password: string) =>
  publicPost<{ token: string }>('/invitations/signup', { code, username, password }).then((r) => r.token);

export const previewReset = (code: string) => publicPost<ResetPreview>('/password-resets/preview', { code });

export const redeemReset = (code: string, username: string | null, newPassword: string) =>
  publicPost<{ token: string }>('/password-resets', { code, username, newPassword }).then((r) => r.token);

async function publicPost<T>(path: string, body: unknown, headers: Record<string, string> = {}): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new ApiError(response.status, await problemText(response));
  }
  return readJson<T>(response);
}

async function readJson<T>(response: Response): Promise<T> {
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function problemText(response: Response): Promise<string> {
  try {
    const problem = await response.json();
    const fields = (problem.errors ?? []).map((e: { field: string; message: string }) => `${e.field} ${e.message}`);
    return [problem.detail ?? problem.title, ...fields].filter(Boolean).join('; ');
  } catch {
    return `${response.status} ${response.statusText}`;
  }
}
