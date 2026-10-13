import { useState } from "react";
import { X } from "lucide-react";
import { Button } from "../ui/button";
import { Checkbox } from "../ui/checkbox";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "../ui/select";
import { useSkillBundle, useSkillBundleVersion, useSkillBundles } from "../../hooks/useSkillBundles";
import type { SkillBundleSummary, SkillPin } from "../../types/skillBundle";
import { SCOPE_LABEL } from "../skills/skillBundleText";

const BASE = "/skill-bundles";
const LATEST = "latest";

/** config.skills 는 패널 안에서 JSON 글로 들고 있다. 비었으면 빈 글. */
function parsePins(raw: string): SkillPin[] | null {
  if (!raw.trim()) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

// 최신이면 version 을, 고른 파일이 없으면 files 를 적지 않는다(T3-17 3.6)
function serialize(pins: SkillPin[]): string {
  const clean = pins
    .filter((p) => p.name)
    .map((p) => ({
      name: p.name,
      ...(p.version ? { version: p.version } : {}),
      ...(p.files && p.files.length > 0 ? { files: p.files } : {}),
    }));
  return clean.length === 0 ? "" : JSON.stringify(clean, null, 2);
}

interface SkillPinsFieldProps {
  value: string;
  onChange: (next: string) => void;
  stepKind: "LLM_CALL" | "AGENT_CALL";
}

export function SkillPinsField({ value, onChange, stepKind }: SkillPinsFieldProps) {
  const parsed = parsePins(value);
  // 이름을 아직 안 고른 줄은 저장하지 않으므로 줄 목록은 여기서 따로 든다
  const [rows, setRows] = useState<SkillPin[]>(parsed ?? []);
  const { data: bundles = [], isLoading } = useSkillBundles(BASE);
  // 비활성이나 가려진 공용은 단계 실행 때 쓰이지 않아 고를 수 없게 한다(BIZ-120)
  const usable = bundles.filter((b) => b.is_active && !b.shadowed);

  if (parsed === null) {
    return (
      <div className="flex flex-col gap-2 text-[11px]">
        <div className="text-destructive">스킬 지정 형식이 올바르지 않습니다.</div>
        <Button variant="outline" size="sm" onClick={() => { setRows([]); onChange(""); }}>
          지정 삭제
        </Button>
      </div>
    );
  }

  const update = (next: SkillPin[]) => {
    setRows(next);
    onChange(serialize(next));
  };

  return (
    <div className="flex flex-col gap-2">
      {rows.map((pin, i) => (
        <SkillPinRow
          key={i}
          pin={pin}
          usable={usable}
          loading={isLoading}
          taken={rows.filter((_, j) => j !== i).map((r) => r.name)}
          onChange={(p) => update(rows.map((r, j) => (j === i ? p : r)))}
          onRemove={() => update(rows.filter((_, j) => j !== i))}
        />
      ))}
      <Button variant="dashed" size="sm" onClick={() => setRows([...rows, { name: "" }])}>
        스킬 추가
      </Button>
      <div className="text-[10px] text-muted-foreground leading-relaxed">
        {stepKind === "LLM_CALL"
          ? "이 단계는 스크립트를 돌리지 않습니다. 안내서와 선택한 파일이 지시문에 붙습니다."
          : "에이전트가 스킬을 열어 읽고 스크립트를 돌립니다. 선택한 파일은 처음부터 붙습니다."}
      </div>
    </div>
  );
}

interface SkillPinRowProps {
  pin: SkillPin;
  usable: SkillBundleSummary[];
  loading: boolean;
  taken: string[];
  onChange: (pin: SkillPin) => void;
  onRemove: () => void;
}

function SkillPinRow({ pin, usable, loading, taken, onChange, onRemove }: SkillPinRowProps) {
  const bundle = usable.find((b) => b.name === pin.name);
  const { data: detail } = useSkillBundle(BASE, bundle?.name, bundle?.scope);
  const shownVersion = pin.version ?? bundle?.latest_version;
  const { data: versionDetail } = useSkillBundleVersion(BASE, bundle?.name, shownVersion, bundle?.scope);

  const textFiles = (versionDetail?.files ?? []).filter((f) => f.is_text).map((f) => f.path);
  const picked = pin.files ?? [];
  const missing = versionDetail ? picked.filter((f) => !textFiles.includes(f)) : [];
  const unknownName = !!pin.name && !loading && !bundle;
  const versionMissing = !!pin.version && !!detail && !detail.versions.some((v) => v.version === pin.version);

  const toggleFile = (path: string) =>
    onChange({
      ...pin,
      files: picked.includes(path) ? picked.filter((f) => f !== path) : [...picked, path],
    });

  return (
    <div className="flex flex-col gap-1.5 rounded-lg border border-border p-2">
      <div className="flex items-center gap-1.5">
        <Select value={pin.name || undefined} onValueChange={(name) => onChange({ name })}>
          <SelectTrigger size="sm" className="flex-1 min-w-0">
            <SelectValue placeholder={loading ? "불러오는 중" : "스킬을 선택하세요"} />
          </SelectTrigger>
          <SelectContent>
            {usable
              .filter((b) => !taken.includes(b.name))
              .map((b) => (
                <SelectItem key={b.name} value={b.name}>
                  {b.name} ({SCOPE_LABEL[b.scope]})
                </SelectItem>
              ))}
            {unknownName && <SelectItem value={pin.name}>{pin.name} (쓸 수 없음)</SelectItem>}
          </SelectContent>
        </Select>
        <Select
          value={pin.version ? String(pin.version) : LATEST}
          onValueChange={(v) => onChange({ ...pin, version: v === LATEST ? undefined : Number(v) })}
          disabled={!bundle}
        >
          <SelectTrigger size="sm" className="w-[104px] shrink-0">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={LATEST}>최신</SelectItem>
            {(detail?.versions ?? []).map((v) => (
              <SelectItem key={v.version} value={String(v.version)}>
                판 {v.version}
              </SelectItem>
            ))}
            {versionMissing && <SelectItem value={String(pin.version)}>판 {pin.version} (없음)</SelectItem>}
          </SelectContent>
        </Select>
        <Button variant="ghost" size="icon" className="size-7" onClick={onRemove} aria-label="삭제">
          <X />
        </Button>
      </div>

      {unknownName && (
        <div className="text-[10px] text-destructive">비활성이거나 없는 스킬이라 이 단계가 실패합니다.</div>
      )}
      {versionMissing && (
        <div className="text-[10px] text-destructive">판 {pin.version} 이 없어 이 단계가 실패합니다.</div>
      )}

      {bundle && (
        <>
          <div className="text-[10px] text-muted-foreground">
            미리 붙일 파일{pin.version ? "" : ` (지금 최신 판 ${bundle.latest_version} 기준)`}
          </div>
          <div className="max-h-[132px] overflow-y-auto rounded-md border border-border bg-background px-2 py-1">
            {textFiles.length === 0 ? (
              <div className="text-[11px] text-muted-foreground py-0.5">붙일 수 있는 파일이 없습니다</div>
            ) : (
              textFiles.map((path) => (
                <label key={path} className="flex items-center gap-1.5 py-0.5 text-[11px] font-mono cursor-pointer">
                  <Checkbox size="sm" checked={picked.includes(path)} onCheckedChange={() => toggleFile(path)} />
                  <span className="truncate">{path}</span>
                </label>
              ))
            )}
          </div>
          {missing.length > 0 && (
            <div className="flex items-center gap-1.5 text-[10px] text-destructive">
              <span className="flex-1">선택한 판에 없는 파일이라 이 단계가 실패합니다: {missing.join(", ")}</span>
              <Button
                variant="outline"
                size="sm"
                onClick={() => onChange({ ...pin, files: picked.filter((f) => !missing.includes(f)) })}
              >
                제외
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
