-- Ledger core: groups, members, expenses, shares and the double-entry ledger.
-- "group" is a reserved word in SQL, so the table is called expense_group.

create table expense_group (
    id         bigint generated always as identity primary key,
    name       varchar(100) not null,
    created_at timestamptz  not null default now()
);

create table member (
    id           bigint generated always as identity primary key,
    group_id     bigint       not null references expense_group (id),
    display_name varchar(80)  not null,
    iban         varchar(34),
    bic          varchar(11),
    created_at   timestamptz  not null default now(),
    constraint member_name_unique_in_group unique (group_id, display_name),
    -- Target for composite foreign keys below: "this member belongs to this group".
    constraint member_group_id_unique unique (group_id, id)
);

create table expense (
    id              bigint generated always as identity primary key,
    group_id        bigint       not null references expense_group (id),
    payer_id        bigint       not null,
    amount_cents    bigint       not null check (amount_cents > 0),
    description     varchar(200) not null,
    notes           text,
    expense_date    date         not null,
    split_mode      varchar(10)  not null check (split_mode in ('EQUAL', 'EXACT', 'SHARES')),
    idempotency_key varchar(100) not null,
    -- SHA-256 of the canonical request, so a reused key with a different body can be rejected.
    request_hash    varchar(64)  not null,
    created_at      timestamptz  not null default now(),
    constraint expense_payer_in_group foreign key (group_id, payer_id) references member (group_id, id),
    constraint expense_idempotency_key_unique unique (group_id, idempotency_key),
    constraint expense_group_id_unique unique (group_id, id)
);

create table expense_share (
    expense_id   bigint  not null,
    member_id    bigint  not null,
    group_id     bigint  not null,
    amount_cents bigint  not null check (amount_cents >= 0),
    -- Only set for split_mode SHARES (e.g. 2 shares vs 1 share).
    share_units  integer check (share_units > 0),
    primary key (expense_id, member_id),
    constraint share_expense_in_group foreign key (group_id, expense_id) references expense (group_id, id),
    constraint share_member_in_group foreign key (group_id, member_id) references member (group_id, id)
);

-- Signed amounts: positive = the group owes this member, negative = this member owes the group.
-- The entries written for one business event always sum to zero.
create table ledger_entry (
    id           bigint generated always as identity primary key,
    group_id     bigint      not null references expense_group (id),
    member_id    bigint      not null,
    amount_cents bigint      not null check (amount_cents <> 0),
    kind         varchar(10) not null check (kind in ('EXPENSE', 'PAYMENT', 'REVERSAL')),
    -- Id of the expense, payment or reversed entry; which table depends on kind, so no foreign key.
    reference_id bigint      not null,
    created_at   timestamptz not null default now(),
    constraint ledger_member_in_group foreign key (group_id, member_id) references member (group_id, id)
);

create index ledger_entry_group_member_idx on ledger_entry (group_id, member_id);
create index ledger_entry_reference_idx on ledger_entry (kind, reference_id);
create index expense_group_date_idx on expense (group_id, expense_date desc, id desc);
