export interface Schema {
  id: string;
  version?: string;
  description?: string;
  domain?: string;
  schema?: Record<string, unknown>;
  fieldCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface SchemaRequest {
  id: string;
  version?: string;
  description?: string;
  domain?: string;
  schema: Record<string, unknown>;
}

export interface ValidateResult {
  valid: boolean;
  errors?: string[];
}
