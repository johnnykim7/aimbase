/**
 * 잡는 자리(CR-198). 창 바깥 가장자리에 보이지 않게 두고 커서만 바꾼다.
 * 떠 있는 위젯과 iframe 연결 요소(CR-203)가 같은 모양을 쓴다.
 */
export const RESIZE_HANDLE_CSS = `
.win-frame { position: fixed; pointer-events: none; z-index: 9999; }
.win-frame.side { top: 0; right: 0; bottom: 0; }
.rz { position: absolute; pointer-events: auto; touch-action: none; }
.rz-n, .rz-s { left: 8px; right: 8px; height: 6px; cursor: ns-resize; }
.rz-n { top: -6px; }
.rz-s { bottom: -6px; }
.rz-e, .rz-w { top: 8px; bottom: 8px; width: 6px; cursor: ew-resize; }
.rz-e { right: -6px; }
.rz-w { left: -6px; }
.win-frame.side .rz-w { top: 0; bottom: 0; }
.rz-nw, .rz-ne, .rz-sw, .rz-se { width: 14px; height: 14px; }
.rz-nw { left: -6px; top: -6px; cursor: nwse-resize; }
.rz-se { right: -6px; bottom: -6px; cursor: nwse-resize; }
.rz-ne { right: -6px; top: -6px; cursor: nesw-resize; }
.rz-sw { left: -6px; bottom: -6px; cursor: nesw-resize; }
`;
