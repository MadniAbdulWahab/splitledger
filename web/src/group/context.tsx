import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type {
  ActivityItem, Api, Balance, BankTransaction, Expense, ExpenseHit, Member, PaymentRecord, SuggestedPayment,
} from '../api';
import type { Screen } from '../route';

/** Everything the screens of one group show; loaded together and reloaded after every change. */
export interface GroupData {
  members: Member[];
  balances: Balance[];
  /** Who owes whom, pair by pair. */
  debts: SuggestedPayment[];
  /** The fewest transfers that settle everything. */
  simplified: SuggestedPayment[];
  candidates: BankTransaction[];
  payments: PaymentRecord[];
  latest: ExpenseHit[];
  activity: ActivityItem[];
  /** Where older activity continues; null when the first page is everything. */
  activityCursor: string | null;
}

const EMPTY: GroupData = {
  members: [], balances: [], debts: [], simplified: [], candidates: [], payments: [], latest: [], activity: [], activityCursor: null,
};

/** What the expense sheet is opened with: nothing, a bank payment to share, or an expense to edit. */
export type Draft =
  | { kind: 'new' }
  | { kind: 'bank'; transaction: BankTransaction }
  | { kind: 'edit'; expense: Expense };

export interface GroupContextValue {
  api: Api;
  groupId: number;
  groupName: string;
  myMemberId: number;
  data: GroupData;
  loaded: boolean;
  /** Increases after every change, so screens with their own data (search, insights) reload too. */
  version: number;
  reload: () => Promise<void>;
  /** Runs a change, reloads, then shows the success text (or the server's error) as a toast. */
  run: (action: () => Promise<unknown>, success?: string) => Promise<boolean>;
  nameOf: (memberId: number | null | undefined) => string;
  go: (screen: Screen) => void;
  openExpense: (draft: Draft) => void;
  openExpenseDetail: (expenseId: number) => void;
  /** Edit, delete or details: what a press and hold on an expense row opens. */
  openExpenseActions: (hit: ExpenseHit) => void;
  openSettle: (debt?: SuggestedPayment) => void;
}

const GroupContext = createContext<GroupContextValue | null>(null);
export const GroupProvider = GroupContext.Provider;

export function useGroup(): GroupContextValue {
  const value = useContext(GroupContext);
  if (!value) throw new Error('useGroup outside a group');
  return value;
}

export function useGroupData(api: Api, groupId: number, onError: (message: string) => void) {
  const [data, setData] = useState<GroupData>(EMPTY);
  const [loaded, setLoaded] = useState(false);
  const [version, setVersion] = useState(0);

  const reload = useCallback(async () => {
    const [members, balances, debts, settlement, candidates, payments, latest, activity] = await Promise.all([
      api.members(groupId), api.balances(groupId), api.debts(groupId), api.settlement(groupId),
      api.candidates(groupId), api.payments(groupId), api.search(groupId, '', { limit: 5 }), api.activity(groupId),
    ]);
    setData({
      members, balances: balances.balances, debts: debts.debts, simplified: settlement.payments,
      candidates, payments, latest: latest.items, activity: activity.items, activityCursor: activity.nextCursor,
    });
    setLoaded(true);
    setVersion((v) => v + 1);
  }, [api, groupId]);

  useEffect(() => {
    setLoaded(false);
    reload().catch((err: Error) => onError(err.message));
  }, [reload, onError]);

  const nameOf = useMemo(() => {
    const names = new Map(data.members.map((m) => [m.id, m.displayName]));
    return (id: number | null | undefined) => (id == null ? 'Someone' : names.get(id) ?? 'Someone');
  }, [data.members]);

  return { data, loaded, version, reload, nameOf };
}
