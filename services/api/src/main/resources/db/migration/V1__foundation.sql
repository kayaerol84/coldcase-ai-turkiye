-- T0 foundation: identity, cases, membership, append-only audit.
-- Convention: every case-owned table carries case_id; pdf page numbers are 1-based.

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    oidc_subject    TEXT NOT NULL UNIQUE,
    email           TEXT,
    display_name    TEXT,
    platform_admin  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cases (
    id                UUID PRIMARY KEY,
    title             TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    description       TEXT,
    primary_language  VARCHAR(10) NOT NULL DEFAULT 'tr',
    period_start      DATE,
    period_end        DATE,
    created_by        UUID NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (period_end IS NULL OR period_start IS NULL OR period_end >= period_start)
);

CREATE TABLE case_memberships (
    case_id     UUID NOT NULL REFERENCES cases(id),
    user_id     UUID NOT NULL REFERENCES users(id),
    role        VARCHAR(20) NOT NULL CHECK (role IN ('VIEWER','RESEARCHER','REVIEWER','CASE_ADMIN')),
    granted_by  UUID NOT NULL REFERENCES users(id),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (case_id, user_id)
);
CREATE INDEX idx_case_memberships_user ON case_memberships(user_id);

CREATE TABLE audit_events (
    id           BIGSERIAL PRIMARY KEY,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id     UUID REFERENCES users(id),
    case_id      UUID REFERENCES cases(id),
    action       VARCHAR(60) NOT NULL,
    target_type  VARCHAR(40),
    target_id    TEXT,
    details      JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_audit_case_time ON audit_events(case_id, occurred_at DESC);

-- Append-only from the application perspective (TR §12.3).
CREATE FUNCTION audit_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();
