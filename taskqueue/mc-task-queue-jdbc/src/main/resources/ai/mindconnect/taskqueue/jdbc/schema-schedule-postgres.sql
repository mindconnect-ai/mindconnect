-- Schedules for JdbcScheduleStore. Separate table and separate DDL from
-- mc_task on purpose: a deployment that wants durable tasks without cron
-- should not end up with a table it never writes to.

CREATE TABLE IF NOT EXISTS mc_task_schedule (
    id             TEXT PRIMARY KEY,
    name           TEXT        NOT NULL,
    task_type      TEXT        NOT NULL,
    payload        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    cron           TEXT        NOT NULL,
    zone           TEXT        NOT NULL,
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    priority       INTEGER     NOT NULL DEFAULT 0,
    max_attempts   INTEGER     NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL,

    -- The one column that makes this safe on twenty nodes: the firing time
    -- this schedule has already been claimed for. Every claim is a conditional
    -- UPDATE against it, so the losers change nothing and wait for nothing.
    last_fired_for TIMESTAMPTZ,
    last_task_id   TEXT
);
