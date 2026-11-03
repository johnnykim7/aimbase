import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_KEY = "test-key"

# app 은 가져올 때 키와 목소리 폴더를 읽는다. 가져오기 전에 둔다.
os.environ["TTS_API_KEY"] = TEST_KEY
os.environ.setdefault("TTS_VOICES_DIR", str(ROOT / "voices"))
sys.path.insert(0, str(ROOT / "src"))
