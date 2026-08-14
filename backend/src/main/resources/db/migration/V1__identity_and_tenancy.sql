CREATE TABLE business (
    id uuid PRIMARY KEY,
    slug varchar(100) NOT NULL UNIQUE,
    display_name varchar(200) NOT NULL,
    business_type varchar(30) NOT NULL CHECK (business_type IN ('HAIR_SALON','BARBERSHOP','NAIL_STUDIO','MASSAGE_STUDIO','MAKEUP_STUDIO','BEAUTY_STUDIO','OTHER')),
    status varchar(20) NOT NULL CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED')),
    timezone varchar(100) NOT NULL DEFAULT 'Europe/Sofia',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (slug = lower(slug)),
    CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    normalized_email varchar(320) NOT NULL UNIQUE,
    display_name varchar(200) NOT NULL,
    password_hash varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    locked boolean NOT NULL DEFAULT false,
    credential_version bigint NOT NULL DEFAULT 1,
    password_changed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (normalized_email = lower(normalized_email))
);

CREATE TABLE platform_role (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role varchar(30) NOT NULL CHECK (role = 'PLATFORM_ADMIN'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE TABLE membership (
    id uuid PRIMARY KEY,
    business_id uuid NOT NULL REFERENCES business(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    role varchar(30) NOT NULL CHECK (role IN ('BUSINESS_OWNER','MANAGER','STAFF')),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (user_id, business_id)
);
CREATE INDEX membership_business_user_idx ON membership (business_id, user_id);

CREATE TABLE user_session (
    id uuid PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    active_business_id uuid REFERENCES business(id) ON DELETE SET NULL,
    credential_version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (absolute_expires_at > created_at)
);
CREATE INDEX user_session_user_active_idx ON user_session (user_id, revoked_at, absolute_expires_at);

CREATE TABLE owner_invitation (
    id uuid PRIMARY KEY,
    business_id uuid NOT NULL REFERENCES business(id) ON DELETE CASCADE,
    normalized_email varchar(320) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    created_by uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    invalidated_at timestamptz,
    CHECK (normalized_email = lower(normalized_email)),
    CHECK (expires_at > created_at)
);
CREATE INDEX owner_invitation_active_idx ON owner_invitation (business_id, normalized_email, expires_at);

CREATE TABLE password_reset (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    invalidated_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX password_reset_user_active_idx ON password_reset (user_id, expires_at);
