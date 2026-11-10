# SPDX-License-Identifier: GPL-3.0-only
"""Tests of aitel_setup.py against a real relay on 127.0.0.1: python -m unittest (run in this folder)."""

import contextlib
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "mcp"))

import aitel_relay as relay  # noqa: E402
import aitel_setup as setup_tool  # noqa: E402

PUBLIC = "https://relay.example.test"
# A second URL for the tests that change it
OTHER_PUBLIC = "https://other.example.test"


def run(*args):
    """The tool as a command: (exit code, what it printed)."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
        code = setup_tool.main(list(args))
    return code, out.getvalue()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class FakePhone:
    """Polls with the device token and answers every request as the phone would for a missing field."""

    def __init__(self, base, token):
        self.base = base
        self.token = token
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.commands = []

    def call(self, path, body=None):
        request = urllib.request.Request(self.base + path, data=body, headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as reply:
            return reply.read()

    def loop(self):
        while not self.stop.is_set():
            data = json.loads(self.call("/phone/next?instance=test&wait=1"))
            for command in data["commands"]:
                self.commands.append(command)
                reply = {"ok": True, "id": command["id"], "status": "needs_info", "questions": [{"field": "phone_number", "question": "電話番号は？"}]}
                self.call("/phone/reply", json.dumps({"cmd": command["cmd"], "reply": reply}).encode("utf-8"))

    def start(self):
        self.thread.start()
        return self

    def close(self):
        self.stop.set()
        self.thread.join(5)


class WithFolder(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp(prefix="aitel-setup-")
        self.addCleanup(shutil.rmtree, self.folder, True)
        self.env = os.path.join(self.folder, "relay.env")

    def read_env(self, path=None):
        return setup_tool.read_env(path or self.env)

    def written(self, path=None):
        with open(path or self.env, encoding="utf-8") as f:
            return f.read()


class InitTest(WithFolder):
    def init(self, *more):
        return run("init", PUBLIC, "--env", self.env, *more)

    def test_makes_two_different_long_tokens_and_keeps_the_url(self):
        code, _ = self.init()
        self.assertEqual(0, code)
        values = self.read_env()
        self.assertGreaterEqual(len(values["AITEL_AI_TOKEN"]), relay.MIN_TOKEN_CHARS)
        self.assertGreaterEqual(len(values["AITEL_PHONE_TOKEN"]), relay.MIN_TOKEN_CHARS)
        self.assertNotEqual(values["AITEL_AI_TOKEN"], values["AITEL_PHONE_TOKEN"])
        self.assertEqual(PUBLIC, values["AITEL_PUBLIC_URL"])

    def test_the_relay_accepts_what_it_wrote(self):
        self.init()
        config = relay.Config.from_environment(self.read_env())
        self.assertEqual(PUBLIC, config.public_url)

    def test_says_which_token_goes_where(self):
        _, out = self.init()
        values = self.read_env()
        self.assertIn(values["AITEL_AI_TOKEN"], out)
        self.assertIn(values["AITEL_PHONE_TOKEN"], out)
        self.assertLess(out.index("Grok"), out.index(values["AITEL_AI_TOKEN"]))
        self.assertLess(out.index("スマホ"), out.index(values["AITEL_PHONE_TOKEN"]))
        self.assertIn(f"{PUBLIC}/mcp", out)

    def test_never_overwrites_what_is_there(self):
        self.init()
        before = self.written()
        code, out = self.init()
        self.assertNotEqual(0, code)
        self.assertIn("rotate", out)
        self.assertEqual(before, self.written())

    def test_a_trailing_slash_is_dropped(self):
        run("init", PUBLIC + "/", "--env", self.env)
        self.assertEqual(PUBLIC, self.read_env()["AITEL_PUBLIC_URL"])

    def test_refuses_a_url_it_cannot_use(self):
        for bad in ("http://relay.example.test", "relay.example.test", "https://", "https://relay.example.test/mcp", "https://a b.example.test"):
            code, _ = run("init", bad, "--env", self.env)
            self.assertNotEqual(0, code, bad)
            self.assertFalse(os.path.exists(self.env), bad)

    @unittest.skipIf(os.name == "nt", "POSIX permissions")
    def test_only_the_owner_can_read_it_on_posix(self):
        previous = os.umask(0)
        self.addCleanup(os.umask, previous)
        self.init()
        self.assertEqual(0o600, os.stat(self.env).st_mode & 0o777)

    @unittest.skipUnless(os.name == "nt", "Windows permissions")
    def test_only_the_owner_can_read_it_on_windows(self):
        self.init()
        listed = subprocess.run(["icacls", self.env], stdout=subprocess.PIPE, check=True).stdout.decode("mbcs", "replace")
        entries = [line for line in listed.splitlines() if ":(" in line]
        self.assertEqual(1, len(entries), listed)
        self.assertNotIn("(I)", entries[0], "inherited permissions are gone")


class ChangesTest(WithFolder):
    def setUp(self):
        super().setUp()
        run("init", PUBLIC, "--env", self.env)
        self.before = self.read_env()

    def test_tokens_shows_them_again_and_changes_nothing(self):
        text = self.written()
        code, out = run("tokens", "--env", self.env)
        self.assertEqual(0, code)
        self.assertIn(self.before["AITEL_AI_TOKEN"], out)
        self.assertIn(self.before["AITEL_PHONE_TOKEN"], out)
        self.assertEqual(text, self.written())

    def test_rotate_phone_changes_only_that_token(self):
        code, out = run("rotate", "phone", "--env", self.env)
        self.assertEqual(0, code)
        after = self.read_env()
        self.assertNotEqual(self.before["AITEL_PHONE_TOKEN"], after["AITEL_PHONE_TOKEN"])
        self.assertEqual(self.before["AITEL_AI_TOKEN"], after["AITEL_AI_TOKEN"])
        self.assertEqual(PUBLIC, after["AITEL_PUBLIC_URL"])
        self.assertIn(after["AITEL_PHONE_TOKEN"], out)
        self.assertNotIn(after["AITEL_AI_TOKEN"], out)

    def test_rotate_ai_changes_only_that_token(self):
        run("rotate", "ai", "--env", self.env)
        after = self.read_env()
        self.assertNotEqual(self.before["AITEL_AI_TOKEN"], after["AITEL_AI_TOKEN"])
        self.assertEqual(self.before["AITEL_PHONE_TOKEN"], after["AITEL_PHONE_TOKEN"])

    def test_rotate_all_changes_both(self):
        run("rotate", "all", "--env", self.env)
        after = self.read_env()
        self.assertNotEqual(self.before["AITEL_AI_TOKEN"], after["AITEL_AI_TOKEN"])
        self.assertNotEqual(self.before["AITEL_PHONE_TOKEN"], after["AITEL_PHONE_TOKEN"])

    def test_url_changes_only_the_url(self):
        code, _ = run("url", OTHER_PUBLIC, "--env", self.env)
        self.assertEqual(0, code)
        after = self.read_env()
        self.assertEqual(OTHER_PUBLIC, after["AITEL_PUBLIC_URL"])
        self.assertEqual(self.before["AITEL_AI_TOKEN"], after["AITEL_AI_TOKEN"])

    def test_settings_added_by_hand_survive_a_change(self):
        with open(self.env, "a", encoding="utf-8") as f:
            f.write("AITEL_OAUTH_REDIRECT_HOSTS=example.test\n")
        run("rotate", "phone", "--env", self.env)
        self.assertEqual("example.test", self.read_env()["AITEL_OAUTH_REDIRECT_HOSTS"])

    @unittest.skipIf(os.name == "nt", "POSIX permissions")
    def test_a_change_keeps_it_private(self):
        run("rotate", "all", "--env", self.env)
        self.assertEqual(0o600, os.stat(self.env).st_mode & 0o777)

    def test_a_file_missing_a_setting_is_explained(self):
        with open(self.env, "w", encoding="utf-8") as f:
            print(f"AITEL_PUBLIC_URL={PUBLIC}", file=f)
        for command in (["tokens"], ["rotate", "all"], ["serve"], ["check"]):
            code, out = run(*command, "--env", self.env)
            self.assertNotEqual(0, code, command)
            self.assertIn("AITEL_AI_TOKEN", out, command)
            self.assertNotIn("Traceback", out, command)

    def test_a_missing_file_is_explained(self):
        for command in (["tokens"], ["rotate", "all"], ["url", OTHER_PUBLIC], ["serve"], ["check"]):
            code, out = run(*command, "--env", os.path.join(self.folder, "none.env"))
            self.assertNotEqual(0, code, command)
            self.assertIn("init", out, command)


class ReadEnvTest(WithFolder):
    def test_comments_blank_lines_quotes_and_spaces(self):
        with open(self.env, "w", encoding="utf-8") as f:
            f.write('# 説明\n\nA=1\n B = 2 \nC="3 4"\nD=\'5\'\nE=x=y\n')
        self.assertEqual({"A": "1", "B": "2", "C": "3 4", "D": "5", "E": "x=y"}, self.read_env())

    def test_a_byte_order_mark_from_notepad_is_ignored(self):
        with open(self.env, "wb") as f:
            f.write(b"\xef\xbb\xbfA=1\r\nB=2\r\n")
        self.assertEqual({"A": "1", "B": "2"}, self.read_env())


class LiveRelay(WithFolder):
    """A real relay on an ephemeral port, the tool's env file pointing at it."""

    def setUp(self):
        super().setUp()
        run("init", PUBLIC, "--env", self.env)
        self.values = self.read_env()
        self.port = free_port()
        with open(self.env, "a", encoding="utf-8") as f:
            f.write(f"AITEL_RELAY_PORT={self.port}\n")
        self.desk = relay.Desk(create_wait_seconds=1)
        config = relay.Config.from_environment(self.read_env())
        self.server = relay.make_server(self.desk, config, "127.0.0.1", self.port, log=lambda _: None)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)
        self.base = f"http://127.0.0.1:{self.port}"

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def phone(self):
        phone = FakePhone(self.base, self.values["AITEL_PHONE_TOKEN"]).start()
        self.addCleanup(phone.close)
        return phone


class CheckTest(LiveRelay):
    def check(self, *more):
        return run("check", "--local", "--env", self.env, *more)

    def test_every_step_passes_with_the_phone_polling(self):
        phone = self.phone()
        code, out = self.check()
        self.assertEqual(0, code, out)
        for step in ("401", "oauth-protected-resource", "create_call_request", "needs_info"):
            self.assertIn(step, out)
        self.assertEqual(1, len(phone.commands))
        self.assertEqual(setup_tool.CHECK_SHOP, phone.commands[0]["arg"]["shop_name"])
        self.assertNotIn("phone_number", phone.commands[0]["arg"])

    def test_without_the_phone_it_says_so_and_leaves_nothing_behind(self):
        code, out = self.check()
        self.assertEqual(0, code, out)
        self.assertIn("スマホ", out)
        self.assertIn("delivering", out)
        # Nothing is left in the way of the next request
        active = [item for item in self.desk.items.values() if item.status in relay.ACTIVE]
        self.assertEqual([], active)

    def test_it_never_takes_a_command_meant_for_the_phone(self):
        self.check()
        self.assertIsNone(self.desk.instance)

    def test_a_wrong_ai_token_is_named(self):
        with open(self.env, encoding="utf-8") as f:
            text = f.read()
        with open(self.env, "w", encoding="utf-8") as f:
            f.write(text.replace(self.values["AITEL_AI_TOKEN"], "x" * 43))
        code, out = self.check()
        self.assertNotEqual(0, code)
        self.assertIn("AI 用のトークン", out)

    def test_a_wrong_phone_token_is_named(self):
        with open(self.env, encoding="utf-8") as f:
            text = f.read()
        with open(self.env, "w", encoding="utf-8") as f:
            f.write(text.replace(self.values["AITEL_PHONE_TOKEN"], "y" * 43))
        code, out = self.check()
        self.assertNotEqual(0, code)
        self.assertIn("端末用のトークン", out)

    def test_a_url_that_does_not_match_the_server_is_named(self):
        run("url", OTHER_PUBLIC, "--env", self.env)
        code, out = self.check()
        self.assertNotEqual(0, code)
        self.assertIn("AITEL_PUBLIC_URL", out)

    def test_no_token_ever_appears_in_what_it_prints(self):
        self.phone()
        _, out = self.check()
        self.assertNotIn(self.values["AITEL_AI_TOKEN"], out)
        self.assertNotIn(self.values["AITEL_PHONE_TOKEN"], out)

    def test_a_request_already_in_progress_is_left_alone(self):
        self.desk.create({"shop_name": "本物の依頼"})
        before = dict(self.desk.items)
        code, out = self.check()
        self.assertEqual(0, code, out)
        self.assertEqual(before, self.desk.items)
        self.assertIn("1 件", out)


class TokensAtWorkTest(LiveRelay):
    """What docs/mcp-setup.md promises of the two tokens, over real HTTP with what `init` wrote."""

    def call(self, path, token, body=None):
        request = urllib.request.Request(self.base + path, data=body, headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=20) as reply:
            return json.loads(reply.read() or b"null")

    def create(self):
        arguments = {"shop_name": "試験の店", "phone_number": "03-0000-0001", "party_size": 2}
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "create_call_request", "arguments": arguments}}
        return self.call("/mcp", self.values["AITEL_AI_TOKEN"], json.dumps(message).encode("utf-8"))

    def test_a_request_made_with_the_ai_token_is_taken_with_the_device_token(self):
        made = {}
        maker = threading.Thread(target=lambda: made.update(self.create()), daemon=True)
        maker.start()
        taken = []
        deadline = time.time() + 10
        while not taken and time.time() < deadline:
            taken = self.call("/phone/next?instance=test&wait=1", self.values["AITEL_PHONE_TOKEN"])["commands"]
        maker.join(10)
        self.assertEqual("create", taken[0]["method"])
        self.assertEqual("試験の店", taken[0]["arg"]["shop_name"])
        self.assertIn("result", made)

    def assert_refused(self, path, token, body=None):
        with self.assertRaises(urllib.error.HTTPError) as caught:
            self.call(path, token, body)
        self.assertEqual(401, caught.exception.code)

    def test_the_device_token_does_not_open_the_door_of_the_ais(self):
        self.assert_refused("/mcp", self.values["AITEL_PHONE_TOKEN"], b"{}")

    def test_the_ai_token_does_not_open_the_door_of_the_phone(self):
        self.assert_refused("/phone/next?instance=test", self.values["AITEL_AI_TOKEN"])


class CheckNotReachableTest(WithFolder):
    def setUp(self):
        super().setUp()
        run("init", PUBLIC, "--env", self.env)
        self.port = free_port()
        with open(self.env, "a", encoding="utf-8") as f:
            f.write(f"AITEL_RELAY_PORT={self.port}\n")

    def test_no_server_says_to_start_it(self):
        code, out = run("check", "--local", "--env", self.env)
        self.assertNotEqual(0, code)
        self.assertIn("serve", out)


class PublicAndLocalTest(LiveRelay):
    def test_an_unreachable_public_url_with_the_server_up_blames_the_tunnel(self):
        # Nothing listens on this port of this name: .invalid never resolves
        run("url", "https://relay.invalid", "--env", self.env)
        code, out = run("check", "--env", self.env)
        self.assertNotEqual(0, code)
        self.assertIn("トンネル", out)
        self.assertNotIn("Traceback", out)


class ServeTest(WithFolder):
    def setUp(self):
        super().setUp()
        run("init", PUBLIC, "--env", self.env)
        self.port = free_port()
        with open(self.env, "a", encoding="utf-8") as f:
            f.write(f"AITEL_RELAY_PORT={self.port}\n")

    def start(self):
        process = subprocess.Popen(
            [sys.executable, os.path.join(HERE, "aitel_setup.py"), "serve", "--env", self.env],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.addCleanup(self.finish, process)
        return process

    @staticmethod
    def finish(process):
        process.kill()
        process.wait(10)
        process.stdout.close()

    def wait_for_door(self):
        deadline = time.time() + 20
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", self.port), 0.5):
                    return
            except OSError:
                time.sleep(0.1)
        self.fail("the server did not start")

    def test_starts_the_relay_from_the_env_file(self):
        self.start()
        self.wait_for_door()
        code, out = run("check", "--local", "--env", self.env)
        self.assertEqual(0, code, out)

    def test_the_tokens_are_not_on_its_command_line(self):
        process = self.start()
        self.wait_for_door()
        self.assertFalse(any(self.read_env()["AITEL_AI_TOKEN"] in str(a) for a in process.args))

    def test_a_port_in_use_is_explained(self):
        with socket.socket() as taken:
            taken.bind(("127.0.0.1", self.port))
            taken.listen(1)
            process = self.start()
            out, _ = process.communicate(timeout=20)
        text = out.decode("utf-8", "replace")
        self.assertNotEqual(0, process.returncode)
        self.assertNotIn("Traceback", text)
        self.assertIn(str(self.port), text)


class SystemdTest(WithFolder):
    def setUp(self):
        super().setUp()
        run("init", PUBLIC, "--env", self.env)
        self.values = self.read_env()
        self.root = os.path.join(self.folder, "root")
        os.mkdir(self.root)

    def install(self, *more):
        return run("install-systemd", "--env", self.env, "--root", self.root, *more)

    def at(self, *parts):
        return os.path.join(self.root, *parts)

    def test_places_the_programs_the_settings_and_the_unit(self):
        code, out = self.install()
        self.assertEqual(0, code, out)
        for name in ("aitel_relay.py", "aitel_mcp.py", "aitel_setup.py"):
            self.assertTrue(os.path.isfile(self.at("opt", "aitel", name)), name)
        self.assertTrue(os.path.isfile(self.at("etc", "systemd", "system", "aitel-relay.service")))
        self.assertEqual(self.values, self.read_env(self.at("etc", "aitel", "relay.env")))

    def test_the_settings_are_moved_not_copied(self):
        self.install()
        self.assertFalse(os.path.exists(self.env))

    @unittest.skipIf(os.name == "nt", "POSIX permissions")
    def test_the_settings_stay_private(self):
        self.install()
        self.assertEqual(0o600, os.stat(self.at("etc", "aitel", "relay.env")).st_mode & 0o777)

    def test_the_unit_runs_the_relay_with_the_settings_and_no_token(self):
        self.install()
        unit = self.written(self.at("etc", "systemd", "system", "aitel-relay.service"))
        self.assertIn("EnvironmentFile=/etc/aitel/relay.env", unit)
        self.assertIn("/opt/aitel/aitel_relay.py", unit)
        self.assertIn("Restart=always", unit)
        self.assertIn("DynamicUser=yes", unit)
        self.assertIn("WantedBy=multi-user.target", unit)
        self.assertNotIn(self.values["AITEL_AI_TOKEN"], unit)

    def test_says_the_next_commands_and_does_not_run_them(self):
        _, out = self.install()
        self.assertIn("systemctl daemon-reload", out)
        self.assertIn("systemctl enable --now aitel-relay", out)

    def test_it_will_not_replace_settings_already_there(self):
        self.install()
        run("init", OTHER_PUBLIC, "--env", self.env)
        code, out = self.install()
        self.assertNotEqual(0, code)
        self.assertIn("relay.env", out)
        self.assertEqual(PUBLIC, self.read_env(self.at("etc", "aitel", "relay.env"))["AITEL_PUBLIC_URL"])

    def test_the_program_placed_there_runs_the_relay(self):
        self.install()
        env = {k: v for k, v in os.environ.items() if not k.startswith("AITEL_")}
        env["AITEL_AI_TOKEN"] = "short"
        env["AITEL_PHONE_TOKEN"] = "short"
        done = subprocess.run([sys.executable, self.at("opt", "aitel", "aitel_relay.py")], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20, check=False)
        # It got as far as reading its settings: it found aitel_mcp.py next to it
        self.assertEqual(1, done.returncode)
        self.assertIn("AITEL_AI_TOKEN", done.stdout.decode("utf-8", "replace"))


class FolderTest(unittest.TestCase):
    def test_works_with_only_its_three_files_in_a_folder(self):
        folder = tempfile.mkdtemp(prefix="aitel-empty-")
        self.addCleanup(shutil.rmtree, folder, True)
        shutil.copy(os.path.join(HERE, "aitel_setup.py"), folder)
        shutil.copy(os.path.join(HERE, "aitel_relay.py"), folder)
        shutil.copy(os.path.join(HERE, "..", "mcp", "aitel_mcp.py"), folder)
        done = subprocess.run([sys.executable, os.path.join(folder, "aitel_setup.py"), "init", PUBLIC], cwd=folder, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20, check=False)
        self.assertEqual(0, done.returncode, done.stdout)
        self.assertTrue(os.path.isfile(os.path.join(folder, "relay.env")))

    def test_needs_no_package_beyond_the_standard_library(self):
        with open(os.path.join(HERE, "aitel_setup.py"), encoding="utf-8") as f:
            source = f.read()
        allowed = set(sys.stdlib_module_names) if hasattr(sys, "stdlib_module_names") else None
        if allowed is None:
            self.skipTest("needs Python 3.10")
        for line in source.splitlines():
            words = line.split()
            if len(words) >= 2 and words[0] in ("import", "from") and not line.startswith(" "):
                self.assertIn(words[1].split(".")[0], allowed, line)


class FetchTest(unittest.TestCase):
    def test_it_names_itself_because_cloudflare_turns_away_urllib(self):
        seen = {}

        class Reply:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self):
                return b"ok"

        def urlopen(request, timeout):
            seen["agent"] = request.get_header("User-agent")
            seen["token"] = request.get_header("Authorization")
            return Reply()

        original = urllib.request.urlopen
        urllib.request.urlopen = urlopen
        try:
            self.assertEqual((200, b"ok"), setup_tool.fetch("GET", PUBLIC, {"Authorization": "Bearer x"}))
        finally:
            urllib.request.urlopen = original
        self.assertEqual(setup_tool.USER_AGENT, seen["agent"])
        self.assertNotIn("urllib", seen["agent"].lower())
        self.assertEqual("Bearer x", seen["token"])


if __name__ == "__main__":
    unittest.main()
