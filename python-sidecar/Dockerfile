FROM python:3.12-slim

WORKDIR /app

# System dependencies for unstructured + PDF/image parsing
# poppler-utils: pdf2image/unstructured PDF / libxcb1,libgl1,libglib2.0-0: 이미지 처리 런타임
# tesseract-ocr + tesseract-ocr-kor + tesseract-ocr-eng: 스캔 PDF/이미지 OCR (CR-092 — BIZ-106 언어 화이트리스트)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libpq-dev \
    poppler-utils \
    libxcb1 \
    libgl1 \
    libglib2.0-0 \
    tesseract-ocr \
    tesseract-ocr-kor \
    tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*

# Install uv for fast dependency management
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

# Copy project files
COPY pyproject.toml .
COPY src/ src/

# Install dependencies
RUN uv pip install --system -e ".[dev]"

EXPOSE 8000

CMD ["python", "-m", "rag_pipeline.server"]
