-- Key/value JSONB state tables: domain objects are serialized into `data`,
-- keyed by a GroupedKey, discriminated by `type` (the object's simple class name).
-- created_at is written on INSERT only (audit); last_update on every write.

create table access_state
(
    created_at  timestamp(6) with time zone,
    last_update timestamp(6) with time zone,
    data        jsonb,
    key         varchar(255) not null primary key,
    type        varchar(255)
);

create index idx_access_type on access_state (type);
create index idx_access_last_update on access_state (last_update);

create table project_state
(
    created_at  timestamp(6) with time zone,
    last_update timestamp(6) with time zone,
    data        jsonb,
    key         varchar(255) not null primary key,
    type        varchar(255)
);

create index idx_project_type on project_state (type);
create index idx_project_last_update on project_state (last_update);

create table session_state
(
    expiry      timestamp(6) with time zone,
    created_at  timestamp(6) with time zone,
    last_update timestamp(6) with time zone,
    data        jsonb,
    key         varchar(255) not null primary key,
    type        varchar(255)
);

create index idx_session_type on session_state (type);
create index idx_session_expiry on session_state (expiry);
