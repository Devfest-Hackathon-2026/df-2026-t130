# SPDX-License-Identifier: GPL-3.0-only
"""Tests of aitel_mcp.py without a phone: python -m unittest (run in this folder)."""

import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import unittest

import aitel_mcp as m

ID = "12345678-1234-1234-1234-123456789abc"
MODERN = {"_meta": {m.META_VERSION: "2026-07-28", m.META_CAPABILITIES: {}}}


class FakePhone:
    def __init__(self, *replies):
        self.replies = list(replies)
        self.asked = []

    def ask(self, method, payload):
        self.asked.append((method, payload))
        reply = self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]
        if isinstance(reply, Exception):
            raise reply
        return reply


class Wire:
    """A Server with its output collected, and a way to wait for the output of tool threads."""

    def __init__(self, phone=None):
        self.lines = []
        self.changed = threading.Condition()
        self.phone = phone or FakePhone({"ok": True})
        self.server = m.Server(m.Tools(self.phone), self._write)

    def _write(self, data):
        with self.changed:
            self.lines.append(data)
            self.changed.notify_all()

    def send(self, message):
        self.server.receive(json.dumps(message).encode("utf-8"))

    def wait_for(self, count):
        with self.changed:
            self.changed.wait_for(lambda: len(self.lines) >= count, timeout=10)
        return [json.loads(line) for line in self.lines]

    def ask(self, method, params=None, request_id=1):
        before = len(self.lines)
        message = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)
        return self.wait_for(before + 1)[before]

    def tool(self, name, arguments, request_id=1):
        return self.ask("tools/call", {"name": name, "arguments": arguments, **MODERN}, request_id)["result"]

    def legacy(self):
        self.ask("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
        return self


class LegacyEra(unittest.TestCase):
    def test_a_known_version_comes_back_unchanged(self):
        result = Wire().ask("initialize", {"protocolVersion": "2025-03-26"})["result"]
        self.assertEqual("2025-03-26", result["protocolVersion"])
        self.assertEqual({"tools": {}}, result["capabilities"])
        self.assertEqual("aitel", result["serverInfo"]["name"])

    def test_an_unknown_version_gets_the_latest_handshake_version(self):
        self.assertEqual("2025-11-25", Wire().ask("initialize", {"protocolVersion": "1999-01-01"})["result"]["protocolVersion"])

    def test_after_initialize_no_metadata_is_needed_and_no_modern_fields_are_added(self):
        wire = Wire().legacy()
        result = wire.ask("tools/list", {})["result"]
        self.assertEqual(3, len(result["tools"]))
        for field in ("resultType", "ttlMs", "cacheScope", "_meta"):
            self.assertNotIn(field, result)

    def test_ping(self):
        self.assertEqual({}, Wire().legacy().ask("ping")["result"])

    def test_ping_before_initialize(self):
        self.assertEqual({}, Wire().ask("ping")["result"])


class ModernEra(unittest.TestCase):
    def test_discover(self):
        result = Wire().ask("server/discover", MODERN)["result"]
        self.assertIn("2026-07-28", result["supportedVersions"])
        self.assertIn("2025-11-25", result["supportedVersions"])
        self.assertEqual("complete", result["resultType"])
        self.assertEqual({"tools": {}}, result["capabilities"])
        self.assertGreaterEqual(result["ttlMs"], 0)
        self.assertEqual("public", result["cacheScope"])
        self.assertEqual("aitel", result["_meta"][m.META_SERVER_INFO]["name"])

    def test_tools_list_carries_cache_hints(self):
        result = Wire().ask("tools/list", MODERN)["result"]
        self.assertEqual("complete", result["resultType"])
        self.assertGreaterEqual(result["ttlMs"], 0)
        self.assertEqual("public", result["cacheScope"])

    def test_a_request_with_no_metadata_before_initialize_is_refused(self):
        self.assertEqual(m.INVALID_PARAMS, Wire().ask("tools/list", {})["error"]["code"])

    def test_an_unsupported_version_lists_the_supported_ones(self):
        params = {"_meta": {m.META_VERSION: "1900-01-01", m.META_CAPABILITIES: {}}}
        error = Wire().ask("tools/list", params)["error"]
        self.assertEqual(m.UNSUPPORTED_VERSION, error["code"])
        self.assertIn("2026-07-28", error["data"]["supported"])
        self.assertEqual("1900-01-01", error["data"]["requested"])

    def test_a_version_that_is_not_text_is_not_sent_back(self):
        nested = [[]]
        for _ in range(50):
            nested = [nested]
        error = Wire().ask("tools/list", {"_meta": {m.META_VERSION: nested, m.META_CAPABILITIES: {}}})["error"]
        self.assertEqual(m.UNSUPPORTED_VERSION, error["code"])
        self.assertIsNone(error["data"]["requested"])

    def test_capabilities_are_required(self):
        params = {"_meta": {m.META_VERSION: "2026-07-28"}}
        self.assertEqual(m.INVALID_PARAMS, Wire().ask("tools/list", params)["error"]["code"])

    def test_an_unknown_method(self):
        self.assertEqual(m.METHOD_NOT_FOUND, Wire().ask("nope", MODERN)["error"]["code"])


class TheWire(unittest.TestCase):
    def test_broken_json_has_no_id(self):
        wire = Wire()
        wire.server.receive(b"{not json")
        reply = wire.wait_for(1)[0]
        self.assertEqual(m.PARSE_ERROR, reply["error"]["code"])
        self.assertNotIn("id", reply)

    def test_a_batch_is_refused(self):
        wire = Wire()
        wire.server.receive(b"[]")
        self.assertEqual(m.INVALID_REQUEST, wire.wait_for(1)[0]["error"]["code"])

    def test_a_null_id_is_refused(self):
        wire = Wire()
        wire.send({"jsonrpc": "2.0", "id": None, "method": "ping"})
        self.assertEqual(m.INVALID_REQUEST, wire.wait_for(1)[0]["error"]["code"])

    def test_notifications_and_responses_get_no_answer(self):
        wire = Wire()
        wire.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        wire.send({"jsonrpc": "2.0", "id": 9, "result": {}})
        self.assertEqual([], wire.lines)

    def test_a_cancel_with_an_odd_id_does_not_stop_the_server(self):
        wire = Wire().legacy()
        wire.send({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": [1]}})
        self.assertIn("result", wire.ask("ping"))

    def test_a_tool_name_that_is_not_text_is_a_protocol_error(self):
        reply = Wire().ask("tools/call", {"name": ["x"], "arguments": {}, **MODERN})
        self.assertEqual(m.INVALID_PARAMS, reply["error"]["code"])

    def test_extreme_nesting_is_a_parse_error(self):
        wire = Wire()
        wire.server.receive(b"[" * 200_000)
        self.assertEqual(m.PARSE_ERROR, wire.wait_for(1)[0]["error"]["code"])

    def test_every_message_is_one_ascii_line(self):
        wire = Wire().legacy()
        wire.ask("tools/list", {})
        for line in wire.lines:
            self.assertTrue(line.endswith(b"\n"))
            self.assertEqual(1, line.count(b"\n"))
            line.decode("ascii")


class TheToolsOnOffer(unittest.TestCase):
    def test_the_call_time_is_on_offer_and_separate_from_the_wished_times(self):
        schema = m.CREATE_SCHEMA["properties"]
        self.assertIn("call_at", schema)
        self.assertIn("windows", schema["call_at"]["description"])

    def test_the_names(self):
        self.assertEqual(["create_call_request", "get_call_request", "cancel_call_request"], [t["name"] for t in m.TOOLS])

    def test_only_reading_is_marked_read_only(self):
        self.assertEqual([False, True, False], [t["annotations"]["readOnlyHint"] for t in m.TOOLS])

    def test_party_limits_match_the_app(self):
        self.assertEqual(50, m.CREATE_SCHEMA["properties"]["party_size"]["maximum"])

    def test_a_range_of_people_is_not_on_offer(self):
        self.assertNotIn("party_min", m.CREATE_SCHEMA["properties"])
        self.assertNotIn("party_max", m.CREATE_SCHEMA["properties"])

    def test_nothing_on_offer_can_grant_authority(self):
        offered = set(m.CREATE_SCHEMA["properties"])
        self.assertFalse([p for p in offered if "approv" in p or p.startswith("may_") or "budget" in p or "max_call" in p])
        self.assertFalse(m.CREATE_SCHEMA["additionalProperties"])


class CallingTools(unittest.TestCase):
    def test_a_request_goes_to_the_phone_as_it_is(self):
        phone = FakePhone({"ok": True, "id": ID, "status": "waiting_for_approval"})
        result = Wire(phone).tool("create_call_request", {"shop_name": "店", "phone_number": "0300000000"})
        self.assertEqual([("create", {"shop_name": "店", "phone_number": "0300000000"})], phone.asked)
        self.assertFalse(result["isError"])
        self.assertEqual({"id": ID, "status": "waiting_for_approval"}, result["structuredContent"])
        self.assertEqual(result["structuredContent"], json.loads(result["content"][0]["text"]))
        self.assertEqual("complete", result["resultType"])

    def test_what_the_phone_refused_is_told_to_the_ai(self):
        phone = FakePhone({"ok": False, "error": "invalid", "message": "直してください", "problems": ["番号が変です"]})
        result = Wire(phone).tool("create_call_request", {})
        self.assertTrue(result["isError"])
        self.assertIn("直してください", result["content"][0]["text"])
        self.assertIn("番号が変です", result["content"][0]["text"])
        self.assertEqual("invalid", result["structuredContent"]["error"])

    def test_the_waiting_requests_id_is_in_the_text(self):
        phone = FakePhone({"ok": False, "error": "busy", "message": "前の依頼があります", "existing_id": ID})
        self.assertIn(ID, Wire(phone).tool("create_call_request", {})["content"][0]["text"])

    def test_a_phone_that_cannot_be_reached_is_a_tool_error(self):
        result = Wire(FakePhone(m.PhoneError("スマホが見つかりません"))).tool("create_call_request", {})
        self.assertTrue(result["isError"])
        self.assertEqual("スマホが見つかりません", result["content"][0]["text"])

    def test_an_unknown_tool_is_a_protocol_error(self):
        reply = Wire().ask("tools/call", {"name": "dial_now", "arguments": {}, **MODERN})
        self.assertEqual(m.INVALID_PARAMS, reply["error"]["code"])

    def test_arguments_must_be_an_object(self):
        reply = Wire().ask("tools/call", {"name": "get_call_request", "arguments": [1], **MODERN})
        self.assertEqual(m.INVALID_PARAMS, reply["error"]["code"])

    def test_a_malformed_id_does_not_reach_the_phone(self):
        phone = FakePhone({"ok": True})
        for name in ("get_call_request", "cancel_call_request"):
            self.assertTrue(Wire(phone).tool(name, {"id": "../etc"})["isError"])
            self.assertTrue(Wire(phone).tool(name, {"id": ID + "\n"})["isError"])
        self.assertEqual([], phone.asked)

    def test_wait_seconds_is_checked(self):
        phone = FakePhone({"ok": True})
        for wait in (-1, m.MAX_WAIT_SECONDS + 1, True, "5"):
            self.assertTrue(Wire(phone).tool("get_call_request", {"id": ID, "wait_seconds": wait})["isError"])
        self.assertEqual([], phone.asked)

    def test_a_long_request_does_not_reach_the_phone(self):
        phone = FakePhone({"ok": True})
        result = Wire(phone).tool("create_call_request", {"notes_for_shop": "あ" * m.MAX_REQUEST_BYTES})
        self.assertTrue(result["isError"])
        self.assertEqual([], phone.asked)

    def test_an_unexpected_error_does_not_quote_the_request(self):
        class Broken:
            def ask(self, method, payload):
                raise ValueError(f"the number was {payload['phone_number']}")

        wire = Wire()
        wire.server.handlers["create_call_request"] = m.Tools(Broken()).create
        result = wire.tool("create_call_request", {"phone_number": "0312345678"})
        self.assertTrue(result["isError"])
        self.assertNotIn("0312345678", json.dumps(result))

    def test_the_same_id_cannot_run_twice_at_once(self):
        entered = threading.Event()
        release = threading.Event()

        class Slow:
            def ask(self, method, payload):
                entered.set()
                release.wait(10)
                return {"ok": True, "status": "finished"}

        wire = Wire(Slow())
        args = {"name": "get_call_request", "arguments": {"id": ID}, **MODERN}
        wire.send({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": args})
        self.assertTrue(entered.wait(10))
        wire.send({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": args})
        self.assertEqual(m.INVALID_REQUEST, wire.wait_for(1)[0]["error"]["code"])
        release.set()
        self.assertFalse(wire.wait_for(2)[1]["result"]["isError"])


class WithoutAnId(unittest.TestCase):
    def test_get_without_an_id_asks_for_the_latest(self):
        phone = FakePhone({"ok": True, "id": ID, "status": "finished"})
        tools = m.Tools(phone)
        self.assertEqual("finished", tools.get({}, lambda _: False)["status"])
        self.assertEqual([("get", {})], phone.asked)


class Waiting(unittest.TestCase):
    def setUp(self):
        self.pauses = []
        self.now = 100.0

    def tools(self, *replies, stop_after=None):
        def stopped(pause):
            self.pauses.append(pause)
            self.now += pause
            return stop_after is not None and len(self.pauses) >= stop_after

        return m.Tools(FakePhone(*replies), clock=lambda: self.now), stopped

    def test_without_wait_the_phone_is_asked_once(self):
        tools, stopped = self.tools({"ok": True, "status": "calling"})
        self.assertEqual("calling", tools.get({"id": ID}, stopped)["status"])
        self.assertEqual([], self.pauses)
        self.assertEqual(1, len(tools.phone.asked))

    def test_it_returns_as_soon_as_the_status_changes(self):
        tools, stopped = self.tools({"ok": True, "status": "waiting_for_approval"}, {"ok": True, "status": "waiting_for_approval"}, {"ok": True, "status": "calling"})
        self.assertEqual("calling", tools.get({"id": ID, "wait_seconds": 30}, stopped)["status"])
        self.assertEqual([m.POLL_SECONDS, m.POLL_SECONDS], self.pauses)

    def test_a_finished_request_is_not_waited_on(self):
        tools, stopped = self.tools({"ok": True, "status": "finished"})
        tools.get({"id": ID, "wait_seconds": 30}, stopped)
        self.assertEqual([], self.pauses)

    def test_the_wait_ends_on_time(self):
        tools, stopped = self.tools({"ok": True, "status": "calling"})
        tools.get({"id": ID, "wait_seconds": 7}, stopped)
        self.assertEqual([3, 3, 1], self.pauses)

    def test_the_time_adb_takes_counts_too(self):
        test = self

        class SlowPhone(FakePhone):
            def ask(self, method, payload):
                test.now += 2
                return super().ask(method, payload)

        tools = m.Tools(SlowPhone({"ok": True, "status": "calling"}), clock=lambda: self.now)

        def stopped(pause):
            self.now += pause
            self.pauses.append(pause)
            return False

        tools.get({"id": ID, "wait_seconds": 7}, stopped)
        self.assertLessEqual(sum(self.pauses), 5)
        self.assertLessEqual(self.now - 100.0, 9)

    def test_cancelling_ends_the_wait(self):
        tools, stopped = self.tools({"ok": True, "status": "calling"}, stop_after=1)
        tools.get({"id": ID, "wait_seconds": 30}, stopped)
        self.assertEqual(1, len(self.pauses))
        self.assertEqual(1, len(tools.phone.asked))

    def test_nothing_is_sent_for_a_cancelled_request(self):
        wire = Wire(FakePhone({"ok": True, "status": "calling"}))
        wire.send({"jsonrpc": "2.0", "id": 7, "method": "tools/call", "params": {"name": "get_call_request", "arguments": {"id": ID, "wait_seconds": m.MAX_WAIT_SECONDS}, **MODERN}})
        for _ in range(500):
            with wire.server.lock:
                if 7 in wire.server.running:
                    break
        wire.send({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 7}})
        for _ in range(2000):
            with wire.server.lock:
                if 7 not in wire.server.running:
                    break
            threading.Event().wait(0.01)
        self.assertEqual([], wire.lines)


class FindingAdb(unittest.TestCase):
    def sdk(self, root):
        tools = os.path.join(root, "platform-tools")
        os.makedirs(tools)
        path = os.path.join(tools, "adb.exe" if os.name == "nt" else "adb")
        with open(path, "w", encoding="ascii"):
            pass
        return path

    def test_the_setting_wins(self):
        self.assertEqual("C:/x/adb", m.find_adb({"AITEL_ADB": "C:/x/adb", "LOCALAPPDATA": "C:/y"}))

    def test_the_users_sdk_comes_before_android_home(self):
        with tempfile.TemporaryDirectory() as d:
            local = self.sdk(os.path.join(d, "local", "Android", "Sdk"))
            self.sdk(os.path.join(d, "home"))
            self.assertEqual(local, m.find_adb({"LOCALAPPDATA": os.path.join(d, "local"), "ANDROID_HOME": os.path.join(d, "home")}))

    def test_android_home(self):
        with tempfile.TemporaryDirectory() as d:
            home = self.sdk(os.path.join(d, "home"))
            self.assertEqual(home, m.find_adb({"ANDROID_HOME": os.path.join(d, "home")}))

    def test_otherwise_the_path(self):
        self.assertEqual("adb", m.find_adb({}))


FAKE_ADB = r"""
import base64, json, sys
args = sys.argv[1:]
if "AITEL_FAKE" in __import__("os").environ:
    print(__import__("os").environ["AITEL_FAKE"])
    sys.exit(1)
arg = args[args.index("--arg") + 1]
payload = json.loads(base64.urlsafe_b64decode(arg + "=" * (-len(arg) % 4)))
reply = {"ok": True, "status": "waiting_for_approval", "method": args[args.index("--method") + 1], "args": args, "echo": payload}
print("Result: Bundle[{result=" + base64.urlsafe_b64encode(json.dumps(reply, ensure_ascii=False).encode()).rstrip(b"=").decode() + "}]")
"""


def install_fake_adb(folder):
    script = os.path.join(folder, "fake_adb.py")
    with open(script, "w", encoding="utf-8") as f:
        f.write(FAKE_ADB)
    if os.name == "nt":
        path = os.path.join(folder, "adb.cmd")
        with open(path, "w", encoding="ascii") as f:
            f.write(f'@"{sys.executable}" -I "{script}" %*\n')
    else:
        path = os.path.join(folder, "adb")
        with open(path, "w", encoding="ascii") as f:
            f.write(f'#!/bin/sh\nexec "{sys.executable}" -I "{script}" "$@"\n')
        os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
    return path


class ThroughAdb(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.adb = install_fake_adb(self.folder.name)
        self.addCleanup(os.environ.pop, "AITEL_FAKE", None)

    def test_japanese_goes_there_and_back_and_the_arg_is_plain(self):
        reply = m.Phone(self.adb).ask("create", {"shop_name": "寿司 ＋/=& 店"})
        self.assertEqual({"shop_name": "寿司 ＋/=& 店"}, reply["echo"])
        args = reply["args"]
        self.assertEqual(["shell", "content", "call", "--uri", "content://" + m.AUTHORITY, "--method", "create", "--arg"], args[:8])
        self.assertRegex(args[8], r"^[A-Za-z0-9_-]+$")

    def test_the_arg_can_never_look_like_an_option(self):
        self.assertEqual("e", m.to_base64url(b'{"a":1}')[0])

    def test_a_serial_selects_the_device(self):
        command = m.Phone("adb", "SERIAL")._command("ping", "e30")
        self.assertEqual(["adb", "-s", "SERIAL", "shell"], command[:4])

    def test_no_result_is_a_phone_error_that_does_not_repeat_adbs_words(self):
        os.environ["AITEL_FAKE"] = "Result: null"
        with self.assertRaises(m.PhoneError) as caught:
            m.Phone(self.adb).ask("get", {})
        self.assertNotIn("null", str(caught.exception))

    def test_a_reply_that_is_not_an_object(self):
        os.environ["AITEL_FAKE"] = "Result: Bundle[{result=" + m.to_base64url(b"[1]") + "}]"
        with self.assertRaises(m.PhoneError):
            m.Phone(self.adb).ask("get", {})

    def test_common_adb_failures_are_explained(self):
        cases = {
            "adb.exe: no devices/emulators found": "USB",
            "adb.exe: device 'SERIAL' not found": "AITEL_ADB_SERIAL",
            "adb.exe: device unauthorized.": "許可",
            "adb.exe: device offline": "つなぎ直して",
            "adb.exe: more than one device/emulator": "AITEL_ADB_SERIAL",
            "Error while accessing provider:io.github": "AITEL",
        }
        for text, word in cases.items():
            self.assertIn(word, m.explain_adb_output(text, 1))

    def test_a_missing_adb(self):
        with self.assertRaises(m.PhoneError) as caught:
            m.Phone(os.path.join(self.folder.name, "nothing-here")).ask("get", {})
        self.assertIn("adb", str(caught.exception))

    def test_the_whole_way_over_stdio(self):
        here = os.path.dirname(os.path.abspath(__file__))
        env = {**os.environ, "AITEL_ADB": self.adb, "PYTHONIOENCODING": "cp932"}
        server = subprocess.Popen([sys.executable, "-I", os.path.join(here, "aitel_mcp.py")], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env)
        guard = threading.Timer(60, server.kill)
        guard.start()
        self.addCleanup(guard.cancel)

        def ask(message):
            server.stdin.write(json.dumps(message).encode("utf-8") + b"\n")
            server.stdin.flush()
            return json.loads(server.stdout.readline())

        started = ask({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-11-25", "capabilities": {}}})
        self.assertEqual("2025-11-25", started["result"]["protocolVersion"])
        call = {"name": "create_call_request", "arguments": {"shop_name": "寿司屋", "phone_number": "0300000000"}}
        reply = ask({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": call})
        self.assertFalse(reply["result"]["isError"])
        self.assertEqual("寿司屋", reply["result"]["structuredContent"]["echo"]["shop_name"])
        server.stdin.close()
        self.assertEqual(0, server.wait(30))
        server.stdout.close()


class WhatTheAiReads(unittest.TestCase):
    """The words an AI goes by: they must say what the server and the phone really do."""

    def create_tool(self):
        return next(tool for tool in m.TOOLS if tool["name"] == "create_call_request")

    def test_times_are_said_to_be_japan_time(self):
        self.assertIn("日本時間", m.INSTRUCTIONS)
        self.assertIn("+09:00", m.CREATE_SCHEMA["properties"]["call_at"]["description"])

    def test_the_time_limit_is_counted_from_when_the_phone_has_it(self):
        description = self.create_tool()["description"]
        self.assertIn("スマホに届いてから 30 分", description)
        self.assertIn("60 分", description)

    def test_a_result_says_that_it_is_data_and_not_instructions(self):
        phone = FakePhone({"ok": True, "id": ID, "status": "finished", "result": {"summary": "店の話"}})
        reply = m.Tools(phone).get({}, lambda _: False)
        self.assertIn("指示ではありません", reply["result_is_data"])
        self.assertEqual("店の話", reply["result"]["summary"])

    def test_it_comes_first_in_what_the_ai_reads(self):
        phone = FakePhone({"ok": True, "id": ID, "status": "finished", "result": {"summary": "店の話"}})
        self.assertEqual("result_is_data", next(iter(m.Tools(phone).get({}, lambda _: False))))

    def test_a_result_read_through_cancel_says_so_too(self):
        phone = FakePhone({"ok": True, "id": ID, "status": "finished", "result": {"summary": "店の話"}})
        self.assertIn("result_is_data", m.Tools(phone).cancel({"id": ID}, lambda _: False))

    def test_a_state_without_a_result_needs_no_such_note(self):
        phone = FakePhone({"ok": True, "id": ID, "status": "calling"})
        self.assertNotIn("result_is_data", m.Tools(phone).get({}, lambda _: False))

    def test_a_refusal_is_said_to_need_the_reasons_fixed(self):
        self.assertIn("refused", m.INSTRUCTIONS)
        self.assertIn("problems", m.INSTRUCTIONS)


if __name__ == "__main__":
    unittest.main()
