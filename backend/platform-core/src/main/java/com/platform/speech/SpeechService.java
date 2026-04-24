package com.platform.speech;

/**
 * CR-060: OpenAI Whisper(STT) 호출을 추상화하는 서비스.
 *
 * {@link com.platform.api.SpeechController}(Platform JWT 경로)와
 * {@link com.platform.api.ChatSttController}(Widget 토큰 경로)가 공유한다.
 * 테넌트 OpenAI Connection 조회 + multipart body 조립 + HTTP 호출 + 응답 파싱이 구현 책임이다.
 */
public interface SpeechService {

    /**
     * 오디오 바이너리를 OpenAI Whisper 로 보내 텍스트로 변환한다.
     *
     * @param audio      오디오 바이너리 본문
     * @param mimeType   magic number 검증 완료된 MIME (e.g. audio/webm)
     * @param filename   OpenAI 요청 form 의 filename 필드에 사용 (확장자가 포맷 판정에 영향)
     * @param language   ISO-639-1 코드 또는 "auto"(Whisper 자동 감지)
     * @return 변환 결과
     * @throws SpeechException Connection 없음 / 업스트림 4xx·5xx / 타임아웃
     */
    TranscribeResult transcribe(byte[] audio, String mimeType, String filename, String language);

    record TranscribeResult(String text, String language, Double durationSec) {}
}
