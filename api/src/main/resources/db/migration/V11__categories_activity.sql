-- Categories for expenses (for the insights page) and who added an expense or payment (for the
-- activity feed). Both are written once when the row is inserted; the rows stay immutable.

alter table expense
    -- Null for expenses from before categories existed; shown as OTHER.
    add column category   varchar(20) check (category in ('GROCERIES', 'FOOD', 'RENT', 'UTILITIES', 'TRANSPORT',
                                                          'TRAVEL', 'ENTERTAINMENT', 'SHOPPING', 'SUBSCRIPTIONS',
                                                          'GIFTS', 'OTHER')),
    -- The member who entered it, not necessarily the payer. Null for expenses from before V11.
    add column created_by bigint,
    add constraint expense_created_by_in_group foreign key (group_id, created_by) references member (group_id, id);

alter table payment
    add column created_by bigint,
    add constraint payment_created_by_in_group foreign key (group_id, created_by) references member (group_id, id);

-- The activity feed and the insights read expenses by creation time and by month.
create index expense_group_created_idx on expense (group_id, created_at desc, id desc);
