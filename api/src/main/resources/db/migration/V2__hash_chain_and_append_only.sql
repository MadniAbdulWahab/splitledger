-- Tamper-evident ledger: per-group hash chain, reversals, and a database-level append-only guard.

-- The new columns are NOT NULL without a default, so existing entries would need their hashes
-- backfilled. No deployed database has ledger data yet (only local development databases from
-- day 1), so this migration refuses to run on a non-empty ledger instead of carrying a backfill.
do $$
begin
    if exists (select 1 from ledger_entry) then
        raise exception 'V2 needs an empty ledger_entry table. Reset the local database: docker compose down -v';
    end if;
end
$$;

-- seq:           position in the group's chain, 1, 2, 3, ... without gaps (a deleted entry leaves a gap)
-- previous_hash: hash of the entry before; 64 zeros for the first entry of a group
-- hash:          SHA-256 of previous_hash and the entry's canonical content (see LedgerHasher)
alter table ledger_entry
    add column seq           bigint      not null,
    add column previous_hash varchar(64) not null,
    add column hash          varchar(64) not null,
    add constraint ledger_entry_seq_positive check (seq > 0),
    add constraint ledger_entry_group_seq_unique unique (group_id, seq);

-- A REVERSAL entry references the ledger entry it cancels. Each entry can be reversed once.
create unique index ledger_entry_reversed_once on ledger_entry (reference_id) where kind = 'REVERSAL';

-- Rejects every UPDATE, DELETE and TRUNCATE, for every role including the table owner.
-- Only a superuser (or the owner after ALTER TABLE ... DISABLE TRIGGER) can get around it,
-- and the hash chain makes such a change visible afterwards.
create function reject_change_to_append_only_table() returns trigger
    language plpgsql as
$$
begin
    raise exception '% is append-only: % is not allowed, write a reversal instead', tg_table_name, tg_op;
end
$$;

create trigger ledger_entry_append_only
    before update or delete on ledger_entry
    for each row execute function reject_change_to_append_only_table();
create trigger ledger_entry_no_truncate
    before truncate on ledger_entry
    for each statement execute function reject_change_to_append_only_table();

-- Expenses and shares are immutable too: a wrong expense is reversed, not edited.
create trigger expense_append_only
    before update or delete on expense
    for each row execute function reject_change_to_append_only_table();
create trigger expense_no_truncate
    before truncate on expense
    for each statement execute function reject_change_to_append_only_table();
create trigger expense_share_append_only
    before update or delete on expense_share
    for each row execute function reject_change_to_append_only_table();
create trigger expense_share_no_truncate
    before truncate on expense_share
    for each statement execute function reject_change_to_append_only_table();
