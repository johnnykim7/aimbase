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

RUN groupadd -r appuser && useradd -r -g appuser -m appuser

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
