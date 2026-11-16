import { useState } from "react";
import { Input } from "../ui/input";
import { Button } from "../ui/button";
import { Modal } from "../common/Modal";
import { FormField } from "../common/FormField";
import type { TokenRecipients } from "../../types/cliAccountToken";
import { recipientErrorValue } from "./accountTokenState";

interface TokenRecipientsCardProps {
  description: string;
  recipients: TokenRecipients | undefined;
  loadError: boolean;
  onSave: (next: TokenRecipients) => Promise<unknown>;
}

const split = (s: string) => s.split(",").map((x) => x.trim()).filter(Boolean);

/** CR-196: 1년 토큰 만료 알림 수신자 카드와 고치기 창. 연결 관리와 실행기 계정 토큰 화면이 같이 쓴다. */
export function TokenRecipientsCard({ description, recipients, loadError, onSave }: TokenRecipientsCardProps) {
  const [open, setOpen] = useState(false);
  const [emails, setEmails] = useState("");
  const [phones, setPhones] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const openEditor = () => {
    setEmails((recipients?.emails ?? []).join(", "));
    setPhones((recipients?.phones ?? []).join(", "));
    setError(null);
    setOpen(true);
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await onSave({ emails: split(emails), phones: split(phones) });
      setOpen(false);
    } catch (e) {
      const bad = recipientErrorValue(e);
      setError(bad !== null ? `모양이 맞지 않습니다: ${bad}` : (e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const all = [
    ...(recipients?.emails ?? []).map((v) => ({ kind: "이메일", v })),
    ...(recipients?.phones ?? []).map((v) => ({ kind: "문자", v })),
  ];

  return (
    <div className="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card px-4 py-3 text-[13px]">
      <div>
        <div className="font-semibold text-foreground">만료 알림 수신자</div>
        <div className="mt-0.5 text-xs text-muted-foreground">{description}</div>
      </div>
      <div className="flex flex-1 flex-wrap gap-1.5 md:ml-4">
        {loadError ? (
          <span className="text-xs text-destructive">수신자를 불러오지 못했습니다.</span>
        ) : all.length === 0 ? (
          <span className="text-xs text-warning">수신자가 없습니다. 지금은 알림이 가지 않습니다.</span>
        ) : (
          all.map(({ kind, v }) => (
            <span key={`${kind}${v}`} className="rounded-full border border-border px-2.5 py-0.5 text-xs">
              {kind} {v}
            </span>
          ))
        )}
      </div>
      <Button variant="outline" size="sm" disabled={loadError} onClick={openEditor}>
        수신자 고치기
      </Button>

      <Modal open={open} onClose={() => setOpen(false)} title="만료 알림 수신자">
        <div className="mb-3 text-xs text-muted-foreground">쉼표로 여럿을 적습니다. 비운 쪽으로는 알림을 보내지 않습니다.</div>
        <FormField label="이메일">
          <Input placeholder="ops@company.com" value={emails} onChange={(e) => setEmails(e.target.value)} />
        </FormField>
        <FormField label="휴대폰 번호">
          <Input placeholder="010-1234-5678" value={phones} onChange={(e) => setPhones(e.target.value)} />
        </FormField>
        {error && <div className="mb-3 text-xs text-destructive">{error}</div>}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => setOpen(false)}>
            취소
          </Button>
          <Button variant="primary" disabled={saving} onClick={save}>
            저장
          </Button>
        </div>
      </Modal>
    </div>
  );
}
