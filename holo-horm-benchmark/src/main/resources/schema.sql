-- H2 schema for HORM JMH benchmarks (MODE=MySQL compatible)
CREATE TABLE IF NOT EXISTS bench_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255),
    name VARCHAR(255),
    created_at TIMESTAMP
);
