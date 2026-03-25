-- V1__create_accounts_table.sql
-- Account Service — Initial Schema
--
-- Design notes:
--   - UUID as primary key: avoids sequential ID enumeration (security)
--   - balance as NUMERIC(19,4): sufficient precision for financial amounts
--   - version column: optimistic locking (JPA @Version) to prevent lost updates
--     under concurrent debit/credit operations
--   - updated_at NOT managed by DB trigger intentionally:
--     the domain controls this timestamp for consistency with domain events

CREATE TABLE accounts (
    id          UUID            NOT NULL,
    owner_id    VARCHAR(255)    NOT NULL,
    currency    VARCHAR(3)      NOT NULL,
    balance     NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    status      VARCHAR(10)     NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL,
    version     BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_currency CHECK (currency IN ('EUR', 'USD', 'GBP')),
    CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);
CREATE INDEX idx_accounts_status   ON accounts (status);
CREATE INDEX idx_accounts_owner_status ON accounts (owner_id, status);

COMMENT ON TABLE  accounts               IS 'Bank accounts — managed by Account Service';
COMMENT ON COLUMN accounts.version       IS 'Optimistic lock version — prevents concurrent update conflicts';
COMMENT ON COLUMN accounts.balance       IS 'Current balance in account currency. Invariant: always >= 0';
COMMENT ON COLUMN accounts.owner_id      IS 'References the Keycloak subject (sub claim) of the account owner';
