# CR-147 skill-sandbox: 스킬 스크립트 실행 컨테이너 (T3-17 2.4, 3.7)
# 운영에서는 인터넷이 없는 내부 망에 붙으므로 스크립트가 쓸 패키지는 여기서 미리 넣는다.
FROM python:3.12-slim-bookworm

# uid 는 api 와 같은 999 로 고정한다. 작업장 볼륨(호스트 999:999)을 api 와 같이 읽고 쓴다.
# 뒤의 apt 설치가 시스템 계정을 만들며 999 를 먼저 가져가지 않게 계정을 맨 앞에서 만든다.
# gid 는 볼륨 접근에 필요 없어 자동 할당으로 둔다(platform-core Dockerfile 과 같은 이유).
RUN groupadd -r sandbox && useradd -r -u 999 -g sandbox -m sandbox

RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm fonts-noto-cjk \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# pyhwp 는 six 를 쓰면서 의존성에 적지 않아 hwp5txt 가 import 에서 죽는다(실측). 직접 넣는다.
RUN pip install --no-cache-dir \
    pyhwp==0.1b15 six==1.16.0 python-docx==1.1.2 openpyxl==3.1.5 pillow==11.0.0 playwright==1.48.0

# 실행 프로세스의 HOME 은 run 작업장 안이라 브라우저를 HOME 밑에 두면 찾지 못한다.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN playwright install --with-deps chromium && chmod -R a+rX /ms-playwright

RUN npm install -g docx@9.0.2 && npm cache clean --force

WORKDIR /app
COPY server.py /app/server.py

USER sandbox
EXPOSE 8300
CMD ["python3", "/app/server.py"]
