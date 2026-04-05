import { cn } from "@/lib/utils";

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface SchemaFormProps {
  schema: Record<string, unknown>;
  values: Record<string, unknown>;
  onChange: (key: string, value: unknown) => void;
}

interface PropertyDef {
  type?: string;
  description?: string;
  default?: unknown;
  enum?: string[];
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function getProperties(schema: Record<string, unknown>): Record<string, PropertyDef> {
  return (schema.properties as Record<string, PropertyDef>) ?? {};
}

function getRequired(schema: Record<string, unknown>): string[] {
  return (schema.required as string[]) ?? [];
}

/* ------------------------------------------------------------------ */
/*  Field renderers                                                    */
/* ------------------------------------------------------------------ */

const inputBase =
  "w-full rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 transition-colors";

function renderField(
  key: string,
  prop: PropertyDef,
  value: unknown,
  onChange: (key: string, value: unknown) => void,
) {
  const placeholder = prop.description ?? "";

  if (prop.enum) {
    return (
      <select
        className={inputBase}
        value={(value as string) ?? ""}
        onChange={(e) => onChange(key, e.target.value)}
      >
        <option value="">{placeholder || "선택..."}</option>
        {prop.enum.map((v) => (
          <option key={v} value={v}>
            {v}
          </option>
        ))}
      </select>
    );
  }

  switch (prop.type) {
    case "boolean":
      return (
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={Boolean(value)}
            onChange={(e) => onChange(key, e.target.checked)}
            className="h-4 w-4 rounded border-border text-primary focus:ring-primary/40"
          />
          <span className="text-sm text-muted-foreground">{prop.description ?? key}</span>
        </label>
      );

    case "integer":
    case "number":
      return (
        <input
          type="number"
          className={inputBase}
          placeholder={placeholder}
          value={value != null ? String(value) : ""}
          step={prop.type === "integer" ? 1 : "any"}
          onChange={(e) => {
            const v = e.target.value;
            onChange(key, v === "" ? undefined : prop.type === "integer" ? parseInt(v) : parseFloat(v));
          }}
        />
      );

    case "array":
    case "object":
      return (
        <textarea
          className={cn(inputBase, "min-h-[80px] font-mono text-xs")}
          placeholder={placeholder || `JSON ${prop.type}`}
          value={value != null ? (typeof value === "string" ? value : JSON.stringify(value, null, 2)) : ""}
          onChange={(e) => {
            try {
              onChange(key, JSON.parse(e.target.value));
            } catch {
              onChange(key, e.target.value);
            }
          }}
        />
      );

    default:
      return (
        <input
          type="text"
          className={inputBase}
          placeholder={placeholder}
          value={(value as string) ?? ""}
          onChange={(e) => onChange(key, e.target.value)}
        />
      );
  }
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

export function SchemaForm({ schema, values, onChange }: SchemaFormProps) {
  const properties = getProperties(schema);
  const required = getRequired(schema);
  const keys = Object.keys(properties);

  if (keys.length === 0) {
    return (
      <div className="text-sm text-muted-foreground italic py-2">
        스키마에 정의된 속성이 없습니다.
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {keys.map((key) => {
        const prop = properties[key];
        const isRequired = required.includes(key);

        return (
          <div key={key} className="flex flex-col gap-1.5">
            {prop.type !== "boolean" && (
              <label className="text-sm font-medium text-foreground">
                {key}
                {isRequired && <span className="text-destructive ml-0.5">*</span>}
              </label>
            )}
            {renderField(key, prop, values[key], onChange)}
          </div>
        );
      })}
    </div>
  );
}
