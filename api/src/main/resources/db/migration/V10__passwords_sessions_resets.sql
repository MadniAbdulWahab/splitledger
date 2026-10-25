-- Sign-in for people instead of scripts: username and password, one session per device, invite
-- links that create the account, and password resets made by a groupmate. API tokens from V4 keep
-- working next to the sessions (scripts, accounts created before this migration).

alter table app_user
    add column username      varchar(30),
    -- bcrypt hash; it contains its own salt and cost factor.
    add column password_hash varchar(100),
    add constraint app_user_username_unique unique (username),
    -- Stored in lower case, so "Ben" and "ben" cannot both exist.
    add constraint app_user_username_format check (username ~ '^[a-z0-9._-]{3,30}$');

-- Only these columns of an account can change; the API token hash and the creation time cannot.
grant update (username, password_hash) on app_user to splitledger_app;

create table user_session (
    id         bigint generated always as identity primary key,
    user_id    bigint      not null references app_user (id),
    -- SHA-256 of the session token, like API tokens: the token itself is never stored.
    token_hash varchar(64) not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    -- Set on sign-out; the row stays, so sign-ins remain traceable.
    revoked_at timestamptz,
    constraint user_session_token_hash_unique unique (token_hash)
);

create index user_session_user_idx on user_session (user_id);
grant update (revoked_at) on user_session to splitledger_app;

-- Who added a member (for "Anna invited you") and when the friend's account was linked.
alter table member
    add column invited_by bigint,
    add column joined_at  timestamptz,
    add constraint member_invited_by_in_group foreign key (group_id, invited_by) references member (group_id, id);

update member set joined_at = created_at where user_id is not null;

-- A one-time link a groupmate creates for a friend who forgot their password.
create table password_reset (
    id                   bigint generated always as identity primary key,
    user_id              bigint      not null references app_user (id),
    group_id             bigint      not null references expense_group (id),
    -- The member who created the link; shown to the whole group in the activity feed.
    created_by_member_id bigint      not null,
    code_hash            varchar(64) not null,
    created_at           timestamptz not null default now(),
    expires_at           timestamptz not null,
    used_at              timestamptz,
    constraint password_reset_code_hash_unique unique (code_hash),
    constraint password_reset_member_in_group foreign key (group_id, created_by_member_id) references member (group_id, id)
);

create index password_reset_group_idx on password_reset (group_id, created_at);
grant update (used_at) on password_reset to splitledger_app;
