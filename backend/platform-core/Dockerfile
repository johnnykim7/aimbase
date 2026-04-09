# Pre-built JAR를 복사하는 런타임 이미지 + Claude Code CLI
# 빌드: ./gradlew :platform-core:bootJar 후 docker compose up
FROM eclipse-temurin:21-jre
WORKDIR /app

# Node.js 18 LTS + PDF 도구 설치 (Claude Code CLI 실행용)
RUN apt-get update && apt-get install -y curl poppler-utils && \
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

COPY platform-core/build/libs/*.jar app.jar
RUN chown appuser:appuser app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
