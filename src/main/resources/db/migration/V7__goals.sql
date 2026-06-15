CREATE TABLE goals (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    target_amount   DECIMAL(19,2) NOT NULL CHECK (target_amount > 0),
    current_amount  DECIMAL(19,2) NOT NULL DEFAULT 0 CHECK (current_amount >= 0),
    icon_name  VARCHAR(50)  NOT NULL DEFAULT 'Target',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_goals_user_id ON goals(user_id);
