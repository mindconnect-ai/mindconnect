-- Shared state between tasks (SharedStateStore): the claim is the insert.
CREATE TABLE IF NOT EXISTS mc_shared_state (
    id    TEXT  NOT NULL,
    key   TEXT  NOT NULL,
    value JSONB NOT NULL,
    PRIMARY KEY (id, key)
);
