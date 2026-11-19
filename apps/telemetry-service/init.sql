CREATE TABLE IF NOT EXISTS readings (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL CHECK (device_id > 0),
    value DOUBLE PRECISION NOT NULL CHECK (value > '-Infinity'::float8 AND value < 'Infinity'::float8),
    unit VARCHAR(32) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL CHECK (status IN ('active', 'inactive', 'error')),
    observed_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('manual', 'simulator')),
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- device_id refers to a different service; there is deliberately no cross-database FK.
CREATE INDEX IF NOT EXISTS idx_readings_device_time ON readings (device_id, observed_at DESC, id DESC);
