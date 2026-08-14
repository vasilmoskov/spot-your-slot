CREATE UNIQUE INDEX owner_invitation_one_active_generation_idx
    ON owner_invitation (business_id, normalized_email)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;

CREATE UNIQUE INDEX password_reset_one_active_generation_idx
    ON password_reset (user_id)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;
