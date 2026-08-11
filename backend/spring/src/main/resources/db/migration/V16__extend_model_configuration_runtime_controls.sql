ALTER TABLE model_configurations ADD COLUMN temperature DOUBLE NOT NULL DEFAULT 0.2;
ALTER TABLE model_configurations ADD COLUMN max_output_tokens INT NOT NULL DEFAULT 1024;
ALTER TABLE model_configurations ADD COLUMN request_timeout_ms BIGINT NOT NULL DEFAULT 45000;
ALTER TABLE model_configurations ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE model_configurations ADD COLUMN daily_token_quota BIGINT NOT NULL DEFAULT 0;
ALTER TABLE model_configurations ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE model_configurations ADD COLUMN last_test_status VARCHAR(64) NULL;
ALTER TABLE model_configurations ADD COLUMN last_tested_at TIMESTAMP NULL;
