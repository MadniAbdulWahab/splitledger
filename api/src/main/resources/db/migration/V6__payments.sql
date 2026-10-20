-- Settle-up payments between members. Like expenses they are immutable and write ledger entries
-- (kind PAYMENT): the payer's debt shrinks, the receiver's claim shrinks.

create table payment (
    id              bigint generated always as identity primary key,
    group_id        bigint       not null references expense_group (id),
    from_member_id  bigint       not null,
    to_member_id    bigint       not null,
    amount_cents    bigint       not null check (amount_cents > 0),
    note            varchar(200),
    idempotency_key varchar(100) not null,
    request_hash    varchar(64)  not null,
    created_at      timestamptz  not null default now(),
    constraint payment_from_in_group foreign key (group_id, from_member_id) references member (group_id, id),
    constraint payment_to_in_group foreign key (group_id, to_member_id) references member (group_id, id),
    constraint payment_not_to_self check (from_member_id <> to_member_id),
    constraint payment_idempotency_key_unique unique (group_id, idempotency_key)
);

create trigger payment_append_only
    before update or delete on payment
    for each row execute function reject_change_to_append_only_table();
create trigger payment_no_truncate
    before truncate on payment
    for each statement execute function reject_change_to_append_only_table();
