CREATE TABLE IF NOT EXISTS devices (
    id BIGSERIAL PRIMARY KEY,
    house_id BIGINT NOT NULL DEFAULT 1 CHECK (house_id > 0),
    serial_number VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(30) NOT NULL CHECK (type IN ('temperature', 'heating', 'light', 'gate', 'camera')),
    location VARCHAR(100) NOT NULL,
    unit VARCHAR(20) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'inactive' CHECK (status IN ('active', 'inactive', 'error')),
    state JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_devices_active_house ON devices(house_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_devices_active_type ON devices(type) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS device_commands (
    id UUID PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES devices(id),
    action VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status = 'completed'),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    state JSONB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_commands_device ON device_commands(device_id, created_at DESC);
