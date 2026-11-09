# CR-098: aimbase-agent(Runner) 운영 docker 이미지
# Pre-built JAR 복사 런타임 + Claude Code CLI (Runner 가 claude 프로세스 spawn)
# 빌드: ./gradlew :aimbase-agent:bootJar 후 docker compose build aimbase-agent
# context = ./backend (platform-core Dockerfile 과 동일)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Node.js 18 LTS + PDF 도구 (Claude Code CLI 실행용) — platform-core 와 동일 구성
# postgresql-client: NATIVE 모드에서 CLI 가 Bash 로 소비앱 DB 를 직접 조회(psql)하기 위함.
#   MCP 도구로 감싸지 않고 접속정보만 쥐어주는 "클로드코드 방식" 자율 운영의 전제.
#   ※ 접근 통제는 DB 계정 권한(읽기전용 role)으로 건다 — 컨테이너에 psql 이 있다고 권한이 생기진 않는다.
RUN apt-get update && apt-get install -y curl poppler-utils postgresql-client git openssh-client && \
    curl -fsSL https://deb.nodesource.com/setup_18.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# CR-174: 수리 실행기가 코드 레포를 받아 가지를 밀고 PR 을 만든다(BIZ-145).
#   git 자격은 환경변수 GH_TOKEN 을 gh 가 대신 내주게 해 토큰이 파일에 남지 않는다.
#   토큰은 compose 가 수리 실행기(aimbase-agent-3plgw)에만 넣으므로 다른 실행기는 이미지에 gh 가 있어도 쓸 수 없다.
RUN mkdir -p -m 755 /etc/apt/keyrings && \
    curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /etc/apt/keyrings/githubcli-archive-keyring.gpg && \
    chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list && \
    apt-get update && apt-get install -y gh && \
    apt-get clean && rm -rf /var/lib/apt/lists/* && \
    git config --system credential.helper '!gh auth git-credential'

# CR-132: CLI 산출물 생성 런타임 (pptx/xlsx/pdf/docx).
#   파일 생성 능력은 Claude CLI 가 이미 가짐 — Bash 로 아래 라이브러리를 호출해 ./out/ 에 파일을 쓴다.
#   aimbase 는 도구를 새로 만들지 않고 "런타임을 깔아주는" 역할만 한다.
#   fonts-nanum: pptx/pdf 한글 깨짐 방지(reportlab/python-pptx 렌더 시 한글 폰트 필요).
#   --break-system-packages: bookworm(PEP 668) 외부 관리 환경에서 전역 pip 설치 허용(러너 전용 이미지라 안전).
RUN apt-get update && apt-get install -y python3 python3-pip fonts-nanum && \
    pip install --no-cache-dir --break-system-packages \
        python-pptx openpyxl reportlab python-docx && \
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
