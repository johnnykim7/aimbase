CREATE TABLE model_pricing (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_name VARCHAR(100) NOT NULL UNIQUE,
    provider VARCHAR(50) NOT NULL,
    input_price_per_1k NUMERIC(10,6) NOT NULL,
    output_price_per_1k NUMERIC(10,6) NOT NULL,
    currency VARCHAR(10) DEFAULT 'USD',
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Seed data
INSERT INTO model_pricing (model_name, provider, input_price_per_1k, output_price_per_1k) VALUES
('gpt-4o', 'openai', 0.005, 0.015),
('gpt-4o-mini', 'openai', 0.00015, 0.0006),
('gpt-4-turbo', 'openai', 0.01, 0.03),
('gpt-3.5-turbo', 'openai', 0.0005, 0.0015),
('claude-3-5-sonnet-20241022', 'anthropic', 0.003, 0.015),
('claude-3-5-haiku-20241022', 'anthropic', 0.001, 0.005),
('claude-3-opus-20240229', 'anthropic', 0.015, 0.075),
('claude-sonnet-4-5-20250514', 'anthropic', 0.003, 0.015),
('text-embedding-3-small', 'openai', 0.00002, 0.0),
('text-embedding-3-large', 'openai', 0.00013, 0.0);
