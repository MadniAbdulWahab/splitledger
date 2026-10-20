-- Accounts and group access. A member may exist without an account (a friend who has not joined
-- yet); an invite code links the friend's account to that member later.

create table app_user (
    id           bigint generated always as identity primary key,
    display_name varchar(80) not null,
    -- SHA-256 of the API token. The token itself is shown once at registration and never stored.
    token_hash   varchar(64) not null,
    created_at   timestamptz not null default now(),
    constraint app_user_token_hash_unique unique (token_hash)
);

alter table member
    add column user_id          bigint references app_user (id),
    -- SHA-256 of a one-time invite code; cleared when the invitation is accepted.
    add column invite_code_hash varchar(64),
    add constraint member_user_unique_in_group unique (group_id, user_id),
    add constraint member_invite_code_unique unique (invite_code_hash);

create index member_user_idx on member (user_id);
