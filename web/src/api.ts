import { newIdempotencyKey } from './ids';

// Thin client for the SplitLedger API. Every call sends the user's token; errors arrive as
// RFC 9457 problem details and are turned into an ApiError with the server's explanation.

export interface Membership { groupId: number; groupName: string; memberId: number; displayName: string }
export interface Me { id: number; displayName: string; groups: Membership[] }
export interface Member { id: number; displayName: string; iban: string | null; bic: string | null; joined: boolean }
export interface Balance { memberId: number; displayName: string; balanceCents: number }
export interface SuggestedPayment {
  fromMemberId: number; fromName: string; toMemberId: number; toName: string;
  toIban: string | null; toBic: string | null; amountCents: number;
}
export interface ExpenseHit {
  id: number; payerId: number; amountCents: number; description: string; notes: string | null;
  expenseDate: string; reversed: boolean;
}
export interface BankTransaction {
  id: number; bookingDate: string; amountCents: number; counterpartyName: string | null;
  purpose: string | null; status: string;
}
export interface ImportReport {
  rowsRead: number; newTransactions: number; duplicates: number; skippedCount: number;
  skipped: { line: number; reason: string }[];
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

export class Api {
  constructor(private readonly token: string) {}

  me = () => this.json<Me>('GET', '/me');
  createGroup = (name: string) => this.json<{ id: number }>('POST', '/groups', { name });
  acceptInvitation = (code: string) => this.json<Membership>('POST', '/invitations/accept', { code });
  members = (g: number) => this.json<Member[]>('GET', `/groups/${g}/members`);
  addMember = (g: number, displayName: string) =>
    this.json<{ member: Member; inviteCode: string }>('POST', `/groups/${g}/members`, { displayName });
  setMyIban = (g: number, iban: string) =>
    this.json<Member>('PUT', `/groups/${g}/members/me/bank-details`, { iban: iban || null });
  balances = (g: number) => this.json<{ balances: Balance[] }>('GET', `/groups/${g}/balances`);
  settlement = (g: number) => this.json<{ payments: SuggestedPayment[] }>('GET', `/groups/${g}/settlement`);

  // Every create sends a fresh Idempotency-Key, so a retried click never books twice.
  addExpense = (g: number, body: unknown) =>
    this.json('POST', `/groups/${g}/expenses`, body, { 'Idempotency-Key': newIdempotencyKey() });
  recordPayment = (g: number, p: SuggestedPayment) =>
    this.json('POST', `/groups/${g}/payments`,
      { fromMemberId: p.fromMemberId, toMemberId: p.toMemberId, amountCents: p.amountCents },
      { 'Idempotency-Key': newIdempotencyKey() });

  search = (g: number, q: string) =>
    this.json<{ items: ExpenseHit[] }>('GET', `/groups/${g}/expenses/search?q=${encodeURIComponent(q)}&limit=20`);
  candidates = (g: number) => this.json<BankTransaction[]>('GET', `/groups/${g}/bank-transactions`);
  confirm = (g: number, id: number, memberIds: number[]) =>
    this.json('POST', `/groups/${g}/bank-transactions/${id}/confirmation`,
      { splitMode: 'EQUAL', participants: memberIds.map((memberId) => ({ memberId })) });
  ignore = (g: number, id: number) => this.json('POST', `/groups/${g}/bank-transactions/${id}/ignore`);

  importStatement(g: number, file: File): Promise<ImportReport> {
    const form = new FormData();
    form.append('file', file);
    return this.send('POST', `/groups/${g}/imports`, form).then((r) => r.json());
  }

  /** The QR code as an object URL; an <img src> cannot send the Authorization header itself. */
  async giroCode(g: number, p: SuggestedPayment): Promise<string> {
    const response = await this.send('GET', `/groups/${g}/settlement/girocode?from=${p.fromMemberId}&to=${p.toMemberId}`);
    return URL.createObjectURL(await response.blob());
  }

  private async json<T = unknown>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
    const response = await this.send(method, path, body === undefined ? undefined : JSON.stringify(body),
      body === undefined ? headers : { 'Content-Type': 'application/json', ...headers });
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
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

export async function register(displayName: string, registrationCode: string): Promise<string> {
  const response = await fetch('/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(registrationCode ? { 'X-Registration-Code': registrationCode } : {}) },
    body: JSON.stringify({ displayName }),
  });
  if (!response.ok) {
    throw new ApiError(response.status, await problemText(response));
  }
  return (await response.json()).token;
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
