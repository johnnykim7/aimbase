FROM node:18-alpine

# Claude Code CLI 설치
RUN npm install -g @anthropic-ai/claude-code

# PDF 도구 (Read tool용)
RUN apk add --no-cache poppler-utils

WORKDIR /app
COPY server.js .

# 작업 디렉토리 생성
RUN mkdir -p /data/workspace

USER node
EXPOSE 9100
CMD ["node", "server.js"]
