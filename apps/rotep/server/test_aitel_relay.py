# SPDX-License-Identifier: GPL-3.0-only
"""Tests of aitel_relay.py without a phone: python -m unittest (run in this folder)."""

import base64
import hashlib
import json
import os
import socket
import sys
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "mcp"))

import aitel_mcp as mcp  # noqa: E402
import aitel_relay as relay  # noqa: E402

AI_TOKEN = "test-ai-token-" + "a" * 32
PHONE_TOKEN = "test-phone-token-" + "p" * 32
PUBLIC = "https://relay.example.test"
ID = "12345678-1234-1234-1234-123456789abc"
NUMBER = "03-0000-0001"
BOOKING = {
    "shop_name": "店",
    "phone_number": NUMBER,
    "windows": [{"date": "2026-11-14", "from": "19:00"}],
    "party_size": 2,
    "reservation_name": "テスト",
}
MODERN = {"_meta": {mcp.META_VERSION: "2026-07-28", mcp.META_CAPABILITIES: {}}}


def config(**changes):
    values = {
        "ai_token": AI_TOKEN,
        "phone_token": PHONE_TOKEN,
        "public_url": PUBLIC,
        "allowed_origins": (),
        "redirect_hosts": ("grok.com", "x.ai"),
    }
    values.update(changes)
    return relay.Config(**values)


class Clock:
    def __init__(self):
        self.now = 1_000_000.0

    def __call__(self):
        return self.now


class Fixture:
    """A gateway with a test clock, and helpers for the three kinds of caller."""

    def __init__(self, conf=None, wait=0.05):
        self.clock = Clock()
        self.logs = []
        self.desk = relay.Desk(clock=self.clock, create_wait_seconds=wait)
        self.gateway = relay.Gateway(self.desk, conf or config(), clock=self.clock, log=self.logs.append)

    def request(self, method, target, headers=None, body=b""):
        lowered = {k.lower(): v for k, v in (headers or {}).items()}
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode("utf-8")
        status, reply_headers, payload = self.gateway.handle(method, target, lowered, body)
        return status, {k.lower(): v for k, v in reply_headers.items()}, payload

    def ai(self, method, target, body=b"", extra=None, token=AI_TOKEN):
        return self.request(method, target, {"Authorization": f"Bearer {token}", "Content-Type": "application/json", **(extra or {})}, body)

    def phone(self, method, target, body=b""):
        return self.request(method, target, {"Authorization": f"Bearer {PHONE_TOKEN}", "Content-Type": "application/json"}, body)

    def mcp(self, message, headers=None, token=AI_TOKEN):
        status, reply_headers, payload = self.ai("POST", "/mcp", message, headers, token)
        return status, reply_headers, json.loads(payload) if payload else None

    def modern_headers(self, message):
        headers = {"MCP-Protocol-Version": "2026-07-28", "Mcp-Method": message["method"]}
        if message["method"] == "tools/call":
            headers["Mcp-Name"] = message["params"]["name"]
        return headers

    def tool(self, name, arguments, request_id=1):
        params = {"name": name, "arguments": arguments, **MODERN}
        message = {"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": params}
        return self.mcp(message, self.modern_headers(message))[2]["result"]

    def next_commands(self, instance="p1", wait=0):
        status, _, payload = self.phone("GET", f"/phone/next?instance={instance}&wait={wait}")
        assert status == 200, status
        return json.loads(payload)["commands"]

    def reply(self, cmd, reply):
        return self.phone("POST", "/phone/reply", {"cmd": cmd, "reply": reply})[0]

    def state(self, request_id, state):
        return self.phone("POST", "/phone/state", {"id": request_id, "state": state})[0]

    def phone_answers(self, method, reply, instance="p1"):
        """Takes the next command (of [method]) and answers it, as the phone would."""
        for command in self.next_commands(instance):
            if command["method"] == method:
                self.reply(command["cmd"], {**reply, "id": command["id"]} if "id" not in reply else reply)
                return command
        raise AssertionError(f"no {method} command")


def waiting(**more):
    return {"ok": True, "status": "waiting_for_approval", "message": "本人の承認を待っています", **more}


class Authentication(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def test_the_mcp_door_is_closed_without_a_token(self):
        status, headers, _ = self.f.request("POST", "/mcp", {"Content-Type": "application/json"}, {})
        self.assertEqual(401, status)
        self.assertIn(f'resource_metadata="{PUBLIC}/.well-known/oauth-protected-resource"', headers["www-authenticate"])

    def test_a_wrong_token_is_refused_the_same_way(self):
        self.assertEqual(401, self.f.ai("POST", "/mcp", {}, token="wrong")[0])

    def test_the_phone_token_does_not_open_the_ai_door(self):
        self.assertEqual(401, self.f.ai("POST", "/mcp", {}, token=PHONE_TOKEN)[0])
        self.assertEqual(401, self.f.ai("POST", "/v1/call-requests", dict(BOOKING), token=PHONE_TOKEN)[0])
        self.assertEqual(401, self.f.ai("GET", "/v1/call-requests/latest", token=PHONE_TOKEN)[0])

    def test_the_ai_token_does_not_open_the_phone_door(self):
        self.assertEqual(401, self.f.ai("GET", "/phone/next?instance=x&wait=0")[0])
        self.assertEqual(401, self.f.ai("POST", "/phone/reply", {})[0])
        self.assertEqual(401, self.f.ai("POST", "/phone/state", {})[0])

    def test_rest_needs_a_token(self):
        self.assertEqual(401, self.f.request("GET", "/v1/call-requests/latest")[0])
        self.assertEqual(401, self.f.request("POST", "/v1/call-requests", {}, dict(BOOKING))[0])


class Starting(unittest.TestCase):
    def test_both_tokens_are_needed_and_must_be_long(self):
        for env in (
            {"AITEL_PUBLIC_URL": PUBLIC},
            {"AITEL_AI_TOKEN": "short", "AITEL_PHONE_TOKEN": PHONE_TOKEN, "AITEL_PUBLIC_URL": PUBLIC},
            {"AITEL_AI_TOKEN": AI_TOKEN, "AITEL_PHONE_TOKEN": "short", "AITEL_PUBLIC_URL": PUBLIC},
        ):
            with self.assertRaises(relay.ConfigProblem):
                relay.Config.from_environment(env)

    def test_the_two_tokens_must_differ(self):
        with self.assertRaises(relay.ConfigProblem):
            relay.Config.from_environment({"AITEL_AI_TOKEN": AI_TOKEN, "AITEL_PHONE_TOKEN": AI_TOKEN, "AITEL_PUBLIC_URL": PUBLIC})

    def test_the_public_url_is_https_without_a_trailing_slash(self):
        base = {"AITEL_AI_TOKEN": AI_TOKEN, "AITEL_PHONE_TOKEN": PHONE_TOKEN}
        with self.assertRaises(relay.ConfigProblem):
            relay.Config.from_environment({**base, "AITEL_PUBLIC_URL": "http://relay.example.test"})
        self.assertEqual(PUBLIC, relay.Config.from_environment({**base, "AITEL_PUBLIC_URL": PUBLIC + "/"}).public_url)

    def test_a_good_environment(self):
        conf = relay.Config.from_environment(
            {
                "AITEL_AI_TOKEN": AI_TOKEN,
                "AITEL_PHONE_TOKEN": PHONE_TOKEN,
                "AITEL_PUBLIC_URL": PUBLIC,
                "AITEL_RELAY_ALLOWED_ORIGINS": "https://a.example, https://b.example",
                "AITEL_OAUTH_REDIRECT_HOSTS": "extra.example",
            }
        )
        self.assertEqual(("https://a.example", "https://b.example"), conf.allowed_origins)
        self.assertIn("extra.example", conf.redirect_hosts)
        self.assertIn("grok.com", conf.redirect_hosts)
        self.assertIn("www.cursor.com", conf.redirect_hosts)
        self.assertEqual(("127.0.0.1", 8787), (conf.host, conf.port))

    def test_a_new_token_is_long_and_random(self):
        first, second = relay.new_token(), relay.new_token()
        self.assertNotEqual(first, second)
        self.assertGreaterEqual(len(first), 40)


class Origins(unittest.TestCase):
    def setUp(self):
        self.f = Fixture(config(allowed_origins=("https://allowed.example",)))
        self.message = {"jsonrpc": "2.0", "id": 1, "method": "ping"}

    def test_no_origin_is_fine(self):
        self.assertEqual(200, self.f.mcp(self.message)[0])

    def test_a_listed_origin_is_fine(self):
        self.assertEqual(200, self.f.mcp(self.message, {"Origin": "https://allowed.example"})[0])

    def test_the_public_origin_is_fine(self):
        self.assertEqual(200, self.f.mcp(self.message, {"Origin": PUBLIC})[0])

    def test_the_origin_of_nowhere_is_forbidden(self):
        self.assertEqual(403, self.f.ai("POST", "/mcp", self.message, {"Origin": "null"})[0])

    def test_any_other_origin_is_forbidden(self):
        status, headers, payload = self.f.ai("POST", "/mcp", self.message, {"Origin": "https://evil.example"})
        self.assertEqual(403, status)
        self.assertEqual(b"", payload)
        self.assertEqual("no-store", headers["cache-control"])


class Methods(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def test_only_post_on_the_mcp_door(self):
        for method in ("GET", "DELETE", "PUT"):
            status, headers, _ = self.f.ai(method, "/mcp")
            self.assertEqual(405, status)
            self.assertEqual("POST", headers["allow"])

    def test_the_session_header_is_ignored(self):
        status, headers, payload = self.f.mcp({"jsonrpc": "2.0", "id": 1, "method": "ping"}, {"Mcp-Session-Id": "abc"})
        self.assertEqual(200, status)
        self.assertNotIn("mcp-session-id", headers)
        self.assertEqual({}, payload["result"])

    def test_an_unknown_path(self):
        self.assertEqual(404, self.f.ai("GET", "/nope")[0])

    def test_a_body_that_is_not_json_is_refused(self):
        status, _, payload = self.f.request("POST", "/mcp", {"Authorization": f"Bearer {AI_TOKEN}", "Content-Type": "text/plain"}, b"{}")
        self.assertEqual(415, status)
        self.assertEqual(b"", payload)

    def test_a_body_that_is_too_large(self):
        status, _, _ = self.f.ai("POST", "/mcp", b"x" * (relay.MAX_BODY_BYTES + 1))
        self.assertEqual(413, status)

    def test_broken_json(self):
        status, _, payload = self.f.ai("POST", "/mcp", b"{not json")
        self.assertEqual(400, status)
        self.assertEqual(mcp.PARSE_ERROR, json.loads(payload)["error"]["code"])


class TheMcpDoor(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def test_a_legacy_client_can_initialize_and_list(self):
        status, _, reply = self.f.mcp({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}}})
        self.assertEqual(200, status)
        self.assertEqual("2025-06-18", reply["result"]["protocolVersion"])
        status, _, reply = self.f.mcp({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        self.assertEqual(200, status)
        self.assertEqual(3, len(reply["result"]["tools"]))

    def test_a_notification_gets_202_and_nothing_else(self):
        status, _, payload = self.f.ai("POST", "/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual(202, status)
        self.assertEqual(b"", payload)

    def test_a_modern_request_goes_through_with_its_headers(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": dict(MODERN)}
        status, _, reply = self.f.mcp(message, self.f.modern_headers(message))
        self.assertEqual(200, status)
        self.assertEqual("complete", reply["result"]["resultType"])

    def test_a_modern_request_needs_its_headers(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": dict(MODERN)}
        status, _, reply = self.f.mcp(message)
        self.assertEqual(400, status)
        self.assertEqual(-32020, reply["error"]["code"])

    def test_a_header_that_disagrees_with_the_body(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "get_call_request", "arguments": {}, **MODERN}}
        headers = self.f.modern_headers(message)
        headers["Mcp-Name"] = "cancel_call_request"
        status, _, reply = self.f.mcp(message, headers)
        self.assertEqual(400, status)
        self.assertEqual(-32020, reply["error"]["code"])

    def test_a_tool_name_can_come_encoded(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "get_call_request", "arguments": {}, **MODERN}}
        headers = self.f.modern_headers(message)
        headers["Mcp-Name"] = "=?base64?" + base64.b64encode(b"get_call_request").decode() + "?="
        status, _, reply = self.f.mcp(message, headers)
        self.assertEqual(200, status)
        self.assertTrue(reply["result"]["isError"])  # nothing has been asked yet

    def test_a_version_the_server_does_not_speak_is_400_with_the_list(self):
        params = {"_meta": {mcp.META_VERSION: "1900-01-01", mcp.META_CAPABILITIES: {}}}
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": params}
        headers = {"MCP-Protocol-Version": "1900-01-01", "Mcp-Method": "tools/list"}
        status, _, reply = self.f.mcp(message, headers)
        self.assertEqual(400, status)
        self.assertEqual(mcp.UNSUPPORTED_VERSION, reply["error"]["code"])

    def test_a_method_it_does_not_have_is_404_in_the_modern_era(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "nope", "params": dict(MODERN)}
        status, _, reply = self.f.mcp(message, self.f.modern_headers(message))
        self.assertEqual(404, status)
        self.assertEqual(mcp.METHOD_NOT_FOUND, reply["error"]["code"])

    def test_discover(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "server/discover", "params": dict(MODERN)}
        status, _, reply = self.f.mcp(message, self.f.modern_headers(message))
        self.assertEqual(200, status)
        self.assertIn("2026-07-28", reply["result"]["supportedVersions"])

    def test_the_json_answer_has_the_right_type(self):
        status, headers, _ = self.f.ai("POST", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "ping"})
        self.assertEqual(200, status)
        self.assertEqual("application/json", headers["content-type"])

    def test_tools_call_reaches_the_desk(self):
        result = self.f.tool("create_call_request", dict(BOOKING))
        self.assertFalse(result["isError"])
        self.assertEqual("delivering", result["structuredContent"]["status"])


class TheDesk(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()
        self.desk = self.f.desk

    def create(self, args=None):
        return self.desk.ask("create", dict(args or BOOKING))

    def test_nothing_fetched_yet_means_delivering(self):
        reply = self.create()
        self.assertTrue(reply["ok"])
        self.assertEqual("delivering", reply["status"])
        self.assertTrue(mcp.REQUEST_ID.fullmatch(reply["id"]))
        self.assertEqual(reply, self.desk.ask("get", {"id": reply["id"]}))

    def test_the_phone_gets_the_request_as_it_came(self):
        created = self.create()
        command = self.f.next_commands()[0]
        self.assertEqual(("create", created["id"], BOOKING), (command["method"], command["id"], command["arg"]))

    def test_the_phones_answer_is_what_the_ai_reads(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        reply = self.desk.ask("get", {"id": created["id"]})
        self.assertEqual("waiting_for_approval", reply["status"])

    def test_without_an_id_the_latest_is_read(self):
        created = self.create()
        self.assertEqual(created["id"], self.desk.ask("get", {})["id"])

    def test_a_question_from_the_phone_is_read_like_any_status(self):
        created = self.create({"shop_name": "店"})
        self.f.phone_answers("create", {"ok": True, "status": "needs_info", "message": "足りません", "questions": [{"field": "party_size", "question": "何人ですか"}]})
        reply = self.desk.ask("get", {"id": created["id"]})
        self.assertEqual("needs_info", reply["status"])
        self.assertEqual("party_size", reply["questions"][0]["field"])

    def test_a_refusal_from_the_phone_becomes_a_status(self):
        created = self.create()
        self.f.phone_answers("create", {"ok": False, "error": "invalid", "message": "だめです", "problems": ["番号が変です"]})
        reply = self.desk.ask("get", {"id": created["id"]})
        self.assertFalse(reply["ok"])
        self.assertEqual("refused", reply["status"])
        self.assertEqual(["番号が変です"], reply["problems"])
        self.assertEqual("invalid", reply["error"])

    def test_the_ai_is_told_a_refusal_as_an_error_with_the_reasons(self):
        created = self.create()
        self.f.phone_answers("create", {"ok": False, "error": "invalid", "message": "このままでは預かれません", "problems": ["番号が変です", "日付が過去です"]})
        result = self.f.tool("get_call_request", {"id": created["id"]})
        self.assertTrue(result["isError"])
        text = result["content"][0]["text"]
        self.assertIn("番号が変です", text)
        self.assertIn("日付が過去です", text)

    def test_busy_says_what_to_do_next(self):
        first = self.create()
        result = self.f.tool("create_call_request", dict(BOOKING))
        self.assertTrue(result["isError"])
        text = result["content"][0]["text"]
        self.assertIn("get_call_request", text)
        self.assertIn("cancel_call_request", text)
        self.assertIn(first["id"], text)

    def test_one_at_a_time(self):
        first = self.create()
        reply = self.create()
        self.assertFalse(reply["ok"])
        self.assertEqual("busy", reply["error"])
        self.assertEqual(first["id"], reply["existing_id"])

    def test_a_refused_request_does_not_block_the_next(self):
        self.create()
        self.f.phone_answers("create", {"ok": False, "error": "invalid", "message": "だめです"})
        self.assertEqual("delivering", self.create()["status"])

    def test_cancelling_what_was_not_fetched(self):
        created = self.create()
        reply = self.desk.ask("cancel", {"id": created["id"]})
        self.assertEqual("cancelled", reply["status"])
        self.assertEqual([], self.f.next_commands())

    def test_cancelling_what_the_phone_took_goes_to_the_phone_too(self):
        created = self.create()
        self.f.next_commands()  # taken; its answer has not come
        reply = self.desk.ask("cancel", {"id": created["id"]})
        self.assertTrue(reply["cancel_pending"])
        self.assertEqual("delivering", reply["status"])
        self.assertEqual(["cancel"], [c["method"] for c in self.f.next_commands()])

    def test_the_phones_answer_to_a_cancel_ends_the_request(self):
        created = self.create()
        self.f.next_commands()
        self.desk.ask("cancel", {"id": created["id"]})
        self.f.phone_answers("cancel", {"ok": True, "status": "cancelled", "message": "取り下げました"})
        self.assertEqual("cancelled", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_cancel_of_what_the_phone_never_got_ends_the_request(self):
        created = self.create()
        self.f.next_commands()  # the answer to this poll was lost on the way
        self.desk.ask("cancel", {"id": created["id"]})
        self.f.phone_answers("cancel", {"ok": False, "error": "not_found", "message": "残っていません"})
        self.assertEqual("cancelled", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_cancel_of_what_ended_on_the_phone_says_how_it_ended(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.desk.ask("cancel", {"id": created["id"]})
        self.f.phone_answers("cancel", {"ok": False, "error": "already_ended", "message": "期限が切れました", "status": "expired"})
        self.assertEqual("expired", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_cancel_that_came_too_late_changes_nothing_here(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.desk.ask("cancel", {"id": created["id"]})
        self.f.phone_answers("cancel", {"ok": False, "error": "too_late", "message": "もう取り下げられません"})
        self.assertEqual("waiting_for_approval", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_the_request_does_not_go_out_again_after_its_cancel(self):
        created = self.create()
        self.f.next_commands("p1")
        self.desk.ask("cancel", {"id": created["id"]})
        methods = [c["method"] for c in self.f.next_commands("p2")]
        self.assertEqual(["cancel"], methods)

    def test_a_request_being_cancelled_is_not_delivered_to_a_new_process(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.desk.ask("cancel", {"id": created["id"]})
        self.assertEqual(["cancel"], [c["method"] for c in self.f.next_commands("p2")])

    def test_cancelling_after_delivery_goes_to_the_phone(self):
        f = Fixture(wait=5)
        answer = {}

        def create():
            answer["created"] = f.desk.ask("create", dict(BOOKING))

        worker = threading.Thread(target=create)
        worker.start()
        command = f.next_commands(wait=5)[0]
        f.reply(command["cmd"], {**waiting(), "id": command["id"]})
        worker.join(5)
        created = answer["created"]

        def cancel():
            answer["reply"] = f.desk.ask("cancel", {"id": created["id"]})

        worker = threading.Thread(target=cancel)
        worker.start()
        command = f.next_commands(wait=5)[0]
        self.assertEqual("cancel", command["method"])
        f.reply(command["cmd"], {"ok": True, "id": created["id"], "status": "cancelled", "message": "取り下げました"})
        worker.join(5)
        self.assertEqual("cancelled", answer["reply"]["status"])

    def test_a_cancel_the_phone_has_not_taken_yet_is_said_to_be_pending(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        reply = self.desk.ask("cancel", {"id": created["id"]})
        self.assertTrue(reply["cancel_pending"])
        self.assertEqual("waiting_for_approval", reply["status"])

    def test_cancelling_something_unknown(self):
        reply = self.desk.ask("cancel", {"id": ID})
        self.assertFalse(reply["ok"])
        self.assertEqual("not_found", reply["error"])

    def test_cancelling_what_is_over_says_how_it_ended(self):
        created = self.create()
        self.f.phone_answers("create", {"ok": True, "status": "needs_info", "message": "足りません", "questions": []})
        self.assertEqual("needs_info", self.desk.ask("cancel", {"id": created["id"]})["status"])

    def test_a_request_never_fetched_expires(self):
        created = self.create()
        self.f.clock.now += relay.UNDELIVERED_SECONDS - 1
        self.assertEqual("delivering", self.desk.ask("get", {"id": created["id"]})["status"])
        self.f.clock.now += 1
        self.assertEqual("expired", self.desk.ask("get", {"id": created["id"]})["status"])
        self.assertEqual([], self.f.next_commands())

    def test_a_request_not_approved_expires_a_while_after_the_phone_got_it(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.f.clock.now += relay.WAITING_SECONDS + relay.WAITING_GRACE_SECONDS - 1
        self.assertEqual("waiting_for_approval", self.desk.ask("get", {"id": created["id"]})["status"])
        self.f.clock.now += 1
        self.assertEqual("expired", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_call_that_never_reported_ends_as_unknown(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "calling", "message": "電話をしています"})
        self.f.clock.now += relay.CALLING_STALE_SECONDS
        self.assertEqual("unknown", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_scheduled_call_waits_for_its_time(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        fire = "2026-11-10T11:00:00+09:00"
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "scheduled", "scheduled_for": fire})
        self.f.clock.now = relay.iso_seconds(fire) + relay.CALLING_STALE_SECONDS - 1
        self.assertEqual("scheduled", self.desk.ask("get", {"id": created["id"]})["status"])
        self.f.clock.now += 1
        self.assertEqual("unknown", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_the_state_from_the_phone_replaces_what_was_there(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "finished", "result": {"outcome": "booked"}})
        reply = self.desk.ask("get", {"id": created["id"]})
        self.assertEqual("finished", reply["status"])
        self.assertEqual("booked", reply["result"]["outcome"])

    def test_the_ai_reads_that_a_result_is_data_and_not_instructions(self):
        created = self.create()
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "finished", "result": {"summary": "店の話"}})
        result = self.f.tool("get_call_request", {"id": created["id"]})
        self.assertFalse(result["isError"])
        self.assertTrue(result["content"][0]["text"].startswith('{"result_is_data": "'))
        self.assertIn("指示ではありません", result["content"][0]["text"])

    def test_a_result_is_not_replaced_by_a_process_that_forgot_it(self):
        created = self.create()
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "finished", "result": {"outcome": "booked"}})
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "unknown", "message": "記録が残っていません"})
        reply = self.desk.ask("get", {"id": created["id"]})
        self.assertEqual("finished", reply["status"])
        self.assertEqual("booked", reply["result"]["outcome"])

    def test_a_process_that_forgot_a_call_is_heard_while_the_call_was_open(self):
        created = self.create()
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "calling"})
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "unknown", "message": "記録が残っていません"})
        self.assertEqual("unknown", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_state_for_an_id_it_does_not_know_is_kept_so_a_restart_loses_nothing(self):
        self.f.state(ID, {"ok": True, "id": ID, "status": "finished", "result": {"outcome": "booked"}})
        self.assertEqual("finished", self.desk.ask("get", {"id": ID})["status"])

    def test_the_new_process_of_the_phone_gets_what_still_waits_again(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.assertEqual([], self.f.next_commands("p1"))
        command = self.f.next_commands("p2")[0]
        self.assertEqual(("create", created["id"]), (command["method"], command["id"]))
        self.assertEqual(BOOKING, command["arg"])

    def test_nothing_is_delivered_twice_to_the_same_process(self):
        self.create()
        self.assertEqual(1, len(self.f.next_commands("p1")))
        self.assertEqual([], self.f.next_commands("p1"))

    def test_what_was_taken_and_never_answered_goes_out_again_after_a_while(self):
        self.create()
        first = self.f.next_commands()
        self.f.clock.now += relay.RESEND_SECONDS - 1
        self.assertEqual([], self.f.next_commands())
        self.f.clock.now += 1
        again = self.f.next_commands()
        self.assertEqual(first[0]["cmd"], again[0]["cmd"])
        self.assertEqual([], self.f.next_commands())

    def test_what_was_answered_does_not_go_out_again(self):
        self.create()
        self.f.phone_answers("create", waiting())
        self.f.clock.now += relay.RESEND_SECONDS
        self.assertEqual([], self.f.next_commands())

    def test_an_answer_to_a_command_sent_twice_counts_once(self):
        created = self.create()
        command = self.f.next_commands()[0]
        self.f.clock.now += relay.RESEND_SECONDS
        self.f.next_commands()
        self.f.reply(command["cmd"], {**waiting(), "id": created["id"]})
        self.f.reply(command["cmd"], {"ok": False, "error": "busy", "message": "x"})
        self.assertEqual("waiting_for_approval", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_request_that_ended_takes_its_commands_with_it(self):
        created = self.create()
        self.f.next_commands()
        self.f.clock.now += relay.UNDELIVERED_SECONDS
        self.assertEqual("expired", self.desk.ask("get", {"id": created["id"]})["status"])
        self.assertEqual([], self.f.next_commands())

    def test_a_late_answer_to_a_cancel_does_not_take_the_place_of_a_result(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.desk.ask("cancel", {"id": created["id"]})
        command = self.f.next_commands()[0]
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "calling"})
        self.f.reply(command["cmd"], {"ok": False, "error": "already_ended", "message": "終わりました", "status": "finished"})
        self.assertEqual("calling", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_late_answer_to_a_delivery_does_not_move_the_state_back(self):
        created = self.create()
        command = self.f.next_commands()[0]
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "calling"})
        self.f.reply(command["cmd"], {**waiting(), "id": created["id"]})
        self.assertEqual("calling", self.desk.ask("get", {"id": created["id"]})["status"])

    def test_a_request_the_phone_reports_as_over_is_not_sent_again(self):
        created = self.create()
        self.f.next_commands()
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "declined", "message": "断りました"})
        self.f.clock.now += relay.RESEND_SECONDS
        self.assertEqual([], self.f.next_commands())

    def test_a_cancel_that_was_not_answered_goes_out_again(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.desk.ask("cancel", {"id": created["id"]})
        self.assertEqual(["cancel"], [c["method"] for c in self.f.next_commands()])
        self.f.clock.now += relay.RESEND_SECONDS
        self.assertEqual(["cancel"], [c["method"] for c in self.f.next_commands()])

    def test_the_phone_gets_only_what_it_needs_of_a_command(self):
        self.create()
        self.assertEqual({"cmd", "method", "id", "arg"}, set(self.f.next_commands()[0]))

    def test_a_long_poll_is_answered_when_a_command_is_due_again(self):
        self.create()
        self.f.next_commands()
        self.f.clock.now += relay.RESEND_SECONDS
        started = time.monotonic()
        self.assertEqual(1, len(self.f.next_commands(wait=3)))
        self.assertLess(time.monotonic() - started, 2)

    def test_a_long_poll_notices_a_command_coming_due_while_it_waits(self):
        self.create()
        self.f.next_commands()
        timer = threading.Timer(0.2, lambda: setattr(self.f.clock, "now", self.f.clock.now + relay.RESEND_SECONDS))
        timer.start()
        self.addCleanup(timer.cancel)
        started = time.monotonic()
        self.assertEqual(1, len(self.f.next_commands(wait=4)))
        self.assertLess(time.monotonic() - started, 3)

    def test_what_a_dead_process_took_but_never_answered_is_delivered_again(self):
        created = self.create()
        self.f.next_commands("p1")  # taken; the process dies before it answers
        command = self.f.next_commands("p2")[0]
        self.assertEqual(created["id"], command["id"])

    def test_the_payload_is_gone_once_the_phone_has_moved_on(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "scheduled", "scheduled_for": "2026-11-10T11:00:00+09:00"})
        self.assertIsNone(self.desk.items[created["id"]].args)
        self.assertEqual([], self.f.next_commands("p2"))

    def test_the_payload_is_kept_while_the_owner_may_still_approve(self):
        created = self.create()
        self.f.phone_answers("create", waiting())
        self.assertIsNotNone(self.desk.items[created["id"]].args)

    def test_an_ended_request_is_kept_for_a_day_then_forgotten(self):
        created = self.create()
        self.f.state(created["id"], {"ok": True, "id": created["id"], "status": "finished", "result": {"outcome": "booked"}})
        self.f.clock.now += relay.KEPT_SECONDS - 1
        self.assertEqual("finished", self.desk.ask("get", {"id": created["id"]})["status"])
        self.f.clock.now += 1
        self.assertFalse(self.desk.ask("get", {"id": created["id"]})["ok"])

    def test_only_so_many_are_kept(self):
        for _ in range(relay.MAX_ITEMS + 5):
            self.create()
            self.f.phone_answers("create", {"ok": True, "status": "needs_info", "message": "足りません", "questions": []})
        self.assertLessEqual(len(self.desk.items), relay.MAX_ITEMS)

    def test_a_reply_to_nothing_is_ignored(self):
        self.assertEqual(204, self.f.reply("no-such-command", {"ok": True}))

    def test_ping_says_when_the_phone_was_last_heard(self):
        self.assertIsNone(self.desk.ask("ping", {})["phone_last_seen_seconds"])
        self.f.next_commands()
        self.f.clock.now += 7
        self.assertEqual(7, self.desk.ask("ping", {})["phone_last_seen_seconds"])


class Isolation(unittest.TestCase):
    """What one AI asked for is out of the sight of another. A request of no known AI and the AI token are as before."""

    def setUp(self):
        self.f = Fixture()
        self.desk = self.f.desk

    def create(self, by):
        return self.desk.ask("create", dict(BOOKING), by)

    def get(self, request_id, by):
        return self.desk.ask("get", {"id": request_id}, by)

    def latest(self, by):
        return self.desk.ask("get", {}, by)

    def cancel(self, request_id, by):
        return self.desk.ask("cancel", {"id": request_id}, by)

    def end(self, created):
        self.assertEqual(204, self.f.state(created["id"], {"ok": True, "status": "finished", "message": "x"}))

    def test_another_ai_cannot_read_it(self):
        created = self.create("grok")
        self.assertEqual(relay.Desk.not_found(), self.get(created["id"], "muse"))

    def test_another_ai_cannot_take_it_back(self):
        created = self.create("grok")
        self.assertEqual(relay.Desk.not_found(), self.cancel(created["id"], "muse"))
        self.assertEqual("delivering", self.get(created["id"], "grok")["status"])
        self.assertEqual(["create"], [c["method"] for c in self.f.next_commands()])

    def test_the_latest_of_another_ai_is_not_the_latest(self):
        self.create("grok")
        self.assertEqual(relay.Desk.not_found(), self.latest("muse"))

    def test_the_latest_is_the_latest_the_caller_can_see(self):
        grok = self.create("grok")
        self.end(grok)
        muse = self.create("muse")
        self.end(muse)
        self.assertEqual(grok["id"], self.latest("grok")["id"])
        self.assertEqual(muse["id"], self.latest("muse")["id"])
        self.assertEqual(muse["id"], self.latest(None)["id"])

    def test_busy_does_not_name_the_request_of_another_ai(self):
        self.create("grok")
        reply = self.create("muse")
        self.assertEqual({"ok": False, "error": "busy", "message": relay.MESSAGE_BUSY_ELSEWHERE}, reply)
        self.assertIn("スマホで別の依頼を処理しています", reply["message"])

    def test_busy_names_the_own_request(self):
        created = self.create("grok")
        self.assertEqual(created["id"], self.create("grok")["existing_id"])

    def test_the_own_request_can_be_read_and_taken_back(self):
        created = self.create("grok")
        self.assertEqual(created["id"], self.get(created["id"], "grok")["id"])
        self.assertEqual(created["id"], self.latest("grok")["id"])
        self.assertEqual("cancelled", self.cancel(created["id"], "grok")["status"])

    def test_the_ai_token_sees_everything(self):
        created = self.create("grok")
        self.assertEqual(created["id"], self.get(created["id"], None)["id"])
        self.assertEqual(created["id"], self.latest(None)["id"])
        self.assertEqual(created["id"], self.create(None)["existing_id"])
        self.assertEqual("cancelled", self.cancel(created["id"], None)["status"])

    def test_a_request_of_no_known_ai_is_open_to_every_caller(self):
        created = self.create(None)
        self.assertEqual(created["id"], self.get(created["id"], "muse")["id"])
        self.assertEqual(created["id"], self.latest("grok")["id"])
        self.assertEqual(created["id"], self.create("muse")["existing_id"])
        self.assertEqual("cancelled", self.cancel(created["id"], "grok")["status"])

    def test_the_phones_own_busy_does_not_name_a_request_the_caller_cannot_see(self):
        created = self.create("muse")
        self.f.phone_answers("create", {"ok": False, "error": "busy", "message": "前の依頼", "existing_id": ID})
        reply = self.get(created["id"], "muse")
        self.assertNotIn("existing_id", reply)
        self.assertEqual(relay.MESSAGE_BUSY_ELSEWHERE, reply["message"])
        self.assertEqual(ID, self.get(created["id"], None)["existing_id"])

    def test_the_phones_own_busy_can_name_the_own_request(self):
        own = self.create("muse")
        self.end(own)
        created = self.create("muse")
        self.f.phone_answers("create", {"ok": False, "error": "busy", "message": "前の依頼", "existing_id": own["id"]})
        self.assertEqual(own["id"], self.get(created["id"], "muse")["existing_id"])


class ThePhoneDoor(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def test_next_needs_an_instance(self):
        self.assertEqual(400, self.f.phone("GET", "/phone/next?wait=0")[0])

    def test_next_with_a_long_wait_is_capped(self):
        started = time.monotonic()
        self.f.desk.max_poll_seconds = 0.05
        self.assertEqual([], self.f.next_commands("p1", wait=25))
        self.assertLess(time.monotonic() - started, 2)

    def test_reply_needs_its_fields(self):
        self.assertEqual(400, self.f.phone("POST", "/phone/reply", {"cmd": "x"})[0])

    def test_state_needs_a_good_id(self):
        self.assertEqual(400, self.f.phone("POST", "/phone/state", {"id": "../x", "state": {"status": "finished"}})[0])

    def test_state_needs_a_status(self):
        self.assertEqual(400, self.f.phone("POST", "/phone/state", {"id": ID, "state": {}})[0])

    def test_the_phone_door_takes_only_get_and_post(self):
        self.assertEqual(405, self.f.phone("DELETE", "/phone/next?instance=x")[0])


class TheRestDoor(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()

    def json(self, result):
        return result[0], json.loads(result[2])

    def test_create_and_read(self):
        status, body = self.json(self.f.ai("POST", "/v1/call-requests", dict(BOOKING)))
        self.assertEqual(200, status)
        self.assertEqual("delivering", body["status"])
        request_id = body["id"]
        status, body = self.json(self.f.ai("GET", f"/v1/call-requests/{request_id}"))
        self.assertEqual((200, request_id), (status, body["id"]))
        status, body = self.json(self.f.ai("GET", "/v1/call-requests/latest"))
        self.assertEqual((200, request_id), (status, body["id"]))

    def test_cancel(self):
        request_id = self.json(self.f.ai("POST", "/v1/call-requests", dict(BOOKING)))[1]["id"]
        status, body = self.json(self.f.ai("POST", f"/v1/call-requests/{request_id}/cancel"))
        self.assertEqual((200, "cancelled"), (status, body["status"]))

    def test_problems_get_a_matching_status(self):
        status, body = self.json(self.f.ai("GET", f"/v1/call-requests/{ID}"))
        self.assertEqual(404, status)
        self.assertEqual("not_found", body["error"])
        self.f.ai("POST", "/v1/call-requests", dict(BOOKING))
        status, body = self.json(self.f.ai("POST", "/v1/call-requests", dict(BOOKING)))
        self.assertEqual(409, status)
        self.assertEqual("busy", body["error"])

    def test_a_bad_id_does_not_reach_the_desk(self):
        self.assertEqual(404, self.f.ai("GET", "/v1/call-requests/not-an-id")[0])

    def test_wait_seconds_is_read_from_the_query(self):
        request_id = self.json(self.f.ai("POST", "/v1/call-requests", dict(BOOKING)))[1]["id"]
        self.assertEqual(400, self.f.ai("GET", f"/v1/call-requests/{request_id}?wait_seconds=99")[0])

    def test_the_description_of_the_api_needs_no_token_and_holds_no_secret(self):
        status, _, payload = self.f.request("GET", "/openapi.json")
        self.assertEqual(200, status)
        text = payload.decode("utf-8")
        self.assertIn("/v1/call-requests", text)
        self.assertNotIn(AI_TOKEN, text)
        self.assertNotIn(PHONE_TOKEN, text)

    def test_the_description_is_only_read(self):
        self.assertEqual(405, self.f.request("POST", "/openapi.json")[0])

    def test_openapi_describes_the_same_fields_as_the_tools(self):
        status, body = self.json(self.f.ai("GET", "/openapi.json"))
        self.assertEqual(200, status)
        self.assertEqual(f"{PUBLIC}", body["servers"][0]["url"])
        create = body["paths"]["/v1/call-requests"]["post"]["requestBody"]["content"]["application/json"]["schema"]
        self.assertEqual(mcp.CREATE_SCHEMA["properties"].keys(), create["properties"].keys())
        self.assertIn("/v1/call-requests/{id}", body["paths"])

    def test_the_only_method_on_the_collection_is_post(self):
        self.assertEqual(405, self.f.ai("DELETE", "/v1/call-requests")[0])


def challenge_of(verifier):
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()


class OAuth(unittest.TestCase):
    REDIRECT = "https://grok.com/connectors/oauth/callback"

    def setUp(self):
        self.f = Fixture()
        self.verifier = "v" * 64

    def public(self, method, target, body=b"", headers=None):
        return self.f.request(method, target, headers, body)

    def register(self, redirect=None):
        status, _, payload = self.public("POST", "/oauth/register", {"redirect_uris": [redirect or self.REDIRECT], "client_name": "Grok <b>"})
        self.assertEqual(201, status, payload)
        return json.loads(payload)

    def authorize_query(self, client_id, redirect=None, **changes):
        params = {
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": redirect or self.REDIRECT,
            "state": "st",
            "code_challenge": challenge_of(self.verifier),
            "code_challenge_method": "S256",
            "resource": f"{PUBLIC}/mcp",
        }
        params.update(changes)
        return {k: v for k, v in params.items() if v is not None}

    def get_authorize(self, client_id, **changes):
        query = urllib.parse.urlencode(self.authorize_query(client_id, **changes))
        return self.public("GET", f"/oauth/authorize?{query}")

    def post_authorize(self, client_id, token=AI_TOKEN, **changes):
        form = {**self.authorize_query(client_id, **changes), "owner_token": token}
        return self.public("POST", "/oauth/authorize", urllib.parse.urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded"})

    def code_for(self, client_id, **changes):
        status, headers, _ = self.post_authorize(client_id, **changes)
        self.assertEqual(302, status)
        query = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(headers["location"]).query))
        self.assertEqual("st", query["state"])
        self.assertEqual(PUBLIC, query["iss"])
        return query["code"]

    def token(self, code, client_id, verifier=None, redirect=None, **changes):
        form = {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": client_id,
            "redirect_uri": redirect or self.REDIRECT,
            "code_verifier": verifier or self.verifier,
            "resource": f"{PUBLIC}/mcp",
        }
        form.update(changes)
        form = {k: v for k, v in form.items() if v is not None}
        status, _, payload = self.public("POST", "/oauth/token", urllib.parse.urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded"})
        return status, json.loads(payload)

    def access_token(self):
        client = self.register()["client_id"]
        return self.token(self.code_for(client), client)[1]["access_token"]

    # --- who asked ---

    HOSTS = ("grok.com", "x.ai", "www.cursor.com", "agent.meta.ai", "other.example")

    def access_for(self, redirect):
        self.f = Fixture(config(redirect_hosts=self.HOSTS))
        client = self.register(redirect)["client_id"]
        status, tokens = self.token(self.code_for(client, redirect=redirect), client, redirect=redirect)
        self.assertEqual(200, status, tokens)
        return tokens["access_token"]

    def command_from(self, token):
        # An AI cannot name itself: what it writes stays in arg
        arguments = {"shop_name": "店", "assistant": "muse"}
        message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "create_call_request", "arguments": arguments}}
        self.assertEqual(200, self.f.mcp(message, token=token)[0])
        return self.f.next_commands()[0]

    def test_a_request_says_which_ai_by_the_host_its_grant_went_back_to(self):
        cases = {"https://grok.com/cb": "grok", "https://auth.x.ai/cb": "grok", "https://www.cursor.com/cb": "grok", "https://agent.meta.ai/cb": "muse"}
        for redirect, assistant in cases.items():
            with self.subTest(redirect):
                self.assertEqual(assistant, self.command_from(self.access_for(redirect))["assistant"])

    def test_a_host_not_known_says_nothing_about_who_asked(self):
        self.assertNotIn("assistant", self.command_from(self.access_for("https://other.example/cb")))

    def test_the_ai_token_itself_says_nothing_about_who_asked(self):
        self.assertNotIn("assistant", self.command_from(AI_TOKEN))

    def test_a_grant_from_before_says_nothing_about_who_asked(self):
        old = self.f.gateway.oauth.sign("access", {"exp": self.f.clock() + 60, "aud": f"{PUBLIC}/mcp", "j": "old"})
        self.assertNotIn("assistant", self.command_from(old))

    def test_a_request_delivered_again_still_says_who_asked(self):
        first = self.command_from(self.access_for("https://agent.meta.ai/cb"))
        self.f.reply(first["cmd"], waiting(id=first["id"]))
        self.assertEqual("muse", self.f.next_commands("p2")[0]["assistant"])

    def test_the_rest_door_says_who_asked_too(self):
        token = self.access_for("https://grok.com/cb")
        self.assertEqual(200, self.f.ai("POST", "/v1/call-requests", {"shop_name": "店"}, token=token)[0])
        self.assertEqual("grok", self.f.next_commands()[0]["assistant"])

    # --- one AI and the request of another ---

    def tokens_of_both(self):
        grok = self.access_for("https://grok.com/cb")
        return grok, self.access_for("https://agent.meta.ai/cb")

    def test_the_rest_door_keeps_the_request_of_another_ai_out_of_sight(self):
        grok, muse = self.tokens_of_both()
        created = json.loads(self.f.ai("POST", "/v1/call-requests", {"shop_name": "店"}, token=grok)[2])
        path = f"/v1/call-requests/{created['id']}"
        self.assertEqual(404, self.f.ai("GET", path, token=muse)[0])
        self.assertEqual(404, self.f.ai("GET", "/v1/call-requests/latest", token=muse)[0])
        self.assertEqual(404, self.f.ai("POST", f"{path}/cancel", token=muse)[0])
        status, _, payload = self.f.ai("POST", "/v1/call-requests", {"shop_name": "店"}, token=muse)
        self.assertEqual(409, status)
        self.assertNotIn(created["id"], payload.decode("utf-8"))
        self.assertEqual(200, self.f.ai("GET", path, token=grok)[0])
        self.assertEqual(200, self.f.ai("GET", path)[0])

    def test_the_mcp_door_keeps_the_request_of_another_ai_out_of_sight(self):
        grok, muse = self.tokens_of_both()

        def call(name, arguments, token):
            message = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": arguments}}
            return json.dumps(self.f.mcp(message, token=token)[2], ensure_ascii=False)

        created = json.loads(self.f.ai("POST", "/v1/call-requests", {"shop_name": "店"}, token=grok)[2])
        for reply in (call("get_call_request", {"id": created["id"]}, muse), call("cancel_call_request", {"id": created["id"]}, muse), call("get_call_request", {}, muse)):
            self.assertIn(relay.MESSAGE_NOT_FOUND, reply)
        busy = call("create_call_request", {"shop_name": "店"}, muse)
        self.assertIn(relay.MESSAGE_BUSY_ELSEWHERE, busy)
        self.assertNotIn(created["id"], busy)
        self.assertIn(created["id"], call("get_call_request", {"id": created["id"]}, grok))

    # --- discovery ---

    def test_the_protected_resource_metadata(self):
        for path in ("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"):
            body = json.loads(self.public("GET", path)[2])
            self.assertEqual(f"{PUBLIC}/mcp", body["resource"])
            self.assertEqual([PUBLIC], body["authorization_servers"])

    def test_the_authorization_server_metadata(self):
        body = json.loads(self.public("GET", "/.well-known/oauth-authorization-server")[2])
        self.assertEqual(PUBLIC, body["issuer"])
        self.assertEqual(f"{PUBLIC}/oauth/authorize", body["authorization_endpoint"])
        self.assertEqual(f"{PUBLIC}/oauth/token", body["token_endpoint"])
        self.assertEqual(f"{PUBLIC}/oauth/register", body["registration_endpoint"])
        self.assertEqual(["S256"], body["code_challenge_methods_supported"])
        self.assertEqual(["none"], body["token_endpoint_auth_methods_supported"])
        self.assertTrue(body["authorization_response_iss_parameter_supported"])

    # --- registering ---

    def test_registering_gives_a_client(self):
        body = self.register()
        self.assertTrue(body["client_id"])
        self.assertEqual([self.REDIRECT], body["redirect_uris"])
        self.assertEqual("none", body["token_endpoint_auth_method"])

    def test_a_redirect_must_be_https_or_loopback(self):
        status, _, payload = self.public("POST", "/oauth/register", {"redirect_uris": ["http://evil.example/cb"]})
        self.assertEqual(400, status)
        self.assertEqual("invalid_redirect_uri", json.loads(payload)["error"])
        self.assertEqual(201, self.public("POST", "/oauth/register", {"redirect_uris": ["http://127.0.0.1:9/cb"]})[0])

    def test_a_redirect_with_a_line_break_cannot_be_registered(self):
        status, _, payload = self.public("POST", "/oauth/register", {"redirect_uris": ["https://grok.com/cb\r\nX-Injected: 1"]})
        self.assertEqual(400, status)
        self.assertEqual("invalid_redirect_uri", json.loads(payload)["error"])

    def test_a_disguised_host_cannot_be_registered(self):
        for uri in (
            "https://evil.com\\.grok.com/cb",
            "https://grok.com\\@evil.com/cb",
            "https://user@grok.com/cb",
            "https://grok.com%2f.evil.com/cb",
            "https://grok.com:notaport/cb",
            "https://grok.com\"/cb",
        ):
            status, _, payload = self.public("POST", "/oauth/register", {"redirect_uris": [uri]})
            self.assertEqual(400, status, uri)
            self.assertEqual("invalid_redirect_uri", json.loads(payload)["error"], uri)

    def test_a_plain_host_with_a_port_can_be_registered(self):
        self.assertEqual(201, self.public("POST", "/oauth/register", {"redirect_uris": ["https://cb.grok.com:8443/cb?x=a@b"]})[0])

    def test_registration_needs_redirects(self):
        self.assertEqual(400, self.public("POST", "/oauth/register", {"client_name": "x"})[0])

    # --- the consent page ---

    def test_the_page_says_who_is_asking_and_where_it_goes(self):
        client = self.register()["client_id"]
        status, headers, payload = self.get_authorize(client)
        self.assertEqual(200, status)
        text = payload.decode("utf-8")
        self.assertIn("grok.com", text)
        self.assertIn("Grok &lt;b&gt;", text)
        self.assertNotIn("<b>", text)
        self.assertEqual("no-store", headers["cache-control"])
        self.assertIn("frame-ancestors 'none'", headers["content-security-policy"])
        self.assertEqual("same-origin", headers["referrer-policy"])

    def test_the_form_gets_through_with_the_origin_a_browser_sends(self):
        # Under no-referrer a browser sends "Origin: null" with the form, and the Origin check refused it
        client = self.register()["client_id"]
        self.assertEqual("same-origin", self.get_authorize(client)[1]["referrer-policy"])
        form = {**self.authorize_query(client), "owner_token": AI_TOKEN}
        status = self.public("POST", "/oauth/authorize", urllib.parse.urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded", "Origin": PUBLIC})[0]
        self.assertEqual(302, status)

    def test_a_redirect_that_is_not_allowed_never_leaves_the_page(self):
        client = self.register("https://evil.example/cb")["client_id"]
        status, headers, payload = self.get_authorize(client, redirect="https://evil.example/cb")
        self.assertEqual(400, status)
        self.assertIn("evil.example", payload.decode("utf-8"))
        self.assertNotIn("location", headers)

    def test_a_host_can_be_added_to_the_list(self):
        f = Fixture(config(redirect_hosts=("grok.com", "extra.example")))
        status, _, payload = f.request("POST", "/oauth/register", {}, {"redirect_uris": ["https://cb.extra.example/x"]})
        self.assertEqual(201, status)
        client = json.loads(payload)["client_id"]
        query = urllib.parse.urlencode(self.authorize_query(client, redirect="https://cb.extra.example/x"))
        self.assertEqual(200, f.request("GET", f"/oauth/authorize?{query}")[0])

    def test_an_unregistered_redirect_is_refused_without_redirecting(self):
        client = self.register()["client_id"]
        status, headers, _ = self.get_authorize(client, redirect="https://grok.com/other")
        self.assertEqual(400, status)
        self.assertNotIn("location", headers)

    def test_an_unknown_client_is_refused_without_redirecting(self):
        status, headers, _ = self.get_authorize("forged")
        self.assertEqual(400, status)
        self.assertNotIn("location", headers)

    def test_plain_pkce_is_refused(self):
        client = self.register()["client_id"]
        status, headers, _ = self.get_authorize(client, code_challenge_method="plain")
        self.assertEqual(302, status)
        self.assertIn("error=invalid_request", headers["location"])

    def test_a_missing_challenge_is_refused(self):
        client = self.register()["client_id"]
        status, headers, _ = self.get_authorize(client, code_challenge=None)
        self.assertEqual(302, status)
        self.assertIn("error=invalid_request", headers["location"])

    def test_a_foreign_resource_is_refused(self):
        client = self.register()["client_id"]
        status, headers, _ = self.get_authorize(client, resource="https://other.example/mcp")
        self.assertEqual(302, status)
        self.assertIn("error=invalid_target", headers["location"])

    def test_the_origin_of_the_server_counts_as_the_resource_too(self):
        client = self.register()["client_id"]
        self.assertEqual(200, self.get_authorize(client, resource=PUBLIC)[0])

    def test_a_wrong_token_shows_the_page_again(self):
        client = self.register()["client_id"]
        status, headers, payload = self.post_authorize(client, token="wrong")
        self.assertEqual(200, status)
        self.assertNotIn("location", headers)
        self.assertIn("トークンが違います", payload.decode("utf-8"))

    def test_the_right_token_redirects_with_a_code(self):
        client = self.register()["client_id"]
        code = self.code_for(client)
        self.assertTrue(code)

    def test_the_owner_token_is_not_in_the_page_or_the_redirect(self):
        client = self.register()["client_id"]
        _, headers, payload = self.post_authorize(client)
        self.assertNotIn(AI_TOKEN, headers["location"])
        self.assertNotIn(AI_TOKEN, payload.decode("utf-8"))

    # --- the token ---

    def test_a_code_becomes_an_access_token(self):
        client = self.register()["client_id"]
        status, body = self.token(self.code_for(client), client)
        self.assertEqual(200, status)
        self.assertEqual("Bearer", body["token_type"])
        self.assertTrue(body["access_token"])
        self.assertNotIn("refresh_token", body)
        self.assertEqual(relay.ACCESS_TOKEN_SECONDS, body["expires_in"])

    def test_the_access_token_opens_the_ai_door(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
        status, _, reply = self.f.mcp(message, token=self.access_token())
        self.assertEqual(200, status)
        self.assertEqual({}, reply["result"])

    def test_the_access_token_works_whichever_way_the_resource_was_named(self):
        message = {"jsonrpc": "2.0", "id": 1, "method": "ping"}
        for resource in (f"{PUBLIC}/mcp", PUBLIC, f"{PUBLIC}/", f"{PUBLIC}/mcp/", None):
            client = self.register()["client_id"]
            status, body = self.token(self.code_for(client, resource=resource), client, resource=resource)
            self.assertEqual(200, status, resource)
            self.assertEqual(200, self.f.mcp(message, token=body["access_token"])[0], resource)

    def test_a_grant_made_for_the_bare_address_before_still_works(self):
        now = self.f.clock()
        access = self.f.gateway.oauth.sign("access", {"exp": now + 60, "aud": PUBLIC, "j": "old"})
        self.assertEqual(200, self.f.mcp({"jsonrpc": "2.0", "id": 1, "method": "ping"}, token=access)[0])

    def test_a_grant_for_another_server_does_not_work(self):
        now = self.f.clock()
        access = self.f.gateway.oauth.sign("access", {"exp": now + 60, "aud": "https://other.example.test/mcp", "j": "x"})
        self.assertEqual(401, self.f.mcp({"jsonrpc": "2.0", "id": 1, "method": "ping"}, token=access)[0])

    def test_another_resource_is_still_refused(self):
        client = self.register()["client_id"]
        status, _, _ = self.post_authorize(client, resource="https://other.example.test/mcp")
        self.assertEqual(302, status)
        status, body = self.token(self.code_for(client), client, resource="https://other.example.test/mcp")
        self.assertEqual((400, "invalid_target"), (status, body["error"]))

    def token_with_basic(self, code, username, password=""):
        form = {"grant_type": "authorization_code", "code": code, "redirect_uri": self.REDIRECT, "code_verifier": self.verifier}
        basic = base64.b64encode(f"{urllib.parse.quote(username, safe='')}:{password}".encode()).decode()
        status, _, payload = self.public("POST", "/oauth/token", urllib.parse.urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded", "Authorization": f"Basic {basic}"})
        return status, json.loads(payload)

    def test_the_client_id_may_come_in_http_basic(self):
        client = self.register()["client_id"]
        status, body = self.token_with_basic(self.code_for(client), client)
        self.assertEqual(200, status)
        self.assertTrue(body["access_token"])

    def test_the_fixed_client_may_come_in_http_basic_with_any_secret(self):
        status, _ = self.token_with_basic(self.code_for(relay.PUBLIC_CLIENT_ID), relay.PUBLIC_CLIENT_ID, "anything")
        self.assertEqual(200, status)

    def test_another_client_in_http_basic_is_refused(self):
        client = self.register()["client_id"]
        status, body = self.token_with_basic(self.code_for(client), "someone-else")
        self.assertEqual((400, "invalid_grant"), (status, body["error"]))

    def test_broken_http_basic_is_only_a_missing_client(self):
        client = self.register()["client_id"]
        form = {"grant_type": "authorization_code", "code": self.code_for(client), "redirect_uri": self.REDIRECT, "code_verifier": self.verifier}
        for value in ("Basic !!!", "Basic e30="):
            status, _, _ = self.public("POST", "/oauth/token", urllib.parse.urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded", "Authorization": value})
            self.assertEqual(400, status, value)

    def test_the_access_token_does_not_open_the_phone_door(self):
        access = self.access_token()
        status, _, _ = self.f.request("GET", "/phone/next?instance=x&wait=0", {"authorization": f"Bearer {access}"})
        self.assertEqual(401, status)

    def test_a_wrong_verifier_is_refused(self):
        client = self.register()["client_id"]
        status, body = self.token(self.code_for(client), client, verifier="w" * 64)
        self.assertEqual(400, status)
        self.assertEqual("invalid_grant", body["error"])

    def test_a_short_verifier_is_refused(self):
        client = self.register()["client_id"]
        status, body = self.token(self.code_for(client), client, verifier="short")
        self.assertEqual(400, status)
        self.assertEqual("invalid_grant", body["error"])

    def test_a_code_works_once(self):
        client = self.register()["client_id"]
        code = self.code_for(client)
        self.assertEqual(200, self.token(code, client)[0])
        self.assertEqual("invalid_grant", self.token(code, client)[1]["error"])

    def test_a_code_expires(self):
        client = self.register()["client_id"]
        code = self.code_for(client)
        self.f.clock.now += relay.CODE_SECONDS
        self.assertEqual("invalid_grant", self.token(code, client)[1]["error"])

    def test_the_redirect_must_match(self):
        client = self.register()["client_id"]
        code = self.code_for(client)
        self.assertEqual("invalid_grant", self.token(code, client, redirect="https://grok.com/other")[1]["error"])

    def test_the_client_must_match(self):
        client = self.register()["client_id"]
        other = self.register("https://grok.com/other")["client_id"]
        code = self.code_for(client)
        self.assertEqual("invalid_grant", self.token(code, other)[1]["error"])

    def test_a_code_from_before_a_restart_is_not_reused(self):
        client = self.register()["client_id"]
        code = self.code_for(client)
        self.f.clock.now += 1
        restarted = relay.Gateway(relay.Desk(clock=self.f.clock), config(), clock=self.f.clock, log=self.f.logs.append)
        form = {"grant_type": "authorization_code", "code": code, "client_id": client, "redirect_uri": self.REDIRECT, "code_verifier": self.verifier}
        status, _, payload = restarted.handle("POST", "/oauth/token", {"content-type": "application/x-www-form-urlencoded"}, urllib.parse.urlencode(form).encode())
        self.assertEqual(400, status)
        self.assertEqual("invalid_grant", json.loads(payload)["error"])

    def test_only_a_form_is_taken_at_the_token_door(self):
        status, _, _ = self.public("POST", "/oauth/token", {"grant_type": "authorization_code"})
        self.assertEqual(400, status)

    def test_an_unsupported_grant(self):
        client = self.register()["client_id"]
        status, body = self.token("x", client, grant_type="password")
        self.assertEqual((400, "unsupported_grant_type"), (status, body["error"]))

    def test_the_access_token_survives_a_restart(self):
        access = self.access_token()
        restarted = relay.Gateway(relay.Desk(clock=self.f.clock), config(), clock=self.f.clock, log=self.f.logs.append)
        status, _, _ = restarted.handle("POST", "/mcp", {"authorization": f"Bearer {access}", "content-type": "application/json"}, b'{"jsonrpc":"2.0","id":1,"method":"ping"}')
        self.assertEqual(200, status)

    def test_changing_the_ai_token_takes_back_every_access_token(self):
        access = self.access_token()
        rotated = relay.Gateway(relay.Desk(clock=self.f.clock), config(ai_token="another-" + "b" * 40), clock=self.f.clock, log=self.f.logs.append)
        status, _, _ = rotated.handle("POST", "/mcp", {"authorization": f"Bearer {access}", "content-type": "application/json"}, b"{}")
        self.assertEqual(401, status)

    def test_an_expired_access_token_is_refused(self):
        access = self.access_token()
        self.f.clock.now += relay.ACCESS_TOKEN_SECONDS
        self.assertEqual(401, self.f.ai("POST", "/mcp", {}, token=access)[0])

    def test_a_forged_access_token_is_refused(self):
        self.assertEqual(401, self.f.ai("POST", "/mcp", {}, token="abc.def")[0])

    def test_a_code_is_not_an_access_token(self):
        client = self.register()["client_id"]
        self.assertEqual(401, self.f.ai("POST", "/mcp", {}, token=self.code_for(client))[0])

    def test_the_fixed_public_client(self):
        code = self.code_for(relay.PUBLIC_CLIENT_ID)
        status, body = self.token(code, relay.PUBLIC_CLIENT_ID)
        self.assertEqual(200, status)
        self.assertTrue(body["access_token"])

    def test_nothing_is_redirected_to_a_disguised_host(self):
        for uri in ("https://evil.com\\.grok.com/cb", "https://user@grok.com/cb"):
            for changes in ({}, {"response_type": "token"}, {"code_challenge": None}):
                status, headers, payload = self.get_authorize(relay.PUBLIC_CLIENT_ID, redirect=uri, **changes)
                self.assertEqual(400, status, uri)
                self.assertNotIn("location", headers, uri)
                self.assertNotIn("evil.com", headers.get("location", ""))
            status, headers, _ = self.post_authorize(relay.PUBLIC_CLIENT_ID, redirect=uri)
            self.assertEqual(400, status, uri)
            self.assertNotIn("location", headers, uri)

    def test_the_fixed_client_also_needs_an_allowed_host(self):
        status, headers, _ = self.get_authorize(relay.PUBLIC_CLIENT_ID, redirect="https://evil.example/cb")
        self.assertEqual(400, status)
        self.assertNotIn("location", headers)


class Logs(unittest.TestCase):
    def test_nothing_of_a_request_or_a_token_is_logged(self):
        f = Fixture()
        f.ai("POST", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "create_call_request", "arguments": BOOKING}})
        f.ai("POST", "/mcp", b"{broken", token=AI_TOKEN)
        f.request("POST", "/mcp", {"Authorization": "Bearer guessing"}, b"{}")
        text = "\n".join(f.logs)
        for secret in (NUMBER, "テスト", AI_TOKEN, PHONE_TOKEN, "guessing", "店"):
            self.assertNotIn(secret, text)
        self.assertTrue(any("/mcp" in line for line in f.logs))

    def test_the_query_string_is_not_logged(self):
        f = Fixture()
        f.request("GET", "/oauth/authorize?state=secret-state")
        self.assertNotIn("secret-state", "\n".join(f.logs))


class TheSocket(unittest.TestCase):
    """What only shows on a real connection."""

    def serve(self, **options):
        server = relay.make_server(relay.Desk(), config(), host="127.0.0.1", port=0, log=lambda line: None, **options)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return server.server_address[1]

    def connect(self, port):
        connection = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.addCleanup(connection.close)
        return connection

    def send(self, port, head):
        connection = self.connect(port)
        connection.sendall(head.encode("latin-1"))
        return connection.recv(4096).decode("latin-1")

    def test_a_negative_length_is_refused_before_anything_is_read(self):
        reply = self.send(self.serve(), "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n")
        self.assertIn(" 400 ", reply.splitlines()[0])

    def test_a_length_that_is_not_digits_is_refused(self):
        for length in ("1_0", "+5", "abc"):
            reply = self.send(self.serve(), f"POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: {length}\r\n\r\n")
            self.assertIn(" 400 ", reply.splitlines()[0], length)

    PING = b'{"jsonrpc":"2.0","id":1,"method":"ping"}'

    def chunked_head(self):
        return f"POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {AI_TOKEN}\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"

    @staticmethod
    def chunks(body, size=7):
        parts = [body[i : i + size] for i in range(0, len(body), size)]
        return b"".join(b"%x\r\n%s\r\n" % (len(p), p) for p in parts) + b"0\r\n\r\n"

    @staticmethod
    def read_response(connection):
        """One whole response: the head and the body can come in separate pieces."""
        data = b""
        while b"\r\n\r\n" not in data:
            part = connection.recv(4096)
            if not part:
                return data.decode("latin-1")
            data += part
        head, _, body = data.partition(b"\r\n\r\n")
        length = next((int(line.split(b":")[1]) for line in head.split(b"\r\n") if line.lower().startswith(b"content-length:")), 0)
        while len(body) < length:
            part = connection.recv(4096)
            if not part:
                break
            body += part
        return (head + b"\r\n\r\n" + body).decode("latin-1")

    def post_chunked(self, port, encoded):
        connection = self.connect(port)
        connection.sendall(self.chunked_head().encode("latin-1") + encoded)
        return connection, self.read_response(connection)

    def test_a_chunked_body_is_read(self):
        _, reply = self.post_chunked(self.serve(), self.chunks(self.PING))
        self.assertIn(" 200 ", reply.splitlines()[0])
        self.assertIn('"id":1', reply.replace(" ", ""))

    def test_a_chunked_body_is_read_when_the_coding_is_in_capitals(self):
        connection = self.connect(self.serve())
        connection.sendall(self.chunked_head().replace("chunked", "Chunked").encode("latin-1") + self.chunks(self.PING))
        self.assertIn(" 200 ", connection.recv(4096).decode("latin-1").splitlines()[0])

    def test_chunk_extensions_and_trailers_are_skipped(self):
        size = b"%x" % len(self.PING)
        encoded = size + b";name=value\r\n" + self.PING + b"\r\n0\r\nX-Trailer: 1\r\n\r\n"
        _, reply = self.post_chunked(self.serve(), encoded)
        self.assertIn(" 200 ", reply.splitlines()[0])

    def test_the_connection_goes_on_after_a_chunked_body(self):
        connection, reply = self.post_chunked(self.serve(), self.chunks(self.PING))
        self.assertIn(" 200 ", reply.splitlines()[0])
        connection.sendall(self.chunked_head().encode("latin-1") + self.chunks(self.PING))
        self.assertIn(" 200 ", self.read_response(connection).splitlines()[0])

    def test_too_long_a_chunked_body_is_refused(self):
        huge = b"%x\r\n" % (relay.MAX_BODY_BYTES + 1)
        _, reply = self.post_chunked(self.serve(), huge)
        self.assertIn(" 413 ", reply.splitlines()[0])

    def test_many_chunks_cannot_go_over_the_limit_either(self):
        part = b"x" * (relay.MAX_BODY_BYTES // 2 + 1)
        encoded = b"%x\r\n%s\r\n%x\r\n%s\r\n" % (len(part), part, len(part), part)
        _, reply = self.post_chunked(self.serve(), encoded)
        self.assertIn(" 413 ", reply.splitlines()[0])

    def test_a_chunk_size_that_is_not_hexadecimal_is_refused(self):
        for size in (b"zz", b"-1", b"+5", b"", b"1_0"):
            _, reply = self.post_chunked(self.serve(), size + b"\r\n")
            self.assertIn(" 400 ", reply.splitlines()[0], size)

    def test_a_chunk_without_its_line_end_is_refused(self):
        _, reply = self.post_chunked(self.serve(), b"2\r\nabXX0\r\n\r\n")
        self.assertIn(" 400 ", reply.splitlines()[0])

    def test_endless_trailers_are_refused(self):
        _, reply = self.post_chunked(self.serve(), b"0\r\n" + b"X: 1\r\n" * (relay.MAX_TRAILER_LINES + 1) + b"\r\n")
        self.assertIn(" 400 ", reply.splitlines()[0])

    def test_a_length_and_chunks_together_are_refused(self):
        reply = self.send(self.serve(), "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        self.assertIn(" 400 ", reply.splitlines()[0])

    def test_two_transfer_codings_are_refused(self):
        reply = self.send(self.serve(), "POST /mcp HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: gzip\r\n\r\n0\r\n\r\n")
        self.assertIn(" 400 ", reply.splitlines()[0])

    def test_another_transfer_coding_is_refused(self):
        reply = self.send(self.serve(), "POST /mcp HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip, chunked\r\n\r\n")
        self.assertIn(" 411 ", reply.splitlines()[0])

    def test_a_client_that_stops_in_the_middle_of_a_chunk_is_cut_off(self):
        connection = self.connect(self.serve(read_deadline_seconds=0.3))
        connection.sendall(self.chunked_head().encode("latin-1") + b"a\r\n123")
        connection.settimeout(5)
        started = time.monotonic()
        self.assertEqual(b"", connection.recv(4096))
        self.assertLess(time.monotonic() - started, 4)

    def test_too_long_a_body_is_refused_before_it_is_read(self):
        reply = self.send(self.serve(), f"POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: {relay.MAX_BODY_BYTES + 1}\r\n\r\n")
        self.assertIn(" 413 ", reply.splitlines()[0])

    def test_a_client_that_stops_sending_is_cut_off(self):
        connection = self.connect(self.serve(read_deadline_seconds=0.3))
        connection.sendall(b"POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n12345")
        connection.settimeout(5)
        started = time.monotonic()
        self.assertEqual(b"", connection.recv(4096))
        self.assertLess(time.monotonic() - started, 4)

    def test_a_long_poll_is_not_cut_off_by_the_read_deadline(self):
        port = self.serve(read_deadline_seconds=0.2)
        request = urllib.request.Request(f"http://127.0.0.1:{port}/phone/next?instance=p&wait=1", headers={"Authorization": f"Bearer {PHONE_TOKEN}"})
        with urllib.request.urlopen(request, timeout=10) as response:
            self.assertEqual(200, response.status)

    def test_more_connections_than_the_limit_are_closed_at_once(self):
        port = self.serve(max_connections=2)
        held = [self.connect(port) for _ in range(2)]
        time.sleep(0.1)
        extra = self.connect(port)
        extra.settimeout(5)
        self.assertEqual(b"", extra.recv(4096))
        self.assertEqual(2, len(held))

    def test_a_connection_that_ends_frees_its_place(self):
        port = self.serve(max_connections=1)
        first = socket.create_connection(("127.0.0.1", port), timeout=10)
        first.close()
        time.sleep(0.2)
        reply = self.send(port, "GET /nope HTTP/1.1\r\nHost: x\r\n\r\n")
        self.assertIn(" 404 ", reply.splitlines()[0])


# --- five steps, over real HTTP, against a pretend phone ---


class PretendPhone:
    """The app's side of the wire: long-polls /phone/next, answers like PhoneRequestHandler."""

    REQUIRED = ("shop_name", "phone_number", "windows", "party_size", "reservation_name")

    def __init__(self, base, token):
        self.base = base
        self.token = token
        self.instance = "pretend-1"
        self.stop = threading.Event()
        self.approved_requests = []
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.lock = threading.Lock()
        self.waiting = None
        self.calls_placed = 0

    def http(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method, headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = response.read()
                return response.status, json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            return error.code, None
        except OSError:
            return 0, None  # the server is shutting down

    def run(self):
        while not self.stop.is_set():
            status, reply = self.http("GET", f"/phone/next?instance={self.instance}&wait=1")
            if status != 200:
                time.sleep(0.05)
                continue
            for command in reply["commands"]:
                self.http("POST", "/phone/reply", {"cmd": command["cmd"], "reply": self.handle(command)})

    def handle(self, command):
        if command["method"] == "cancel":
            return {"ok": True, "id": command["id"], "status": "cancelled", "message": "取り下げました"}
        args = command["arg"]
        missing = [f for f in self.REQUIRED if f not in args]
        if missing:
            return {
                "ok": True,
                "status": "needs_info",
                "message": "足りない情報があります",
                "questions": [{"field": f, "question": f"{f} を教えてください"} for f in missing],
            }
        with self.lock:
            self.waiting = (command["id"], args)
        return {"ok": True, "id": command["id"], "status": "waiting_for_approval", "phone_notified": True, "message": "本人の承認を待っています"}

    def owner_approves_for_the_time_asked(self):
        """The owner reads the request on the confirm screen and approves it for call_at."""
        with self.lock:
            request_id, args = self.waiting
            self.waiting = None
        self.approved_requests.append(args)
        self.push(request_id, {"ok": True, "id": request_id, "status": "scheduled", "message": "指定の時刻を待っています", "scheduled_for": args["call_at"]})
        return request_id, args

    def alarm_rings_and_the_call_ends(self, request_id):
        self.push(request_id, {"ok": True, "id": request_id, "status": "calling", "message": "電話をしています"})
        self.calls_placed += 1
        report = {
            "outcome": "booked",
            "practice": False,
            "verified": True,
            "summary": "予約できました",
            "pending_items": ["コースの確認"],
            "booking": {"start": "2026-11-14T19:00", "party_size": 2, "name_used": "テスト"},
            "answered": True,
        }
        self.push(request_id, {"ok": True, "id": request_id, "status": "finished", "message": "電話が終わりました", "result": report})

    def push(self, request_id, state):
        status, _ = self.http("POST", "/phone/state", {"id": request_id, "state": state})
        assert status == 204, status


class McpClient:
    """What Muse's or Grok's bridge does: JSON-RPC over HTTP POST with a bearer token."""

    def __init__(self, base, token):
        self.base = base
        self.token = token
        self.next_id = 0

    def call(self, name, arguments):
        self.next_id += 1
        params = {"name": name, "arguments": arguments, "_meta": {mcp.META_VERSION: "2026-07-28", mcp.META_CAPABILITIES: {}}}
        message = {"jsonrpc": "2.0", "id": self.next_id, "method": "tools/call", "params": params}
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": "2026-07-28",
            "Mcp-Method": "tools/call",
            "Mcp-Name": name,
        }
        request = urllib.request.Request(self.base + "/mcp", data=json.dumps(message).encode(), method="POST", headers=headers)
        with urllib.request.urlopen(request, timeout=30) as response:
            body = json.loads(response.read())
        result = body["result"]
        return result["structuredContent"]


class FiveSteps(unittest.TestCase):
    """Muse is asked, gives AITEL the details, is asked for what is missing, the phone calls at the time, the result comes back."""

    def setUp(self):
        self.server = relay.make_server(relay.Desk(), config(), host="127.0.0.1", port=0, log=lambda line: None)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        port = self.server.server_address[1]
        self.base = f"http://127.0.0.1:{port}"
        self.phone = PretendPhone(self.base, PHONE_TOKEN)
        self.phone.thread.start()
        self.addCleanup(self.phone.stop.set)

    def test_the_whole_way(self):
        muse = McpClient(self.base, AI_TOKEN)
        details = {"shop_name": "寿司 ＋/=& 店", "phone_number": NUMBER, "call_at": "2026-11-10T11:00:00+09:00"}

        # 2. Muse gives AITEL the details it has
        first = muse.call("create_call_request", details)

        # 4. AITEL asks Muse for what is missing
        self.assertEqual("needs_info", first["status"])
        asked = {q["field"] for q in first["questions"]}
        self.assertEqual({"windows", "party_size", "reservation_name"}, asked)
        self.assertEqual(0, self.phone.calls_placed)

        # Muse answers by sending it again with the answers in it
        details.update({"windows": [{"date": "2026-11-14", "from": "19:00"}], "party_size": 2, "reservation_name": "テスト"})
        second = muse.call("create_call_request", details)
        self.assertEqual("waiting_for_approval", second["status"])
        self.assertEqual(0, self.phone.calls_placed, "nothing is called before the owner approves")

        # The answers are in the request the owner reads
        with self.phone.lock:
            self.assertEqual(2, self.phone.waiting[1]["party_size"])
            self.assertEqual("テスト", self.phone.waiting[1]["reservation_name"])

        # 3. The owner approves on the phone, and the phone calls at the time
        request_id, args = self.phone.owner_approves_for_the_time_asked()
        self.assertEqual(second["id"], request_id)
        self.assertEqual("2026-11-10T11:00:00+09:00", args["call_at"])
        state = muse.call("get_call_request", {"id": request_id})
        self.assertEqual("scheduled", state["status"])
        self.assertEqual("2026-11-10T11:00:00+09:00", state["scheduled_for"])
        self.assertEqual(0, self.phone.calls_placed)

        self.phone.alarm_rings_and_the_call_ends(request_id)
        self.assertEqual(1, self.phone.calls_placed)

        # 5. The result comes back to Muse
        state = muse.call("get_call_request", {})
        self.assertEqual((request_id, "finished"), (state["id"], state["status"]))
        self.assertEqual("booked", state["result"]["outcome"])
        self.assertEqual(["コースの確認"], state["result"]["pending_items"])
        self.assertNotIn(NUMBER, json.dumps(state))

    def test_the_oauth_door_works_end_to_end(self):
        # What Grok's connector does: 401, discovery, registration, consent, code, token, tools
        verifier = "x" * 60
        request = urllib.request.Request(self.base + "/mcp", data=b"{}", method="POST", headers={"Content-Type": "application/json"})
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(request, timeout=10)
        self.assertEqual(401, caught.exception.code)
        self.assertIn("resource_metadata", caught.exception.headers["WWW-Authenticate"])

        redirect = "https://grok.com/cb"
        registered = json.loads(self.post("/oauth/register", json.dumps({"redirect_uris": [redirect]}).encode(), "application/json"))
        client = registered["client_id"]
        form = {
            "response_type": "code",
            "client_id": client,
            "redirect_uri": redirect,
            "state": "s",
            "code_challenge": challenge_of(verifier),
            "code_challenge_method": "S256",
            "resource": f"{PUBLIC}/mcp",
            "owner_token": AI_TOKEN,
        }
        code = self.post_form("/oauth/authorize", form)
        token_body = json.loads(self.post("/oauth/token", urllib.parse.urlencode({"grant_type": "authorization_code", "code": code, "client_id": client, "redirect_uri": redirect, "code_verifier": verifier}).encode(), "application/x-www-form-urlencoded"))
        grok = McpClient(self.base, token_body["access_token"])
        self.assertEqual("needs_info", grok.call("create_call_request", {"shop_name": "店"})["status"])

    def post(self, path, data, content_type):
        request = urllib.request.Request(self.base + path, data=data, method="POST", headers={"Content-Type": content_type})
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.read()

    def post_form(self, path, form):
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args):
                return None

        opener = urllib.request.build_opener(NoRedirect)
        request = urllib.request.Request(self.base + path, data=urllib.parse.urlencode(form).encode(), method="POST", headers={"Content-Type": "application/x-www-form-urlencoded"})
        with self.assertRaises(urllib.error.HTTPError) as caught:
            opener.open(request, timeout=10)
        self.assertEqual(302, caught.exception.code)
        return dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(caught.exception.headers["Location"]).query))["code"]


if __name__ == "__main__":
    unittest.main()
