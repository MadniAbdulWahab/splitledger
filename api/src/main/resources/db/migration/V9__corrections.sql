-- Who deleted or edited an expense and who undid a payment. The ledger already holds the
-- reversing entries; this table adds the person, the time and, for an edit, the new version.
-- Like the ledger it is append-only: a correction is never corrected itself.

alter table payment add constraint payment_group_id_unique unique (group_id, id);

-- Undoing a payment publishes its own event type.
alter table outbox_event drop constraint outbox_event_event_type_check;
alter table outbox_event add constraint outbox_event_event_type_check
    check (event_type in ('EXPENSE_RECORDED', 'EXPENSE_REVERSED', 'PAYMENT_RECORDED', 'PAYMENT_REVERSED'));

create table correction (
    id                     bigint generated always as identity primary key,
    group_id               bigint      not null references expense_group (id),
    kind                   varchar(20) not null,
    expense_id             bigint,
    replacement_expense_id bigint,
    payment_id             bigint,
    member_id              bigint      not null,
    created_at             timestamptz not null default now(),
    constraint correction_kind check (
        (kind = 'EXPENSE_DELETED' and expense_id is not null and replacement_expense_id is null and payment_id is null)
        or (kind = 'EXPENSE_EDITED' and expense_id is not null and replacement_expense_id is not null and payment_id is null)
        or (kind = 'PAYMENT_UNDONE' and payment_id is not null and expense_id is null and replacement_expense_id is null)),
    constraint correction_expense_in_group foreign key (group_id, expense_id) references expense (group_id, id),
    constraint correction_replacement_in_group foreign key (group_id, replacement_expense_id) references expense (group_id, id),
    constraint correction_payment_in_group foreign key (group_id, payment_id) references payment (group_id, id),
    constraint correction_member_in_group foreign key (group_id, member_id) references member (group_id, id),
    -- An expense or payment is corrected at most once; an edit's new version replaces exactly one.
    constraint correction_once_per_expense unique (expense_id),
    constraint correction_once_per_payment unique (payment_id),
    constraint correction_replacement_once unique (replacement_expense_id)
);

create trigger correction_append_only
    before update or delete on correction
    for each row execute function reject_change_to_append_only_table();
create trigger correction_no_truncate
    before truncate on correction
    for each statement execute function reject_change_to_append_only_table();

-- V7's default privileges cover new tables; stated here as well so the rule is visible.
grant select, insert on correction to splitledger_app;
