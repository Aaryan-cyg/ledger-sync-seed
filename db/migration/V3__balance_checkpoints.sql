-- Stated balance checkpoints from bank messages for reconciliation.
CREATE TABLE IF NOT EXISTS balance_checkpoints (
    id                 IDENTITY PRIMARY KEY,
    account_last4      VARCHAR(4)     NOT NULL,
    occurred_at        VARCHAR(40)    NOT NULL,
    balance            DECIMAL(14, 2) NOT NULL,
    message_id         VARCHAR(100)   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_checkpoints_account ON balance_checkpoints (account_last4);
