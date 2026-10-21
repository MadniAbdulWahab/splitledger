-- Least-privilege role for the running application. Flyway migrates as the owner of the tables;
-- the application connects as splitledger_app, which cannot change the schema, cannot disable the
-- append-only triggers (only the owner can) and cannot UPDATE or DELETE immutable tables at all.
-- The role is created without a login here; AppRolePasswordCallback gives it a login and the
-- password from configuration after every migration, so no secret is ever written into a migration.

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'splitledger_app') then
        create role splitledger_app nologin;
    end if;
end
$$;

grant usage on schema public to splitledger_app;
grant select, insert on all tables in schema public to splitledger_app;
grant usage on all sequences in schema public to splitledger_app;

-- Mutable tables only. UPDATE is also required for SELECT ... FOR UPDATE: bank_transaction is
-- locked during confirmation, and expense_group is locked (FOR NO KEY UPDATE) before ledger appends,
-- which needs UPDATE on at least one column, so only the harmless name column is granted.
grant update on member, bank_import, bank_transaction to splitledger_app;
grant update (name) on expense_group to splitledger_app;

-- No DELETE anywhere, no UPDATE on ledger_entry, expense, expense_share, payment, app_user.
-- Flyway's own history is off-limits, so the application cannot fake an applied migration.
revoke all on flyway_schema_history from splitledger_app;

-- Tables created by later migrations get read and insert rights automatically.
alter default privileges in schema public grant select, insert on tables to splitledger_app;
alter default privileges in schema public grant usage on sequences to splitledger_app;
