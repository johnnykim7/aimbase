# CR-166 같은 목소리 음성 생성기(CR-166 3.1).
# 모델(4.3GB)은 이미지에 넣지 않고 처음 기동 때 /models 볼륨에 받는다. 이미지를 다시 만들어도 다시 받지 않는다.
FROM python:3.13-slim-bookworm

# 이름 있는 볼륨은 처음 만들 때 이미지 안 /models 의 소유자를 따라가므로 계정과 폴더를 먼저 만든다.
RUN groupadd -r tts && useradd -r -u 999 -g tts -m tts && mkdir /models && chown tts:tts /models

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# 기본 색인의 torch 는 GPU 판이라 수 GB 가 더 붙는다. CPU 판을 먼저 넣어 두면 뒤의 설치가 다시 받지 않는다.
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.15.0 torchaudio==2.11.1

WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt \
    && pip install --no-cache-dir --no-deps qwen-tts==0.1.1

COPY server.py /app/server.py
COPY voices /app/voices

ENV HF_HOME=/models TTS_THREADS=8
# 할당기 조각을 줄인다. 만든 소리는 비트까지 같고 문장 20개 뒤 메모리가 9.6GB 에서 8.5GB 로 준다(CR-167 7절).
ENV MALLOC_ARENA_MAX=2 MALLOC_MMAP_THRESHOLD_=1048576 MALLOC_TRIM_THRESHOLD_=1048576
USER tts
EXPOSE 8310
CMD ["uvicorn", "server:build_app", "--factory", "--host", "0.0.0.0", "--port", "8310"]
