import { defineStrings } from "../../frame/i18n";

/**
 * CR-181 T3-3 「위젯 옆 창 문서 고치기」 화면 글. 화면 글은 「버전」, 설계서 글은 「판」이다.
 * 판 사이 이동, 칩의 ×, 보내기 단추는 위젯 본체에 이미 있는 이름을 쓴다.
 */
export const DOCUMENT_EDIT_STRINGS = defineStrings({
  ko: {
    prevVersion: "이전",
    nextVersion: "다음",
    viewing: (n: number, m: number) => `버전 ${n} 을 보고 있습니다. 마지막은 버전 ${m} 입니다`,
    restore: "이 버전으로 되돌리기",
    showLatest: "마지막 버전 보기",
    selected: (text: string) => `선택한 부분: "${text}"`,
    clearSelection: "취소",
    placeholder: "이 문서를 어떻게 고칠까요?",
    placeholderSelection: "선택한 부분을 어떻게 고칠까요?",
    send: "전송",
    editing: "문서를 고치는 중입니다",
    version: (n: number) => `버전 ${n}`,
    unchanged: "문서가 바뀌지 않았습니다",
    failed: "문서를 고치지 못했습니다",
    sameAsLatest: "마지막 버전과 같습니다",
    restoreFailed: "이 버전으로 되돌리지 못했습니다",
    sessionBusy: "앞의 응답이 아직 끝나지 않았습니다. 잠시 후 다시 시도하세요",
    errInvalid: "고칠 문서를 알지 못해 보내지 못했습니다",
    errSource: "처음 고치는 코드의 본문이 없어 보내지 못했습니다",
    errBaseVersion: "보고 있던 버전이 없어 고치지 못했습니다",
    errNotFound: "고칠 문서를 찾지 못했습니다",
    errForbidden: "다른 사람의 대화 문서입니다",
    errSourceGone: "파일이 정리되어 고치지 못합니다. 다시 만들어 주세요",
    errUnsupported: "이 연결은 이 파일을 고치지 못합니다",
  },
  en: {
    prevVersion: "Previous",
    nextVersion: "Next",
    viewing: (n: number, m: number) => `You're viewing version ${n}. The latest is version ${m}`,
    restore: "Restore this version",
    showLatest: "View latest version",
    selected: (text: string) => `Selected: "${text}"`,
    clearSelection: "Cancel",
    placeholder: "How should I edit this document?",
    placeholderSelection: "How should I edit the selected part?",
    send: "Send",
    editing: "Editing the document",
    version: (n: number) => `Version ${n}`,
    unchanged: "The document didn't change",
    failed: "Couldn't edit the document",
    sameAsLatest: "Same as the latest version",
    restoreFailed: "Couldn't restore this version",
    sessionBusy: "The previous response hasn't finished yet. Try again in a moment",
    errInvalid: "Couldn't tell which document to edit",
    errSource: "The code to edit was missing",
    errBaseVersion: "The version you were viewing no longer exists",
    errNotFound: "Couldn't find the document to edit",
    errForbidden: "This document belongs to someone else's conversation",
    errSourceGone: "The file was cleaned up and can't be edited. Please create it again",
    errUnsupported: "This connection can't edit this file",
  },
});

export type DocumentEditTexts = (typeof DOCUMENT_EDIT_STRINGS)["ko"];
