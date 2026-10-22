-- Transactional outbox: every expense, reversal and payment writes an event row in the same
-- transaction as its ledger entries. A publisher sends unpublished rows to SQS and marks them, so an
-- event exists exactly when the business change was committed, even if SQS was down at that moment.

create table outbox_event (
    id           bigint generated always as identity primary key,
    group_id     bigint      not null references expense_group (id),
    event_type   varchar(30) not null check (event_type in ('EXPENSE_RECORDED', 'EXPENSE_REVERSED', 'PAYMENT_RECORDED')),
    payload      jsonb       not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);

-- The publisher only ever looks for unpublished rows; the partial index stays tiny.
create index outbox_event_unpublished_idx on outbox_event (id) where published_at is null;

-- SELECT and INSERT come from the default privileges of V7; the application may only mark rows sent.
grant update (published_at) on outbox_event to splitledger_app;
