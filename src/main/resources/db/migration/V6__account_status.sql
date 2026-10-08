-- Phase 8: every request with a JWT is checked against the account, so access can be taken away at once.

-- An ADMIN can switch an account off; a switched-off account can't log in and its tokens stop working
ALTER TABLE users ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;

-- Copied into each JWT. Changing the password increases it, so every token issued before stops working
ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0 CHECK (token_version >= 0);
