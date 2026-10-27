# Pre-built JAR를 복사하는 런타임 이미지 + Claude Code CLI
# 빌드: ./gradlew :platform-core:bootJar 후 docker compose up
FROM eclipse-temurin:21-jre
WORKDIR /app

# Node.js 18 LTS + PDF 도구 + ffmpeg 설치
#   poppler-utils : PDF 페이지 이미지화 (CR-101/CR-120)
#   ffmpeg        : CR-137 영상 프레임 추출 (ffmpeg/ffprobe 둘 다 이 패키지에 포함).
#                   BE 가 직접 exec 한다 — 사이드카 경유는 중개 낭비라는 CR-120 결론을 따른다.
#                   기존 ffmpeg 는 transcribe-sidecar(사용자 맥, 오디오 전용)에만 있어
#                   운영 서버 Java 컨테이너에서는 쓸 수 없었다.
RUN apt-get update && apt-get install -y curl poppler-utils ffmpeg && \
    curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Claude Code CLI 설치
RUN npm install -g @anthropic-ai/claude-code

# ⚠️ uid/gid 를 999 로 명시 고정한다 — 자동 할당에 맡기면 안 된다.
# 위 apt 설치(ffmpeg 의존)가 systemd 계열 계정을 먼저 만들어 995~998 을 차지하는 바람에,
# `useradd -r`(SYS_UID_MAX 부터 내려가며 할당)이 994 로 밀린 적이 있다(2026-08-20 재빌드).
# 그런데 호스트 볼륨(./workspace, ./claude-accounts)은 999:999 이고 러너 이미지도 999 라,
# api 만 994 가 되어 /data/workspace/chat 쓰기가 전부 Permission denied 로 죽었다
# (증상: "[CR132] 채팅 workspace 자동할당 실패" 가 세션마다 발생, 산출물 파일 기능 정지).
# 베이스 이미지 구성이 바뀌어도 uid 가 흔들리지 않도록 고정한다. 999 가 이미 점유돼 있으면
# 빌드가 여기서 실패하는데, 그게 조용히 드리프트하는 것보다 낫다.
#
# ⚠️ gid 는 999 로 고정하지 않는다 — 이 이미지에서 gid 999 는 systemd-journal 이 이미 쓰고 있어
# `groupadd -g 999` 는 "GID '999' already exists" 로 빌드가 죽는다(실측). 볼륨 접근에 필요한 것은
# 소유자 uid 뿐이다(호스트 dir 이 999:999, 모드 755 → uid 만 맞으면 rwx). gid 는 자동 할당으로 둔다.
RUN groupadd -r appuser && useradd -r -u 999 -g appuser -m appuser

# 계정별 Claude 설정 디렉토리 (CLAUDE_CONFIG_DIR 방식으로 다중 계정 격리)
RUN mkdir -p /home/appuser/.claude-accounts && chown appuser:appuser /home/appuser/.claude-accounts

# 작업 디렉토리
RUN mkdir -p /data/workspace && chown appuser:appuser /data/workspace

# 업로드 스토리지 (LocalStorageService storage.local.base-path)
# CR-137: 이 mkdir 이 없으면 docker 가 볼륨 마운트 시점에 root:root 로 자동 생성하고,
# 컨테이너는 appuser(999) 로 돌기 때문에 업로드가 전부 Permission denied 로 실패한다.
# (실제로 Phase 0 배포에서 발생 → 수동 chown 으로 임시 해소했고, 여기서 영구 해결한다)
RUN mkdir -p /data/aimbase && chown appuser:appuser /data/aimbase

COPY platform-core/build/libs/*.jar app.jar
RUN chown appuser:appuser app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
