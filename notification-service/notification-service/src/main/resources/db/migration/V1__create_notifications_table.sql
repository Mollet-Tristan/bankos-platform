-- V1__create_notifications_table.sql
-- Notification Service — Initial Schema
--
-- Design notes:
--   - source_event_id + channel: the idempotency key for this service.
--     One notification per (source_event_id, channel) — enforced by unique index.
--   - attempts: tracked at DB level for the retry scheduler query.
--   - body stored as TEXT: email bodies can be large (HTML).
--   - No FK to transactions/accounts: Database per Service pattern (ADR-006).

CREATE TABLE notifications (
    id                UUID            NOT NULL,
    recipient_id      VARCHAR(255)    NOT NULL,
    channel           VARCHAR(10)     NOT NULL,
    type              VARCHAR(40)     NOT NULL,
    subject           VARCHAR(500)    NOT NULL,
    body              TEXT            NOT NULL,
    source_event_id   UUID            NOT NULL,
    source_event_type VARCHAR(60)     NOT NULL,
    status            VARCHAR(25)     NOT NULL DEFAULT 'PENDING',
    attempts          INT             NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ     NOT NULL,
    updated_at        TIMESTAMPTZ     NOT NULL,
    version           BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_notifications       PRIMARY KEY (id),
    CONSTRAINT chk_channel            CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT chk_status             CHECK (status IN ('PENDING', 'SENDING', 'DELIVERED', 'FAILED', 'PERMANENTLY_FAILED')),
    CONSTRAINT chk_attempts_positive  CHECK (attempts >= 0)
);

-- Idempotency: one notification per event per channel
CREATE UNIQUE INDEX uq_notifications_event_channel
    ON notifications (source_event_id, channel);

CREATE INDEX idx_notifications_recipient    ON notifications (recipient_id);
CREATE INDEX idx_notifications_status       ON notifications (status);
CREATE INDEX idx_notifications_source_event ON notifications (source_event_id);
CREATE INDEX idx_notifications_created_at   ON notifications (created_at DESC);

-- Index for retry scheduler query: find FAILED with attempts < 3
CREATE INDEX idx_notifications_retryable
    ON notifications (status, attempts)
    WHERE status = 'FAILED' AND attempts < 3;

COMMENT ON TABLE  notifications                 IS 'Notification delivery records — managed by Notification Service';
COMMENT ON COLUMN notifications.source_event_id IS 'ID of the upstream event that triggered this notification. Used for idempotency.';
COMMENT ON COLUMN notifications.attempts        IS 'Number of delivery attempts made. Max 3 before PERMANENTLY_FAILED.';
COMMENT ON COLUMN notifications.last_error      IS 'Error message from last failed delivery attempt. For ops debugging.';
