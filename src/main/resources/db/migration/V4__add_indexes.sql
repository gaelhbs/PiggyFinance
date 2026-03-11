-- Performance indexes for common query patterns

CREATE INDEX IF NOT EXISTS idx_transactions_user_id
    ON transactions (user_id);

CREATE INDEX IF NOT EXISTS idx_transactions_timestamp
    ON transactions (timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_transactions_user_timestamp
    ON transactions (user_id, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_transactions_type
    ON transactions (type);
