-- Bank statement import: one row per uploaded file, one row per outgoing booked transaction.
-- The uploaded file itself is never stored.

create table bank_import (
    id              bigint generated always as identity primary key,
    group_id        bigint      not null references expense_group (id),
    -- The member whose account the statement belongs to (the uploader).
    member_id       bigint      not null,
    format          varchar(30) not null,
    rows_read       integer     not null,
    new_count       integer     not null,
    duplicate_count integer     not null,
    skipped_count   integer     not null,
    created_at      timestamptz not null default now(),
    constraint bank_import_member_in_group foreign key (group_id, member_id) references member (group_id, id)
);

create table bank_transaction (
    id                bigint generated always as identity primary key,
    group_id          bigint      not null references expense_group (id),
    import_id         bigint      not null references bank_import (id),
    member_id         bigint      not null,
    -- SHA-256 over the transaction's fields plus its occurrence index among identical rows
    -- (see Fingerprints). Unique per group: re-importing the same or an overlapping file adds nothing.
    fingerprint       varchar(64) not null,
    booking_date      date        not null,
    -- Negative: money left the member's account. Only outgoing payments are stored.
    amount_cents      bigint      not null check (amount_cents < 0),
    -- Personal data; cleared when the member ignores the transaction (only fingerprint, date and
    -- amount stay, so a re-import still recognizes it).
    counterparty_name text,
    counterparty_iban varchar(34),
    purpose           text,
    status            varchar(10) not null default 'CANDIDATE'
        check (status in ('CANDIDATE', 'CONFIRMED', 'IGNORED')),
    expense_id        bigint,
    created_at        timestamptz not null default now(),
    decided_at        timestamptz,
    constraint bank_transaction_fingerprint_unique unique (group_id, fingerprint),
    constraint bank_transaction_member_in_group foreign key (group_id, member_id) references member (group_id, id),
    constraint bank_transaction_expense_in_group foreign key (group_id, expense_id) references expense (group_id, id),
    constraint bank_transaction_confirmed_has_expense check ((status = 'CONFIRMED') = (expense_id is not null))
);

create index bank_transaction_member_status_idx on bank_transaction (group_id, member_id, status, booking_date desc);

-- The purpose text of a confirmed bank payment becomes part of the expense and of its search document.
alter table expense add column bank_purpose text;

-- PostgreSQL 16 cannot change a generated column's expression, so drop and recreate it (with its
-- index). The expense trigger does not fire: this rewrites the table, it does not UPDATE rows.
drop index expense_search_idx;
alter table expense drop column search_document;
alter table expense
    add column search_document tsvector generated always as (
        setweight(to_tsvector('german', description), 'A') ||
        setweight(to_tsvector('simple', description), 'A') ||
        setweight(to_tsvector('german', coalesce(notes, '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(notes, '')), 'B') ||
        setweight(to_tsvector('german', coalesce(bank_purpose, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(bank_purpose, '')), 'C')
    ) stored;
create index expense_search_idx on expense using gin (search_document);
