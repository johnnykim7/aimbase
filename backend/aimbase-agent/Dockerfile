# CR-098: aimbase-agent(Runner) 운영 docker 이미지
# Pre-built JAR 복사 런타임 + Claude Code CLI (Runner 가 claude 프로세스 spawn)
# 빌드: ./gradlew :aimbase-agent:bootJar 후 docker compose build aimbase-agent
# context = ./backend (platform-core Dockerfile 과 동일)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Node.js 18 LTS + PDF 도구 (Claude Code CLI 실행용) — platform-core 와 동일 구성
RUN apt-get update && apt-get install -y curl poppler-utils && \
    curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Claude Code CLI 설치 (Runner 가 spawn 하는 바이너리)
RUN npm install -g @anthropic-ai/claude-code

RUN groupadd -r appuser && useradd -r -g appuser -m appuser

# Claude 계정 디렉토리 (CLAUDE_CONFIG_DIR / config_dir — api 와 동일 볼륨 공유)
RUN mkdir -p /home/appuser/.claude-accounts && chown appuser:appuser /home/appuser/.claude-accounts

# AIMBASE/HYBRID tool-mode 시 CLI 가 자식 프로세스로 띄우는 MCP jar = 자기 자신
COPY aimbase-agent/build/libs/*.jar app.jar
RUN chown appuser:appuser app.jar

USER appuser

EXPOSE 8290

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
