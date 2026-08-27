-- Durable channel events for JdbcChannelStore. The store assigns the seq —
-- one gapless space per channel, forever — which is what lets a reconnecting
-- subscriber replay from HERE and attach live without gaps or duplicates.

-- One row per channel: its head. The atomic increment on this row is the
-- seq assignment; everything else is an append.
CREATE TABLE IF NOT EXISTS mc_channel (
    id       TEXT   PRIMARY KEY,
    last_seq BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS mc_channel_event (
    channel_id TEXT        NOT NULL,
    seq        BIGINT      NOT NULL,
    payload    JSONB       NOT NULL,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, seq)
);
