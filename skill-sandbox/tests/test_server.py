"""CR-147 skill-sandbox 시험. TC-SKB-019, TC-SKB-020, TC-SKB-021 의 실행 컨테이너 쪽."""

import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


class SandboxTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.realpath(self.tmp.name)
        self.workdir = os.path.join(self.root, "tenant", "runs", "r1")
        os.makedirs(self.workdir)
        self.cfg = server.Config(api_key="k", root=self.root, max_timeout=600, max_output=1048576)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, content):
        path = os.path.join(self.workdir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        return path

    def run_script(self, rel, content, **extra):
        script = self.write(rel, content)
        req = {"workdir": self.workdir, "script": script}
        req.update(extra)
        return server.run_job(server.validate(req, self.cfg), self.cfg)

    def test_runs_python_and_reports_changed_files(self):
        result = self.run_script("job.py", "import os\nos.makedirs('out', exist_ok=True)\n"
                                           "open('out/a.md','w').write('x')\nprint('한글 ok')\n")
        self.assertEqual(result["exit_code"], 0)
        self.assertFalse(result["timed_out"])
        self.assertEqual(result["stdout"].strip(), "한글 ok")
        self.assertIn(os.path.join("out", "a.md"), result["changed_files"])
        self.assertNotIn("job.py", result["changed_files"])

    def test_args_and_nonzero_exit(self):
        result = self.run_script("job.sh", "echo \"$1-$2\"; exit 3\n", args=["a", "b c"])
        self.assertEqual(result["exit_code"], 3)
        self.assertEqual(result["stdout"].strip(), "a-b c")

    def test_TC_SKB_019_path_escape_is_rejected(self):
        outside = os.path.join(self.root, "x.py")
        with open(outside, "w") as f:
            f.write("print(1)\n")
        for script in [outside, os.path.join(self.workdir, "..", "..", "..", "x.py")]:
            with self.assertRaises(server.BadRequest):
                server.validate({"workdir": self.workdir, "script": script}, self.cfg)
        with self.assertRaises(server.BadRequest):
            server.validate({"workdir": self.root, "script": outside}, self.cfg)
        with self.assertRaises(server.BadRequest):
            server.validate({"workdir": "/etc", "script": "/etc/hosts"}, self.cfg)

    def test_TC_SKB_019_symlink_escape_is_rejected(self):
        outside = os.path.join(self.root, "evil.py")
        with open(outside, "w") as f:
            f.write("print(1)\n")
        link = os.path.join(self.workdir, "link.py")
        os.symlink(outside, link)
        with self.assertRaises(server.BadRequest):
            server.validate({"workdir": self.workdir, "script": link}, self.cfg)

    def test_TC_SKB_019_unknown_extension_is_rejected(self):
        script = self.write("job.rb", "puts 1\n")
        with self.assertRaises(server.BadRequest) as ctx:
            server.validate({"workdir": self.workdir, "script": script}, self.cfg)
        self.assertIn(".rb", str(ctx.exception))

    def test_TC_SKB_020_timeout_kills_process_group(self):
        started = time.monotonic()
        result = self.run_script("slow.sh", "sleep 30 &\nsleep 30\n", timeout_seconds=1)
        self.assertTrue(result["timed_out"])
        self.assertLess(time.monotonic() - started, 10)

    def test_background_child_does_not_hold_the_response(self):
        started = time.monotonic()
        result = self.run_script("bg.sh", "sleep 30 &\necho done\n")
        self.assertEqual(result["stdout"].strip(), "done")
        self.assertFalse(result["timed_out"])
        self.assertLess(time.monotonic() - started, 10)

    def test_TC_SKB_021_only_allowed_environment(self):
        os.environ["SECRET_DB_PASSWORD"] = "leak"
        try:
            result = self.run_script("env.py", "import os, json\nprint(json.dumps(sorted(os.environ)))\n")
        finally:
            del os.environ["SECRET_DB_PASSWORD"]
        keys = set(json.loads(result["stdout"]))
        self.assertNotIn("SECRET_DB_PASSWORD", keys)
        self.assertTrue(keys <= {"PATH", "HOME", "LANG", "NODE_PATH", "PLAYWRIGHT_BROWSERS_PATH",
                                 "LC_CTYPE", "__CF_USER_TEXT_ENCODING", "PWD"}, keys)

    def test_output_is_capped(self):
        result = self.run_script("big.py", "print('a' * 100000)\n", max_output_bytes=1000)
        self.assertEqual(len(result["stdout"]), 1000)
        self.assertTrue(result["stdout_truncated"])

    def test_cut_utf8_character_is_dropped(self):
        result = self.run_script("ko.py", "print('가' * 10)\n", max_output_bytes=4)
        self.assertEqual(result["stdout"], "가")

    def test_timeout_is_clamped_to_maximum(self):
        script = self.write("t.py", "print(1)\n")
        job = server.validate({"workdir": self.workdir, "script": script, "timeout_seconds": 99999}, self.cfg)
        self.assertEqual(job["timeout"], 600)
        with self.assertRaises(server.BadRequest):
            server.validate({"workdir": self.workdir, "script": script, "timeout_seconds": 0}, self.cfg)


class HttpTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = os.path.realpath(self.tmp.name)
        self.workdir = os.path.join(root, "w")
        os.makedirs(self.workdir)
        self.script = os.path.join(self.workdir, "a.py")
        with open(self.script, "w") as f:
            f.write("print('hi')\n")
        cfg = server.Config(api_key="secret", root=root)
        self.httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(cfg))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmp.cleanup()

    def post(self, body, key):
        req = urllib.request.Request(self.url + "/run", data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "X-Sandbox-Key": key})
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_key_and_run(self):
        status, _ = self.post({"workdir": self.workdir, "script": self.script}, "wrong")
        self.assertEqual(status, 401)
        status, body = self.post({"workdir": self.workdir, "script": self.script}, "secret")
        self.assertEqual(status, 200)
        self.assertEqual(body["stdout"].strip(), "hi")
        status, body = self.post({"workdir": self.workdir, "script": "/etc/hosts"}, "secret")
        self.assertEqual(status, 400)
        self.assertIn("inside workdir", body["error"])


if __name__ == "__main__":
    unittest.main()
