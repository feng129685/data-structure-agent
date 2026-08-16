ALTER TABLE users ADD COLUMN username VARCHAR(32) NULL;
ALTER TABLE users ADD COLUMN username_normalized VARCHAR(32) NULL;

CREATE UNIQUE INDEX uq_users_username_normalized ON users (username_normalized);
