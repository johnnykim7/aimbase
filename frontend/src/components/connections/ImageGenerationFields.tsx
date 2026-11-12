import { FormField } from "../common/FormField";
import { Input } from "../ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../ui/select";

/**
 * 연결 편집의 그림 칸(CR-184, PRD-544, FE-120).
 * 그림 모델, 그림 품질은 OpenAI 연결에만, 그림 연결은 모든 LLM 연결에 둔다.
 * 서버가 그림 연결로 인정하는 조건(ImageConnectionResolver)은 어댑터 OpenAI, 그림 모델 있음, 키 있음이다.
 */

export const IMAGE_QUALITIES = [
  { value: "auto", label: "자동" },
  { value: "low", label: "낮음" },
  { value: "medium", label: "보통" },
  { value: "high", label: "높음" },
] as const;

/** 서버 ImageConnectionResolver.DEFAULT_QUALITY 와 같다. */
export const DEFAULT_IMAGE_QUALITY = "medium";

// Radix Select 는 빈 글을 항목 값으로 받지 않는다.
const NONE = "__none__";

export interface ImageGenerationValues {
  imageModel: string;
  imageQuality: string;
  imageConnectionId: string;
}

export interface ImageConnectionOption {
  id: string;
  label: string;
}

function text(v: unknown): string {
  return typeof v === "string" ? v : "";
}

export function parseImageGeneration(cfg: Record<string, unknown>): ImageGenerationValues {
  return {
    imageModel: text(cfg.image_model),
    imageQuality: text(cfg.image_quality) || DEFAULT_IMAGE_QUALITY,
    imageConnectionId: text(cfg.image_connection_id),
  };
}

/** 빈 값은 undefined 로 돌려 저장 때 키를 뺀다. 그림 모델이 없으면 품질도 뺀다. */
export function serializeImageGeneration(
  v: ImageGenerationValues,
  isOpenAi: boolean,
): { image_model?: string; image_quality?: string; image_connection_id?: string } {
  const model = isOpenAi ? v.imageModel.trim() : "";
  return {
    image_model: model || undefined,
    image_quality: model ? v.imageQuality || DEFAULT_IMAGE_QUALITY : undefined,
    image_connection_id: v.imageConnectionId.trim() || undefined,
  };
}

interface Props {
  isOpenAi: boolean;
  values: ImageGenerationValues;
  options: ImageConnectionOption[];
  onChange: (next: ImageGenerationValues) => void;
}

export function ImageGenerationFields({ isOpenAi, values, options, onChange }: Props) {
  const hasModel = isOpenAi && values.imageModel.trim() !== "";
  // 저장된 그림 연결이 목록 조건에서 빠졌어도 지금 값을 보이고 비울 수 있게 남긴다.
  const shown =
    values.imageConnectionId && !options.some((o) => o.id === values.imageConnectionId)
      ? [...options, { id: values.imageConnectionId, label: values.imageConnectionId }]
      : options;

  return (
    <>
      {isOpenAi && (
        <FormField label="그림 모델" hint="적으면 이 연결로 위젯 그림을 만들 수 있습니다. 비우면 그림 연결이 아닙니다.">
          <Input
            placeholder="gpt-image-1"
            value={values.imageModel}
            onChange={(e) => onChange({ ...values, imageModel: e.target.value })}
          />
        </FormField>
      )}
      {hasModel && (
        <FormField label="그림 품질">
          <Select
            value={values.imageQuality || DEFAULT_IMAGE_QUALITY}
            onValueChange={(q) => onChange({ ...values, imageQuality: q })}
          >
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {IMAGE_QUALITIES.map((q) => (
                <SelectItem key={q.value} value={q.value}>
                  {q.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </FormField>
      )}
      <FormField label="그림 연결" hint="선택하면 이 연결을 쓰는 위젯의 + 메뉴에 「그림 만들기」 가 보입니다.">
        {shown.length === 0 ? (
          <div className="text-xs text-muted-foreground">그림 모델이 있는 OpenAI 연결이 없습니다</div>
        ) : (
          <Select
            value={values.imageConnectionId || NONE}
            onValueChange={(id) => onChange({ ...values, imageConnectionId: id === NONE ? "" : id })}
          >
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE}>없음</SelectItem>
              {shown.map((o) => (
                <SelectItem key={o.id} value={o.id}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </FormField>
    </>
  );
}
