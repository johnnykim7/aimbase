#!/usr/bin/env python3
"""실행기 계정 하나에 1년 토큰(claude setup-token)을 넣는다(CR-196, BIZ-283, 운용 가이드 시나리오 H).

실행기는 계정 폴더의 oauth-token.json 만 보고 CLAUDE_CODE_OAUTH_TOKEN 으로 CLI 에 넘긴다.
로그인 파일(.credentials.json)은 읽기만 하고 고치지 않는다.

맥, 리눅스 PC:
    python3 scripts/cli-token.py [계정 폴더]
setup-token 을 띄워 값을 받고, 붙여 넣은 값을 계정 폴더의 토큰 파일로 적은 뒤 짧은 질문 하나로 확인한다.
계정 폴더를 주지 않으면 CLAUDE_CONFIG_DIR, 그것도 없으면 ~/.claude 다. 실행기가 같은 순서로 찾는다.

서버는 chaws-bootstrap.sh token 단계가 실행기 컨테이너 안에서 이 파일을 돌린다.

토큰 값은 화면에 보이지 않게 받고 명령 인자, 기록, 출력에 싣지 않는다.
"""
import fcntl
import getpass
import json
import os
import pty
import re
import shutil
import subprocess
import sys
import tempfile
import termios
from datetime import datetime, timedelta, timezone

FILE_NAME = "oauth-token.json"
PREFIX = "sk-ant-oat01-"
# 실행기 ClaudeAccountToken.OVERRIDING_ENV 와 같다. 확인 질문도 실행기와 같은 인증으로 가게 뺀다.
OVERRIDING_ENV = ("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "CLAUDE_CODE_USE_BEDROCK",
                  "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
KST = timezone(timedelta(hours=9))
VERIFY_MODEL = os.environ.get("VERIFY_MODEL", "claude-haiku-5-5")
# setup-token 화면이 터미널의 붙여넣기 표시 모드(ESC[?2004h)를 켠 채 끝나면, 다음 붙여넣기 앞뒤에
# ESC[200~ 와 ESC[201~ 가 끼어 값이 sk-ant-oat01- 로 시작하지 않게 된다(2026-11-16 chaws work01).
# 받기 전에 그 모드를 끄고, 받은 글에서도 제어 문자를 걷어 토큰 모양만 골라낸다.
ESCAPES = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[@-Z\\-_]")
TOKEN = re.compile(re.escape(PREFIX) + r"[A-Za-z0-9_-]+")
STYLE = re.compile(r"\x1b\[[0-9;:]*m|\x1b\]8;[^\x07\x1b]*(?:\x07|\x1b\\)")
TRIES = 3


def account_dir(arg):
    if arg:
        return arg
    return os.environ.get("CLAUDE_CONFIG_DIR") or os.path.join(os.path.expanduser("~"), ".claude")


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def current(d):
    """이미 넣은 토큰 파일의 기한. 없거나 모양이 틀리면 None."""
    data = read_json(os.path.join(d, FILE_NAME))
    if not isinstance(data, dict) or not str(data.get("token", "")).startswith(PREFIX):
        return None
    return data.get("expires_at")


def adoptable(d):
    """로그인 파일에 갱신 값 없는 1년 토큰이 있으면 (토큰, 기한) 을 준다(CR-194 단계로 넣은 계정)."""
    data = read_json(os.path.join(d, ".credentials.json"))
    oauth = data.get("claudeAiOauth") if isinstance(data, dict) else None
    if not isinstance(oauth, dict):
        return None
    tok = str(oauth.get("accessToken") or "")
    if not tok.startswith(PREFIX) or oauth.get("refreshToken"):
        return None
    try:
        expires = datetime.fromtimestamp(int(oauth["expiresAt"]) / 1000, KST)
    except (KeyError, TypeError, ValueError):
        return None
    return tok, expires


def write(d, tok, issued, expires):
    os.makedirs(d, mode=0o700, exist_ok=True)
    path = os.path.join(d, FILE_NAME)
    tmp = path + ".new"
    body = json.dumps({"token": tok,
                       "issued_at": issued.isoformat(timespec="seconds"),
                       "expires_at": expires.isoformat(timespec="seconds")})
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(body + "\n")
    os.replace(tmp, path)
    os.chmod(path, 0o600)


def verify(d, tok):
    """실행기와 같은 환경으로 짧은 질문 하나를 던져 본다. (통과 여부, 마지막 줄)."""
    env = {k: v for k, v in os.environ.items() if k not in OVERRIDING_ENV and k != "CLAUDECODE"}
    env["CLAUDE_CONFIG_DIR"] = d
    env["CLAUDE_CODE_OAUTH_TOKEN"] = tok
    try:
        r = subprocess.run(["claude", "-p", "ok 라고만 답하세요", "--model", VERIFY_MODEL],
                           env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=120)
        out = (r.stdout.strip() or r.stderr.strip()).splitlines()
        return r.returncode == 0, (out[-1] if out else "(빈 답)")[:200]
    except (OSError, subprocess.TimeoutExpired) as e:
        return False, "확인 못 함: " + type(e).__name__


def saved_token(d):
    data = read_json(os.path.join(d, FILE_NAME)) or {}
    return str(data.get("token", ""))


def token_from_screen(text):
    """setup-token 화면 출력에서 마지막에 찍힌 토큰을 골라낸다. 못 찾으면 빈 문자열.
    CLI 화면은 빈칸을 쓰지 않고 커서만 옮겨 다음 글을 찍는다. 커서 이동을 지우기만 하면 토큰 뒤에 다음 줄
    「Store」 가 붙어 들어갔다(2026-11-16 chaws wms work01). 글자 모양(색, 굵기, 링크)만 지우고 나머지 제어는
    줄바꿈으로 보아 토큰을 한 줄 안에서만 잡는다. 화면 폭이 좁아 꺾였으면 확인에서 걸러져 붙여 넣기로 넘어간다."""
    text = STYLE.sub("", text)
    text = ESCAPES.sub("\n", text).replace("\r", "\n")
    found = TOKEN.findall(text)
    return found[-1] if found else ""


def run_on_screen(argv, env):
    """명령을 사람의 터미널에 그대로 보이며 돌리고, 화면에 나간 글을 돌려준다.
    가상 터미널로 돌려 끝나면 터미널 모드가 원래대로 돌아온다."""
    seen = bytearray()
    sized = []

    def read(fd):
        # 가상 터미널은 화면 폭을 모른다. 사람 터미널 폭을 넘겨야 승인 주소와 토큰이 80칸에서 꺾이지 않는다.
        if not sized:
            sized.append(True)
            try:
                fcntl.ioctl(fd, termios.TIOCSWINSZ, fcntl.ioctl(sys.stdin.fileno(), termios.TIOCGWINSZ, b"\0" * 8))
            except OSError:
                pass
        data = os.read(fd, 4096)
        seen.extend(data)
        return data

    saved = dict(os.environ)
    os.environ.clear()
    os.environ.update(env)
    try:
        pty.spawn(argv, read)
    finally:
        os.environ.clear()
        os.environ.update(saved)
    return seen.decode("utf-8", "replace")


def setup_token():
    """setup-token 을 띄우고 찍힌 값을 화면에서 바로 받는다. 받지 못하면 빈 문자열(그때는 붙여 넣는다).
    값을 복사해 붙여 넣던 단계에서 setup-token 이 남긴 입력 찌꺼기 때문에 빈 값이 들어가 실패했다(2026-11-16 chaws work01).
    계정 폴더에 아무것도 남기지 않게 빈 폴더에서 띄운다."""
    print("[token] 찍힌 주소를 브라우저에서 이 계정으로 승인하고, 받은 코드를 붙여 넣습니다.")
    print("[token] 끝나면 찍히는 sk-ant-oat01- 값은 이 스크립트가 바로 받습니다.")
    tmp = tempfile.mkdtemp(prefix="setup-token-")
    try:
        screen = run_on_screen(["claude", "setup-token"], dict(os.environ, CLAUDE_CONFIG_DIR=tmp))
    except OSError as e:
        print("[token] claude 를 띄우지 못했습니다: " + type(e).__name__)
        return ""
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return token_from_screen(screen)


def drop_pending_input():
    """앞 프로그램이 남긴 입력(빈 줄 등)을 버린다. 남아 있으면 토큰 입력 칸이 빈 값으로 바로 넘어간다."""
    try:
        with open("/dev/tty") as t:
            termios.tcflush(t.fileno(), termios.TCIFLUSH)
    except (OSError, termios.error):
        pass


def pick_token(raw):
    """붙여 넣은 글에서 토큰만 골라낸다. 없으면 빈 문자열."""
    m = TOKEN.search(ESCAPES.sub("", raw))
    return m.group(0) if m else ""


def read_token():
    """토큰을 화면에 보이지 않게 받는다. 모양이 틀리면 값은 찍지 않고 무엇이 왔는지만 알리고 다시 받는다."""
    for _ in range(TRIES):
        drop_pending_input()
        if sys.stdout.isatty():
            sys.stdout.write("\x1b[?2004l")
            sys.stdout.flush()
        raw = getpass.getpass("[token] claude setup-token 이 찍은 sk-ant-oat01- 값을 붙여 넣고 Enter"
                              "(화면에 보이지 않습니다): ")
        tok = pick_token(raw)
        if tok:
            return tok
        print("[token] 받은 글 %d자에 sk-ant-oat01- 값이 없습니다(제어 문자 %d개). 값만 다시 붙여 넣어 주세요"
              % (len(raw), len(ESCAPES.findall(raw))))
    return ""


def ask(prompt):
    try:
        return input(prompt).strip().lower()
    except EOFError:
        return ""


def main():
    args = sys.argv[1:]
    d = account_dir(args[0] if args else None)
    days = int(os.environ.get("TOKEN_DAYS", "365"))
    now = datetime.now(KST)
    print("[token] 계정 폴더 " + d)

    expires_at = current(d)
    if expires_at:
        ok, said = verify(d, saved_token(d))
        if not ok:
            print("[token] 지금 토큰(기한 %s)으로 Claude 가 답하지 않습니다(%s). 새 값으로 바꿉니다" % (expires_at, said))
        elif ask("[token] 이미 토큰이 있고 답도 합니다(기한 %s). 새 값으로 바꿀까요? [y/N] " % expires_at) != "y":
            print("[token] 그대로 둡니다. 확인: " + said)
            return

    # 확인을 통과한 값만 파일에 적는다. 틀린 값으로 기존 토큰을 덮지 않는다.
    old = None if expires_at else adoptable(d)
    if old and ask("[token] 로그인 파일에 1년 토큰이 있습니다(기한 %s). 토큰 파일로 옮길까요? [Y/n] "
                   % old[1].date()) in ("", "y"):
        tok, expires = old
        ok, said = verify(d, tok)
        if not ok:
            print("[token] 로그인 파일의 토큰으로 답하지 않아 옮기지 않았습니다: " + said)
            sys.exit(1)
        write(d, tok, expires - timedelta(days=days), expires)
        print("[token] 옮김. 기한 %s. 로그인 파일은 그대로 둡니다. 확인: %s" % (expires.date(), said))
        return

    tok = ""
    if ask("[token] claude setup-token 을 지금 띄울까요? 이미 값을 받았으면 n [Y/n] ") in ("", "y"):
        tok = setup_token()
        if tok:
            ok, said = verify(d, tok)
            if not ok:
                print("[token] 화면에서 받은 값으로 확인이 되지 않습니다(%s). 찍힌 값을 붙여 넣어 주세요" % said)
                tok = ""
        else:
            print("[token] 화면에서 값을 찾지 못했습니다. 찍힌 값을 붙여 넣어 주세요")
    if not tok:
        tok = read_token()
        if not tok:
            print("[token] sk-ant-oat01- 값을 받지 못해 넣지 않았습니다")
            sys.exit(1)
        ok, said = verify(d, tok)
        if not ok:
            print("[token] 이 값으로 Claude 가 답하지 않아 넣지 않았습니다: " + said)
            sys.exit(1)
    write(d, tok, now, now + timedelta(days=days))
    print("[token] 넣음. 기한 %s. 확인: %s" % ((now + timedelta(days=days)).date(), said))


if __name__ == "__main__":
    main()
