-- Task records for JdbcTaskStore. One row per task: queue entry, status record
-- and audit row in one, exactly as TaskRecord describes it — plus the two
-- columns a single process never needs and a cluster cannot do without.

CREATE TABLE IF NOT EXISTS mc_task (
    id               TEXT PRIMARY KEY,
    type             TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    payload          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    priority         INTEGER     NOT NULL DEFAULT 0,
    parent_task_id   TEXT,
    attempt          INTEGER     NOT NULL DEFAULT 0,
    -- who ran the last attempt — a fact that OUTLIVES the lease, so "which
    -- node finished this" stays answerable on the terminal record
    node_id          TEXT,
    cancel_requested BOOLEAN     NOT NULL DEFAULT FALSE,
    waiting_for      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    notifications    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    resumed          BOOLEAN     NOT NULL DEFAULT FALSE,
    state            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    result           TEXT,
    failure          JSONB,
    run_after        TIMESTAMPTZ,
    max_attempts     INTEGER     NOT NULL DEFAULT 1,
    submitted_at     TIMESTAMPTZ NOT NULL,
    started_at       TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,

    -- The lease. Who is working on this row, and until when they may claim to
    -- be. A worker that stops renewing has its task reclaimed; nothing else
    -- can tell a busy node from a dead one.
    lease_owner      TEXT,
    lease_expires_at TIMESTAMPTZ
);

-- The claim query and nothing else: only the rows a dispatcher looks at, in
-- exactly the order it wants them, so SKIP LOCKED walks an index and not a table.
CREATE INDEX IF NOT EXISTS mc_task_claim_idx
    ON mc_task (type, priority DESC, submitted_at)
    WHERE status = 'QUEUED';

-- children(), the cancel cascade and the tree view all walk this link.
CREATE INDEX IF NOT EXISTS mc_task_parent_idx
    ON mc_task (parent_task_id)
    WHERE parent_task_id IS NOT NULL;

-- Lease recovery: find the abandoned rows without reading the live ones.
CREATE INDEX IF NOT EXISTS mc_task_lease_idx
    ON mc_task (lease_expires_at)
    WHERE status = 'RUNNING';

-- wake(): which suspended tasks were waiting for the task that just ended.
CREATE INDEX IF NOT EXISTS mc_task_waiting_idx
    ON mc_task USING GIN (waiting_for)
    WHERE status = 'SUSPENDED';

-- Idempotent migration for tables created before node_id existed.
ALTER TABLE mc_task ADD COLUMN IF NOT EXISTS node_id TEXT;
