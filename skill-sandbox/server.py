"""CR-147 skill-sandbox. 스킬 스크립트를 run 작업장 안에서만 실행한다(T3-17 3.7, BIZ-122).

비밀값과 바깥 인터넷이 없는 컨테이너에서 돈다. 실행할 파일과 작업 폴더는 작업장 뿌리 아래여야 하고,
실행 프로세스에는 정해 둔 환경변수만 넘긴다.
"""

import codecs
import hmac
import json
import os
import signal
import subprocess
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RUNNERS = {".py": ["python3"], ".js": ["node"], ".mjs": ["node"], ".sh": ["bash"]}
HOME_DIR = ".home"
CHANGED_FILES_LIMIT = 100
SNAPSHOT_LIMIT = 50000
MAX_BODY_BYTES = 1024 * 1024
READ_CHUNK = 65536
DEFAULT_TIMEOUT_SECONDS = 120
DEFAULT_OUTPUT_BYTES = 65536
# setsid 로 묶음을 벗어난 자식이 파이프를 쥐고 있어도 응답이 묶이지 않게 읽기를 이만큼만 더 기다린다.
PIPE_DRAIN_SECONDS = 5


class BadRequest(Exception):
    pass


class Config:
    def __init__(self, api_key, root, max_concurrency=2, max_timeout=600, max_output=1048576,
                 port=8300, node_path=None, browsers_path=None):
        if not api_key:
            raise ValueError("SKILL_SANDBOX_API_KEY is required")
        if not root:
            raise ValueError("SANDBOX_WORKSPACE_ROOT is required")
        self.api_key = api_key
        self.root = os.path.realpath(root)
        self.max_timeout = max_timeout
        self.max_output = max_output
        self.port = port
        self.node_path = node_path
        self.browsers_path = browsers_path
        self.slots = threading.BoundedSemaphore(max_concurrency)

    @staticmethod
    def from_env():
        env = os.environ
        return Config(
            api_key=env.get("SKILL_SANDBOX_API_KEY", "").strip(),
            root=env.get("SANDBOX_WORKSPACE_ROOT", "").strip(),
            max_concurrency=int(env.get("SANDBOX_MAX_CONCURRENCY", "2")),
            max_timeout=int(env.get("SANDBOX_MAX_TIMEOUT_SECONDS", "600")),
            max_output=int(env.get("SANDBOX_MAX_OUTPUT_BYTES", "1048576")),
            port=int(env.get("SANDBOX_PORT", "8300")),
            node_path=env.get("SANDBOX_NODE_PATH") or global_node_modules(),
            browsers_path=env.get("PLAYWRIGHT_BROWSERS_PATH"),
        )


def global_node_modules():
    try:
        out = subprocess.run(["npm", "root", "-g"], capture_output=True, text=True, timeout=30)
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def inside(path, base):
    return path == base or path.startswith(base + os.sep)


def _positive_int(value, name, default, upper):
    if value is None:
        return min(default, upper)
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise BadRequest(f"{name} must be a positive integer")
    return min(value, upper)


def validate(req, cfg):
    if not isinstance(req, dict):
        raise BadRequest("body must be a JSON object")
    workdir = req.get("workdir")
    script = req.get("script")
    if not isinstance(workdir, str) or not workdir.strip() or not isinstance(script, str) or not script.strip():
        raise BadRequest("workdir and script are required")
    if not os.path.isabs(workdir) or not os.path.isabs(script):
        raise BadRequest("workdir and script must be absolute paths")
    wd = os.path.realpath(workdir)
    sc = os.path.realpath(script)
    if wd == cfg.root or not inside(wd, cfg.root):
        raise BadRequest("workdir must be inside the workspace root")
    if not inside(sc, wd) or sc == wd:
        raise BadRequest("script must be inside workdir")
    if not os.path.isdir(wd):
        raise BadRequest("workdir does not exist")
    if not os.path.isfile(sc):
        raise BadRequest("script does not exist")
    ext = os.path.splitext(sc)[1].lower()
    runner = RUNNERS.get(ext)
    if runner is None:
        raise BadRequest(f"no runner for extension '{ext}'. Supported: {', '.join(sorted(RUNNERS))}")
    args = req.get("args") or []
    if not isinstance(args, list) or not all(isinstance(a, str) for a in args):
        raise BadRequest("args must be an array of strings")
    timeout = _positive_int(req.get("timeout_seconds"), "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, cfg.max_timeout)
    max_output = _positive_int(req.get("max_output_bytes"), "max_output_bytes", DEFAULT_OUTPUT_BYTES, cfg.max_output)
    return {"workdir": wd, "script": sc, "command": runner + [sc] + args,
            "timeout": timeout, "max_output": max_output}


class CappedReader(threading.Thread):
    """파이프를 끝까지 비우되 앞쪽 cap 바이트만 남긴다. 안 비우면 자식이 쓰기에서 멈춘다."""

    def __init__(self, pipe, cap):
        super().__init__(daemon=True)
        self.pipe = pipe
        self.cap = cap
        self.data = bytearray()
        self.total = 0

    def run(self):
        try:
            while True:
                chunk = self.pipe.read(READ_CHUNK)
                if not chunk:
                    break
                self.total += len(chunk)
                room = self.cap - len(self.data)
                if room > 0:
                    self.data.extend(chunk[:room])
        except (OSError, ValueError):
            pass

    def text(self):
        # 끝에서 잘린 UTF-8 글자는 버린다.
        return codecs.getincrementaldecoder("utf-8")("replace").decode(bytes(self.data), final=False)

    @property
    def truncated(self):
        return self.total > self.cap


def snapshot(workdir):
    files = {}
    for current, dirs, names in os.walk(workdir):
        if current == workdir and HOME_DIR in dirs:
            dirs.remove(HOME_DIR)
        for name in names:
            path = os.path.join(current, name)
            try:
                st = os.lstat(path)
            except OSError:
                continue
            files[os.path.relpath(path, workdir)] = (st.st_mtime_ns, st.st_size)
            if len(files) >= SNAPSHOT_LIMIT:
                return files
    return files


def kill_group(proc):
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def run_job(job, cfg):
    home = os.path.join(job["workdir"], HOME_DIR)
    os.makedirs(home, exist_ok=True)
    env = {"PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"), "HOME": home, "LANG": "C.UTF-8"}
    if cfg.node_path:
        env["NODE_PATH"] = cfg.node_path
    if cfg.browsers_path:
        env["PLAYWRIGHT_BROWSERS_PATH"] = cfg.browsers_path

    before = snapshot(job["workdir"])
    started = time.monotonic()
    proc = subprocess.Popen(job["command"], cwd=job["workdir"], env=env, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    out = CappedReader(proc.stdout, job["max_output"])
    err = CappedReader(proc.stderr, job["max_output"])
    out.start()
    err.start()
    timed_out = False
    try:
        proc.wait(timeout=job["timeout"])
    except subprocess.TimeoutExpired:
        timed_out = True
    # 스크립트가 띄운 백그라운드 자식이 파이프를 쥐고 남지 않게 끝난 뒤에도 묶음째 정리한다.
    kill_group(proc)
    proc.wait()
    out.join(PIPE_DRAIN_SECONDS)
    err.join(PIPE_DRAIN_SECONDS)
    proc.stdout.close()
    proc.stderr.close()
    duration_ms = int((time.monotonic() - started) * 1000)

    after = snapshot(job["workdir"])
    changed = sorted(path for path, stat in after.items() if before.get(path) != stat)
    return {
        "exit_code": proc.returncode,
        "timed_out": timed_out,
        "stdout": out.text(),
        "stderr": err.text(),
        "stdout_truncated": out.truncated,
        "stderr_truncated": err.truncated,
        "duration_ms": duration_ms,
        "changed_files": changed[:CHANGED_FILES_LIMIT],
        "changed_files_total": len(changed),
    }


def make_handler(cfg):
    class Handler(BaseHTTPRequestHandler):
        server_version = "skill-sandbox"

        def _send(self, status, body):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                self._send(HTTPStatus.OK, {"status": "ok"})
            else:
                self._send(HTTPStatus.NOT_FOUND, {"error": "not found"})

        def do_POST(self):
            if self.path != "/run":
                self._send(HTTPStatus.NOT_FOUND, {"error": "not found"})
                return
            key = self.headers.get("X-Sandbox-Key", "")
            if not hmac.compare_digest(key.encode("utf-8"), cfg.api_key.encode("utf-8")):
                self._send(HTTPStatus.UNAUTHORIZED, {"error": "invalid sandbox key"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > MAX_BODY_BYTES:
                    raise BadRequest("body is empty or too large")
                job = validate(json.loads(self.rfile.read(length)), cfg)
            except (BadRequest, ValueError) as e:
                self._send(HTTPStatus.BAD_REQUEST, {"error": str(e)})
                return
            with cfg.slots:
                try:
                    result = run_job(job, cfg)
                except OSError as e:
                    self._send(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": f"failed to start script: {e}"})
                    return
            self._send(HTTPStatus.OK, result)

    return Handler


def main():
    try:
        cfg = Config.from_env()
    except ValueError as e:
        print(f"skill-sandbox: {e}", file=sys.stderr)
        sys.exit(1)
    server = ThreadingHTTPServer(("0.0.0.0", cfg.port), make_handler(cfg))
    server.daemon_threads = True
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    print(f"skill-sandbox listening on {cfg.port}, workspace root {cfg.root}", file=sys.stderr, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
