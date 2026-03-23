-- V1__create_transactions_table.sql
-- Transaction Service — Initial Schema
--
-- Design notes:
--   - idempotency_key has a UNIQUE constraint: the DB is the last line of
--     defense against duplicate transactions (application layer checks first,
--     but concurrent requests can race past that check).
--   - amount/currency stored together: never store an amount without its currency.
--   - status + created_at indexed: most queries filter by status or sort by date.
--   - No foreign key to accounts table: Transaction Service owns its own DB.
--     Cross-service data integrity is enforced at the application level (saga).
--     This is the "Database per Service" pattern (ADR-006).

CREATE TABLE transactions (
    id                UUID            NOT NULL,
    source_account_id UUID            NOT NULL,
    target_account_id UUID,
    amount            NUMERIC(19, 4)  NOT NULL,
    currency          VARCHAR(3)      NOT NULL,
    type              VARCHAR(20)     NOT NULL,
    status            VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    description       VARCHAR(500)    NOT NULL,
    idempotency_key   VARCHAR(255)    NOT NULL,
    failure_reason    TEXT,
    created_at        TIMESTAMPTZ     NOT NULL,
    updated_at        TIMESTAMPTZ     NOT NULL,
    version           BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_transactions           PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_key        UNIQUE (idempotency_key),
    CONSTRAINT chk_amount_positive       CHECK (amount > 0),
    CONSTRAINT chk_currency              CHECK (currency IN ('EUR', 'USD', 'GBP')),
    CONSTRAINT chk_type                  CHECK (type IN ('WITHDRAWAL', 'DEPOSIT', 'TRANSFER')),
    CONSTRAINT chk_status                CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'COMPENSATED')),
    CONSTRAINT chk_transfer_has_target   CHECK (
        (type = 'TRANSFER' AND target_account_id IS NOT NULL)
        OR (type != 'TRANSFER')
    )
);

CREATE INDEX idx_transactions_source_account ON transactions (source_account_id);
CREATE INDEX idx_transactions_status         ON transactions (status);
CREATE INDEX idx_transactions_created_at     ON transactions (created_at DESC);
-- Composite index for the most common backoffice query: account history sorted by date
CREATE INDEX idx_transactions_account_date   ON transactions (source_account_id, created_at DESC);

COMMENT ON TABLE  transactions                    IS 'Financial transactions — managed by Transaction Service';
COMMENT ON COLUMN transactions.idempotency_key    IS 'Caller-supplied key for safe retries. Unique constraint prevents duplicates at DB level.';
COMMENT ON COLUMN transactions.version            IS 'Optimistic lock — prevents concurrent status transitions on the same transaction';
COMMENT ON COLUMN transactions.failure_reason     IS 'Populated on FAILED or COMPENSATED status. Human-readable reason for audit.';
COMMENT ON COLUMN transactions.target_account_id  IS 'NULL for WITHDRAWAL and DEPOSIT. Required for TRANSFER (enforced by chk_transfer_has_target).';
