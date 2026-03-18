CREATE TABLE traces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id VARCHAR(100) NOT NULL,
    session_id VARCHAR(100),
    model VARCHAR(100),
    messages_in JSONB,
    response JSONB,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    latency_ms INT DEFAULT 0,
    cost_usd NUMERIC(10,6) DEFAULT 0,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_traces_session ON traces(session_id);
CREATE INDEX idx_traces_model ON traces(model);
CREATE INDEX idx_traces_created ON traces(created_at);
