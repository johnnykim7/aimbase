"""CR-019: Python Code Executor Tool.

샌드박스 환경에서 Python 코드를 실행하는 MCP Tool.
- 데이터 가공, 계산, 파일 생성 등 범용 코드 실행
- timeout 제어, 출력 캡처, 생성 파일 base64 반환
"""

import base64
import logging
import os
import subprocess
import sys
import tempfile
from typing import Any

logger = logging.getLogger(__name__)

MAX_TIMEOUT = 300  # 최대 5분
MAX_OUTPUT_SIZE = 1_000_000  # 1MB


def run_python(code: str, timeout: int = 30) -> dict[str, Any]:
    """샌드박스 Python 코드 실행.

    Args:
        code: 실행할 Python 코드
        timeout: 타임아웃 (초, 최대 300)

    Returns:
        {stdout, stderr, files: [{name, base64, size_bytes}], exit_code, success}
    """
    if not code.strip():
        return {"success": False, "error": "Empty code"}

    timeout = min(max(timeout, 1), MAX_TIMEOUT)

    try:
        with tempfile.TemporaryDirectory() as tmpdir:
            script_path = os.path.join(tmpdir, "script.py")
            output_dir = os.path.join(tmpdir, "output")
            os.makedirs(output_dir)

            # OUTPUT_DIR 환경변수로 출력 경로 전달
            wrapped_code = f"import os\nOUTPUT_DIR = {repr(output_dir)}\nOUTPUT_PATH = os.path.join(OUTPUT_DIR, 'result')\n\n{code}"

            with open(script_path, "w") as f:
                f.write(wrapped_code)

            result = subprocess.run(
                [sys.executable, script_path],
                capture_output=True,
                text=True,
                timeout=timeout,
                cwd=tmpdir,
                env={**os.environ, "OUTPUT_DIR": output_dir},
            )

            stdout = result.stdout[:MAX_OUTPUT_SIZE] if result.stdout else ""
            stderr = result.stderr[:MAX_OUTPUT_SIZE] if result.stderr else ""

            # 생성된 파일 수집
            files = []
            for fname in os.listdir(output_dir):
                fpath = os.path.join(output_dir, fname)
                if os.path.isfile(fpath):
                    with open(fpath, "rb") as f:
                        content = f.read()
                    files.append({
                        "name": fname,
                        "base64": base64.b64encode(content).decode("utf-8"),
                        "size_bytes": len(content),
                    })

            logger.info("run_python: exit=%d, stdout=%d chars, files=%d",
                        result.returncode, len(stdout), len(files))

            return {
                "stdout": stdout,
                "stderr": stderr,
                "files": files,
                "exit_code": result.returncode,
                "success": result.returncode == 0,
            }

    except subprocess.TimeoutExpired:
        return {"success": False, "error": f"Execution timed out after {timeout}s"}
    except Exception as e:
        logger.error("run_python failed: %s", e)
        return {"success": False, "error": str(e)}
