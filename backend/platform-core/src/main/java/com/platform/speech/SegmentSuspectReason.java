package com.platform.speech;

/**
 * CR-160: 변환 구간을 사람이 들어 봐야 하는 까닭. 변환 작업 구간의 {@code suspect_reason} 값이다.
 */
public enum SegmentSuspectReason {
    /** 모델이 소리 없는 창으로 본 곳에 글이 있다. 소리 없는 녹음에서 지어낸 글이 여기에 든다 */
    NO_SPEECH,
    /** 알아듣지 못할 때 모델이 흔히 지어내는 문구를 담았다 */
    KNOWN_PHRASE
}
