-- Only for the temporary H2 process integration test; never used by application startup.
CREATE TABLE top50_prediction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prediction_date DATE NOT NULL, app_id VARCHAR(32) NOT NULL, granularity VARCHAR(16) NOT NULL,
    model_version VARCHAR(64) NOT NULL, rank_no INT NOT NULL, file_key VARCHAR(512) NOT NULL,
    file_type VARCHAR(16) NOT NULL, file_size_bytes BIGINT NOT NULL, predicted_bytes BIGINT NOT NULL,
    prediction_score DECIMAL(8,4) NOT NULL, recommendation_reason VARCHAR(255) NOT NULL,
    recent_actual_bytes BIGINT NOT NULL, compression_status VARCHAR(24) NOT NULL
);
