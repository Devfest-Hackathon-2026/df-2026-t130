#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""The server between the cloud AIs and the phone. A mailbox, nothing more.

Muse or Grok leaves a request here (MCP over HTTP, or REST); the phone's app fetches it over its
own outgoing connection. Everything about a request is decided on the phone, by the same code as
every other way in (PhoneRequestHandler.handle), and a request waits there until its owner approves
it on the confirm screen. Nothing here can start a call, and nothing here looks inside a request.

Standard library only. Requests and results are held in memory only; nothing about them is written
to disk or to the log (ADR-042). Run it behind HTTPS; see README.md for the commands.

    python aitel_relay.py              serve on 127.0.0.1:8787 (AITEL_RELAY_HOST, AITEL_RELAY_PORT)
    python aitel_relay.py --new-token  print a new random token

It needs aitel_mcp.py next to it, or in ../mcp when run from the repository.
"""

import base64
import binascii
import datetime
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import socket
import sys
import threading
import time
import urllib.parse
import uuid
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mcp"))

import aitel_mcp as mcp  # noqa: E402  pylint: disable=wrong-import-position

MAX_BODY_BYTES = 64 * 1024
# A chunked body (what a proxy in front may turn any body into): its size lines and trailers
MAX_CHUNK_LINE_BYTES = 256
MAX_TRAILER_LINES = 16
MAX_CONCURRENT = 16
# Every open connection holds a thread; a long poll holds one for up to 25 seconds
MAX_CONNECTIONS = 32
# How long a whole request may take to arrive, however slowly it comes
READ_DEADLINE_SECONDS = 20
# The phone's PhoneRequestStore.WAITING_MS: an owner has this long to approve, from when the phone got it
WAITING_SECONDS = 30 * 60
WAITING_GRACE_SECONDS = 5 * 60
# Not fetched by the phone: long enough for the 15-minute background check to come round a few times
UNDELIVERED_SECONDS = 60 * 60
# A call that says nothing for this long: the phone's process probably died
CALLING_STALE_SECONDS = 60 * 60
KEPT_SECONDS = 24 * 60 * 60
MAX_ITEMS = 50
DEFAULT_CREATE_WAIT_SECONDS = 15
MAX_POLL_SECONDS = 25
# A command the phone took and did not answer goes out again after this long: the answer to its poll may have been lost
RESEND_SECONDS = 20
# How often a waiting poll looks for a command whose time to go out again has come
RESEND_CHECK_SECONDS = 1
# A tool call can wait for the phone (MAX_WAIT_SECONDS) and for one last reply
REPLY_TIMEOUT_SECONDS = mcp.MAX_WAIT_SECONDS + DEFAULT_CREATE_WAIT_SECONDS + 15

ACCESS_TOKEN_SECONDS = 30 * 24 * 60 * 60
CODE_SECONDS = 60
PUBLIC_CLIENT_ID = "aitel"
# www.cursor.com: where the custom connectors of Grok come back to after the consent
DEFAULT_REDIRECT_HOSTS = ("grok.com", "x.ai", "www.cursor.com")
# Which AI a grant is for, by the host its code went back to: never what an AI says about itself
ASSISTANT_HOSTS = (("grok.com", "grok"), ("x.ai", "grok"), ("www.cursor.com", "grok"), ("agent.meta.ai", "muse"))
SCOPE = "aitel"
HEADER_MISMATCH = -32020
MIN_TOKEN_CHARS = 32

# Status.wire of PhoneRequestStore in the app, and two of this server's own
STATUS_DELIVERING = "delivering"
STATUS_WAITING = "waiting_for_approval"
ACTIVE = (STATUS_DELIVERING, STATUS_WAITING, "scheduled", "calling")

# What RFC 3986 allows in a URI. A browser reads anything else in its own way: a backslash as a slash
URI_CHARS = re.compile(r"[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+")
HOST_CHARS = re.compile(r"[A-Za-z0-9.-]+")
CONTENT_LENGTH = re.compile(r"[0-9]+")
CHUNK_SIZE = re.compile(rb"[0-9A-Fa-f]{1,8}")

MESSAGE_DELIVERING = "スマホがこの依頼をまだ受け取っていません。本人にスマホの AITEL を開くよう伝えてください。届くと status が変わります"
MESSAGE_BUSY = "前の依頼がまだ終わっていません。一度に 1 件だけ預かります。新しい依頼は送らず、get_call_request で前の依頼の状態を確かめてください。本人がまだ承認していなければ、cancel_call_request で取り下げてから送り直せます"
MESSAGE_BUSY_ELSEWHERE = "スマホで別の依頼を処理しています"
MESSAGE_NOT_FOUND = "この id の依頼は残っていません。電話をしたかどうかは分かりません。送り直さず、本人に確かめてください"
MESSAGE_EXPIRED = "期限が切れました。電話はしていません"
MESSAGE_UNKNOWN = "電話を始めたあとの記録が残っていません。予約などができたかは分かりません。送り直さず、本人に確かめてください"
MESSAGE_CANCELLED = "取り下げました。電話はかけません"
MESSAGE_CANCEL_PENDING = "取り下げを受け付けました。スマホに届くと効きます。それより先に本人が承認していたら、電話は進みます"

PAGE_STYLE = "body{font:16px sans-serif;margin:2rem auto;max-width:32rem;padding:0 1rem}input{font:inherit;padding:.5rem;width:100%;box-sizing:border-box;margin:.5rem 0}button{font:inherit;padding:.6rem 1.2rem}.warn{background:#fff3cd;padding:.75rem}"


class ConfigProblem(Exception):
    """The server cannot start with this environment. The message says what to fix."""


def new_token():
    return secrets.token_urlsafe(32)


def same(given, expected):
    """Compares two secrets without giving away where they differ."""
    return hmac.compare_digest(given.encode("utf-8"), expected.encode("utf-8"))


def iso_seconds(text):
    """Seconds since the epoch of an ISO-8601 time with an offset, or None."""
    if not isinstance(text, str):
        return None
    try:
        parsed = datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.timestamp()


class Config:
    """What the server is told at the start. The tokens come from the environment and go nowhere else."""

    def __init__(self, ai_token, phone_token, public_url, allowed_origins=(), redirect_hosts=DEFAULT_REDIRECT_HOSTS, host="127.0.0.1", port=8787):
        self.ai_token = ai_token
        self.phone_token = phone_token
        self.public_url = public_url
        self.allowed_origins = tuple(allowed_origins)
        self.redirect_hosts = tuple(redirect_hosts)
        self.host = host
        self.port = port

    @classmethod
    def from_environment(cls, env):
        ai_token = env.get("AITEL_AI_TOKEN", "")
        phone_token = env.get("AITEL_PHONE_TOKEN", "")
        if len(ai_token) < MIN_TOKEN_CHARS or len(phone_token) < MIN_TOKEN_CHARS:
            raise ConfigProblem(f"AITEL_AI_TOKEN と AITEL_PHONE_TOKEN を {MIN_TOKEN_CHARS} 文字以上で設定してください（aitel_relay.py --new-token で作れます）")
        if ai_token == phone_token:
            raise ConfigProblem("AITEL_AI_TOKEN と AITEL_PHONE_TOKEN は別の値にしてください")
        public_url = env.get("AITEL_PUBLIC_URL", "").rstrip("/")
        if not public_url.startswith("https://"):
            raise ConfigProblem("AITEL_PUBLIC_URL を https:// で始まる公開の URL で設定してください")
        origins = tuple(o.strip() for o in env.get("AITEL_RELAY_ALLOWED_ORIGINS", "").split(",") if o.strip())
        hosts = DEFAULT_REDIRECT_HOSTS + tuple(h.strip() for h in env.get("AITEL_OAUTH_REDIRECT_HOSTS", "").split(",") if h.strip())
        try:
            port = int(env.get("AITEL_RELAY_PORT", "8787"))
        except ValueError as e:
            raise ConfigProblem("AITEL_RELAY_PORT は数字で設定してください") from e
        return cls(ai_token, phone_token, public_url, origins, hosts, env.get("AITEL_RELAY_HOST", "127.0.0.1"), port)


class Item:
    """One request. [state] is what the phone last said about it; [args] is kept only while it may be needed again."""

    __slots__ = ("id", "args", "state", "created", "updated", "waiting_since", "taken_by", "assistant")

    def __init__(self, request_id, args, now, assistant=None):
        self.id = request_id
        self.args = args
        self.assistant = assistant
        self.state = None
        self.created = now
        self.updated = now
        self.waiting_since = now
        self.taken_by = None

    @property
    def status(self):
        return self.state.get("status") if self.state else STATUS_DELIVERING


class Waiter:
    """Someone waiting for the phone's answer to one command."""

    __slots__ = ("method", "item", "event", "reply")

    def __init__(self, method, item):
        self.method = method
        self.item = item
        self.event = threading.Event()
        self.reply = None


class Desk:
    """The mailbox. `ask` answers what `aitel_mcp.Tools` asks, in the same words as the phone would."""

    def __init__(self, clock=time.time, create_wait_seconds=DEFAULT_CREATE_WAIT_SECONDS):
        self.clock = clock
        self.create_wait_seconds = create_wait_seconds
        self.max_poll_seconds = MAX_POLL_SECONDS
        self.changed = threading.Condition()
        self.items = OrderedDict()
        self.commands = []
        self.waiters = {}
        self.instance = None
        self.last_seen = None
        self.latest = None

    # --- what the AI's tools call ---

    def ask(self, method, payload, assistant=None):
        if method == "create":
            return self.create(payload, assistant)
        if method == "get":
            return self.read(payload.get("id"), assistant)
        if method == "cancel":
            return self.cancel(payload.get("id"), assistant)
        if method == "ping":
            with self.changed:
                return {"ok": True, "phone_last_seen_seconds": self.seen_ago()}
        return {"ok": False, "error": "unknown_method", "message": "知らない操作です"}

    def seen_ago(self):
        return None if self.last_seen is None else int(self.clock() - self.last_seen)

    def create(self, args, assistant=None):
        with self.changed:
            self.tidy()
            active = next((item for item in self.items.values() if item.status in ACTIVE), None)
            if active is not None:
                if not self.visible_to(active, assistant):
                    return {"ok": False, "error": "busy", "message": MESSAGE_BUSY_ELSEWHERE}
                return {"ok": False, "error": "busy", "message": MESSAGE_BUSY, "existing_id": active.id, "existing_status": active.status}
            item = Item(str(uuid.uuid4()), args, self.clock(), assistant)
            self.items[item.id] = item
            self.latest = item.id
            waiter = self.send("create", item, args)
        waiter.event.wait(self.create_wait_seconds)
        with self.changed:
            return self.view(item, assistant)

    @staticmethod
    def visible_to(item, caller):
        """[caller] None: the AI token itself, which sees everything. A request no AI is known for is open to every caller."""
        return caller is None or item.assistant is None or item.assistant == caller

    def latest_for(self, caller):
        if caller is None:
            return self.items.get(self.latest)
        return next((item for item in reversed(self.items.values()) if self.visible_to(item, caller)), None)

    def read(self, request_id, caller=None):
        with self.changed:
            self.tidy()
            item = self.items.get(request_id) if request_id is not None else self.latest_for(caller)
            return self.view(item, caller) if item is not None and self.visible_to(item, caller) else self.not_found()

    def cancel(self, request_id, caller=None):
        with self.changed:
            self.tidy()
            item = self.items.get(request_id)
            if item is None or not self.visible_to(item, caller):
                return self.not_found()
            # Only what the phone never took can be taken back here. What it took, it may be showing already
            if item.status == STATUS_DELIVERING and item.taken_by is None:
                self.finish(item, "cancelled", MESSAGE_CANCELLED)
                return self.view(item, caller)
            if item.status not in ACTIVE:
                return self.view(item, caller)
            self.drop_delivery(item)
            waiter = self.send("cancel", item, {"id": item.id})
        waiter.event.wait(self.create_wait_seconds)
        with self.changed:
            if waiter.reply is None:
                return {"ok": True, "id": item.id, "status": item.status, "cancel_pending": True, "message": MESSAGE_CANCEL_PENDING}
            return self.view(item, caller) if item.status == "cancelled" else waiter.reply

    def drop_delivery(self, item):
        """A cancel is on its way: the request itself must not reach the phone after it, nor a new process of the phone."""
        item.args = None
        self.commands = [c for c in self.commands if not (c["id"] == item.id and c["method"] == "create")]
        for cmd in [cmd for cmd, waiter in self.waiters.items() if waiter.item is item and waiter.method == "create"]:
            del self.waiters[cmd]

    @staticmethod
    def not_found():
        return {"ok": False, "error": "not_found", "message": MESSAGE_NOT_FOUND}

    def view(self, item, caller=None):
        if item.state is None:
            return {"ok": True, "id": item.id, "status": STATUS_DELIVERING, "message": MESSAGE_DELIVERING, "phone_last_seen_seconds": self.seen_ago()}
        # Refused: not ok, so that the AI is told so as an error, with the reasons in its words
        reply = {**item.state, "ok": item.status != "refused", "id": item.id}
        # The phone's own "busy" names the request that waits there, which may be another AI's
        if caller is not None and "existing_id" in reply:
            named = self.items.get(reply["existing_id"])
            if named is None or not self.visible_to(named, caller):
                return {**{k: v for k, v in reply.items() if k not in ("existing_id", "existing_status")}, "message": MESSAGE_BUSY_ELSEWHERE}
        return reply

    # --- what the phone calls ---

    def take_commands(self, instance, wait_seconds):
        """What the phone should do now. The first call from a new process gets again what the old one may have lost."""
        with self.changed:
            self.last_seen = self.clock()
            if instance != self.instance:
                self.instance = instance
                self.redeliver(instance)
            self.tidy()
            give_up = time.monotonic() + max(0, min(wait_seconds, self.max_poll_seconds))
            while not self.due_commands():
                remaining = give_up - time.monotonic()
                if remaining <= 0:
                    break
                self.changed.wait(min(remaining, RESEND_CHECK_SECONDS))
            self.last_seen = self.clock()
            return [self.hand_over(command, instance) for command in self.due_commands()]

    def due_commands(self):
        """Commands stay until they are answered or their request ends: never taken, or taken long enough ago."""
        now = self.clock()
        return [c for c in self.commands if c["sent_at"] is None or now - c["sent_at"] >= RESEND_SECONDS]

    def hand_over(self, command, instance):
        command["sent_at"] = self.clock()
        item = self.items.get(command["id"])
        if item is not None and command["method"] == "create":
            item.taken_by = instance
        return {k: v for k, v in command.items() if k != "sent_at"}

    def take_reply(self, cmd, reply):
        with self.changed:
            self.commands = [c for c in self.commands if c["cmd"] != cmd]
            waiter = self.waiters.pop(cmd, None)
            if waiter is None:
                return
            # Answers about what still waits. Later than a state the phone has reported, they would only take its place
            if waiter.item.id in self.items and waiter.item.status in (STATUS_DELIVERING, STATUS_WAITING):
                if waiter.method == "create":
                    self.accept(waiter.item, reply)
                else:
                    self.cancel_answered(waiter.item, reply)
            waiter.reply = reply
            waiter.event.set()

    def cancel_answered(self, item, reply):
        if reply.get("ok") is True and reply.get("status"):
            self.accept(item, reply)
        elif reply.get("error") == "already_ended" and reply.get("status"):
            self.accept(item, {**reply, "ok": True})
        elif reply.get("error") == "not_found" and item.status in (STATUS_DELIVERING, STATUS_WAITING):
            # The phone does not have it: nothing is left that could be approved
            self.finish(item, "cancelled", MESSAGE_CANCELLED)

    def take_state(self, request_id, state):
        """The phone says what became of a request. An id it no longer knows is kept: a restart of this server loses nothing."""
        with self.changed:
            item = self.items.get(request_id)
            if item is None:
                item = Item(request_id, None, self.clock())
                self.items[request_id] = item
                self.latest = request_id
            # A new process that no longer knows how the call ended: what was heard before is worth more
            if item.status == "finished" and state.get("status") == "unknown":
                return
            self.accept(item, state)

    # --- the mailbox itself ---

    def send(self, method, item, arg):
        command = {"cmd": str(uuid.uuid4()), "method": method, "id": item.id, "arg": arg, "sent_at": None}
        # Beside arg: that is what the AI wrote
        if item.assistant is not None:
            command["assistant"] = item.assistant
        self.commands.append(command)
        waiter = Waiter(method, item)
        self.waiters[command["cmd"]] = waiter
        self.changed.notify_all()
        return waiter

    def accept(self, item, state):
        state = state if state.get("ok") is True else {**state, "status": "refused"}
        item.state = state
        item.updated = self.clock()
        if state.get("status") == STATUS_WAITING:
            item.waiting_since = item.updated
        else:
            # Not needed again: the request was decided, or was never taken
            item.args = None
        if state.get("status") not in ACTIVE:
            self.drop_commands(item)
        self.changed.notify_all()

    def redeliver(self, instance):
        queued = {command["id"] for command in self.commands}
        for command in self.commands:
            command["sent_at"] = None
        for item in self.items.values():
            if item.args is not None and item.id not in queued and item.taken_by != instance and item.status in (STATUS_DELIVERING, STATUS_WAITING):
                self.send("create", item, item.args)

    def finish(self, item, status, message):
        item.state = {"ok": True, "status": status, "message": message}
        item.updated = self.clock()
        item.args = None
        self.drop_commands(item)
        self.changed.notify_all()

    def drop_commands(self, item):
        """The request is over: nothing more goes out for it, and no answer is waited for."""
        self.commands = [c for c in self.commands if c["id"] != item.id]
        for cmd in [cmd for cmd, waiter in self.waiters.items() if waiter.item is item]:
            del self.waiters[cmd]

    def tidy(self):
        """Looks at the clock: nothing else notices that a request ran out of time."""
        now = self.clock()
        for item in list(self.items.values()):
            status = item.status
            if status == STATUS_DELIVERING and now - item.created >= UNDELIVERED_SECONDS:
                self.finish(item, "expired", MESSAGE_EXPIRED)
            elif status == STATUS_WAITING and now - item.waiting_since >= WAITING_SECONDS + WAITING_GRACE_SECONDS:
                self.finish(item, "expired", MESSAGE_EXPIRED)
            elif status == "calling" and now - item.updated >= CALLING_STALE_SECONDS:
                self.finish(item, "unknown", MESSAGE_UNKNOWN)
            elif status == "scheduled":
                fires = iso_seconds(item.state.get("scheduled_for"))
                if fires is not None and now - fires >= CALLING_STALE_SECONDS:
                    self.finish(item, "unknown", MESSAGE_UNKNOWN)
            elif status not in ACTIVE and now - item.updated >= KEPT_SECONDS:
                del self.items[item.id]
        ended = [item for item in self.items.values() if item.status not in ACTIVE]
        for item in ended[: max(0, len(self.items) - MAX_ITEMS)]:
            del self.items[item.id]


class OAuth:
    """Just enough of OAuth 2.1 for a connector that asks only for a URL.

    Everything is signed, so nothing about a client is stored. The key comes from the AI token:
    whoever has that token could get a code anyway, and a new AI token takes every grant back. The
    owner proves who they are on the consent page by pasting that token.
    """

    def __init__(self, config, clock):
        self.config = config
        self.clock = clock
        self.key = hmac.new(config.ai_token.encode("utf-8"), b"aitel-relay-oauth-v1", hashlib.sha256).digest()
        self.started = clock()
        self.resource = f"{config.public_url}/mcp"
        self.lock = threading.Lock()
        self.used = {}

    def sign(self, kind, data):
        body = mcp.to_base64url(json.dumps({"k": kind, **data}, separators=(",", ":")).encode("utf-8"))
        return f"{body}.{self.mac(kind, body)}"

    def mac(self, kind, body):
        return mcp.to_base64url(hmac.new(self.key, f"{kind}.{body}".encode("utf-8"), hashlib.sha256).digest())

    def read(self, kind, token):
        """The signed data, or None if it is forged, of another kind, or too old."""
        if not isinstance(token, str):
            return None
        body, separator, mac = token.partition(".")
        if not separator or not same(self.mac(kind, body), mac):
            return None
        try:
            data = json.loads(mcp.from_base64url(body))
        except (ValueError, binascii.Error):
            return None
        if not isinstance(data, dict) or data.get("k") != kind:
            return None
        expires = data.get("exp")
        if expires is not None and self.clock() >= expires:
            return None
        return data

    # --- registration ---

    def client(self, client_id):
        """(redirect URIs, name) of a client, or None. The fixed public client may use any allowed redirect."""
        if client_id == PUBLIC_CLIENT_ID:
            return None, "手で登録したクライアント"
        data = self.read("client", client_id)
        return (data.get("r"), data.get("n")) if data else None

    def register(self, metadata):
        redirects = metadata.get("redirect_uris") if isinstance(metadata, dict) else None
        if not isinstance(redirects, list) or not 1 <= len(redirects) <= 5 or not all(isinstance(r, str) for r in redirects):
            return 400, {"error": "invalid_client_metadata", "error_description": "redirect_uris が要ります"}
        if not all(self.acceptable_redirect(r) for r in redirects):
            return 400, {"error": "invalid_redirect_uri", "error_description": "https か 127.0.0.1 の URL にしてください"}
        name = metadata.get("client_name")
        name = name[:100] if isinstance(name, str) and name else "（名前なし）"
        client_id = self.sign("client", {"r": redirects, "n": name})
        return 201, {
            "client_id": client_id,
            "client_id_issued_at": int(self.clock()),
            "client_name": name,
            "redirect_uris": redirects,
            "token_endpoint_auth_method": "none",
            "grant_types": ["authorization_code"],
            "response_types": ["code"],
        }

    @staticmethod
    def acceptable_redirect(uri):
        """An https (or loopback) URI whose host a browser cannot read differently from this server."""
        if len(uri) > 500 or not URI_CHARS.fullmatch(uri):
            return False
        parts = urllib.parse.urlsplit(uri)
        if parts.fragment or not parts.hostname:
            return False
        try:
            port = parts.port
        except ValueError:
            return False
        host = parts.hostname
        if not HOST_CHARS.fullmatch(host) and host != "::1":
            return False
        # Exactly the host and the port: no user name, nothing that could end the host early
        shown = f"[{host}]" if ":" in host else host
        if parts.netloc.lower() != (f"{shown}:{port}" if port is not None else shown).lower():
            return False
        return parts.scheme == "https" or (parts.scheme == "http" and OAuth.is_loopback(host))

    @staticmethod
    def is_loopback(host):
        return host in ("127.0.0.1", "localhost", "::1")

    def host_allowed(self, host):
        return self.is_loopback(host) or any(host == h or host.endswith("." + h) for h in self.config.redirect_hosts)

    def redirect_problem(self, client, redirect_uri):
        """Why the code must not go to [redirect_uri], or None. Only the owner reads this: nothing is redirected."""
        registered, _ = client
        if not isinstance(redirect_uri, str) or not self.acceptable_redirect(redirect_uri):
            return "リダイレクト先の形が正しくありません"
        if registered is not None and redirect_uri not in registered:
            return "登録されていないリダイレクト先です"
        host = urllib.parse.urlsplit(redirect_uri).hostname
        if not self.host_allowed(host):
            return f"{host} へのリダイレクトは許可していません（許可するなら AITEL_OAUTH_REDIRECT_HOSTS に足してください）"
        return None

    def resource_problem(self, resource):
        return resource is not None and resource.rstrip("/") not in (self.resource, self.config.public_url)

    # --- codes and tokens ---

    def new_code(self, client_id, redirect_uri, challenge):
        now = self.clock()
        # For the MCP door, however the client named this server (resource_problem let only those through)
        return self.sign(
            "code",
            {"exp": now + CODE_SECONDS, "iat": now, "c": client_id, "r": redirect_uri, "p": challenge, "u": self.resource, "j": secrets.token_hex(8)},
        )

    def valid_access(self, token):
        data = self.read("access", token)
        return data is not None and data.get("aud") in (self.resource, self.config.public_url)

    def assistant(self, token):
        """Which AI this grant was given to. None: the AI token itself, an older grant, a host not in the list."""
        data = self.read("access", token)
        return data.get("by") if data else None

    @staticmethod
    def assistant_of(redirect_uri):
        host = urllib.parse.urlsplit(redirect_uri).hostname or ""
        return next((name for known, name in ASSISTANT_HOSTS if host == known or host.endswith("." + known)), None)

    def token(self, form):
        if form.get("grant_type") != "authorization_code":
            return 400, {"error": "unsupported_grant_type"}
        code = self.read("code", form.get("code", ""))
        if code is None or code.get("iat", 0) < self.started:
            return 400, {"error": "invalid_grant"}
        if form.get("client_id") != code.get("c") or form.get("redirect_uri") != code.get("r"):
            return 400, {"error": "invalid_grant"}
        verifier = form.get("code_verifier", "")
        if not 43 <= len(verifier) <= 128:
            return 400, {"error": "invalid_grant"}
        computed = mcp.to_base64url(hashlib.sha256(verifier.encode("ascii", "replace")).digest())
        if not same(computed, code.get("p", "")):
            return 400, {"error": "invalid_grant"}
        resource = form.get("resource")
        if self.resource_problem(resource):
            return 400, {"error": "invalid_target"}
        with self.lock:
            now = self.clock()
            self.used = {j: exp for j, exp in self.used.items() if exp > now}
            if code["j"] in self.used:
                return 400, {"error": "invalid_grant"}
            self.used[code["j"]] = code["exp"]
        access = self.sign("access", {"exp": now + ACCESS_TOKEN_SECONDS, "aud": code.get("u"), "j": secrets.token_hex(8), "by": self.assistant_of(code.get("r"))})
        return 200, {"access_token": access, "token_type": "Bearer", "expires_in": ACCESS_TOKEN_SECONDS, "scope": SCOPE}

    # --- the metadata ---

    def protected_resource_metadata(self):
        return {
            "resource": self.resource,
            "authorization_servers": [self.config.public_url],
            "bearer_methods_supported": ["header"],
            "scopes_supported": [SCOPE],
        }

    def server_metadata(self):
        base = self.config.public_url
        return {
            "issuer": base,
            "authorization_endpoint": f"{base}/oauth/authorize",
            "token_endpoint": f"{base}/oauth/token",
            "registration_endpoint": f"{base}/oauth/register",
            "response_types_supported": ["code"],
            "grant_types_supported": ["authorization_code"],
            "code_challenge_methods_supported": ["S256"],
            "token_endpoint_auth_methods_supported": ["none"],
            "scopes_supported": [SCOPE],
            "authorization_response_iss_parameter_supported": True,
        }


def bearer(headers):
    value = headers.get("authorization", "")
    return value[7:].strip() if value[:7].lower() == "bearer " else None


def basic_client_id(headers):
    """The client id of an HTTP Basic header (RFC 6749 2.3.1), or None. The secret means nothing here: every client is public."""
    value = headers.get("authorization", "")
    if value[:6].lower() != "basic ":
        return None
    try:
        decoded = base64.b64decode(value[6:].strip(), validate=True).decode("utf-8")
    except (ValueError, binascii.Error):
        return None
    client_id, separator, _ = decoded.partition(":")
    return urllib.parse.unquote_plus(client_id) if separator else None


def first_values(query):
    """The first value of each field of a query or form body."""
    return {k: v[0] for k, v in urllib.parse.parse_qs(query, keep_blank_values=True).items()}


def json_reply(status, value):
    return status, {"Content-Type": "application/json", "Cache-Control": "no-store"}, json.dumps(value, ensure_ascii=False).encode("utf-8")


def empty(status, headers=None):
    return status, {"Cache-Control": "no-store", **(headers or {})}, b""


class Capture:
    """Receives the one answer of mcp.Server for one HTTP request."""

    def __init__(self):
        self.event = threading.Event()
        self.data = None

    def write(self, data):
        self.data = data
        self.event.set()


def decode_header(value):
    """The value of a header that may use the base64 form of the MCP spec."""
    if value.startswith("=?base64?") and value.endswith("?="):
        try:
            return base64.b64decode(value[9:-2], validate=True).decode("utf-8")
        except (ValueError, binascii.Error):
            return None
    return value


def header_mismatch(headers, message):
    """Why a request of the modern era must be rejected, or None."""
    params = message.get("params") if isinstance(message.get("params"), dict) else {}
    meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
    if headers.get("mcp-protocol-version") != meta.get(mcp.META_VERSION):
        return "MCP-Protocol-Version does not match the request"
    if headers.get("mcp-method") != message.get("method"):
        return "Mcp-Method does not match the request"
    if message.get("method") == "tools/call":
        name = headers.get("mcp-name")
        if name is None or decode_header(name) != params.get("name"):
            return "Mcp-Name does not match the request"
    return None


class Caller:
    """The desk as one caller of the tools uses it: what it creates carries which AI asked, and it sees what that AI may."""

    def __init__(self, desk, assistant):
        self.desk = desk
        self.assistant = assistant

    def ask(self, method, payload):
        return self.desk.ask(method, payload, self.assistant)


class Gateway:
    """Every HTTP request ends up here. The result is (status, headers, body); nothing is logged but route and status."""

    def __init__(self, desk, config, clock=time.time, log=None):
        self.desk = desk
        self.config = config
        self.clock = clock
        self.log = log or (lambda line: print(line, file=sys.stderr, flush=True))
        self.oauth = OAuth(config, clock)

    def handle(self, method, target, headers, body=b""):
        parts = urllib.parse.urlsplit(target)
        route = self.route_label(parts.path)
        try:
            result = self.route(method, parts.path, parts.query, headers, body)
        except Exception as e:  # pylint: disable=broad-except
            # The type only: a message might quote the request
            self.log(f"aitel-relay: {method} {route} failed: {type(e).__name__}")
            return json_reply(500, {"error": "internal"})
        self.log(f"aitel-relay: {method} {route} {result[0]}")
        return result

    @staticmethod
    def route_label(path):
        """Never the id in the path or the query: only which door was used."""
        if path.startswith("/v1/call-requests/"):
            return "/v1/call-requests/{id}"
        if path == "/mcp" or path == "/openapi.json" or path == "/v1/call-requests" or path.startswith(("/phone/", "/oauth/", "/.well-known/")):
            return path
        return "(other)"

    def route(self, method, path, query, headers, body):
        if len(body) > MAX_BODY_BYTES:
            return empty(413)
        origin = headers.get("origin")
        if origin is not None and origin != self.origin_of_public() and origin not in self.config.allowed_origins:
            return empty(403)
        if path == "/mcp":
            return self.mcp_door(method, headers, body)
        if path.startswith("/phone/"):
            return self.phone_door(method, path, query, headers, body)
        if path == "/openapi.json" or path.startswith("/v1/"):
            return self.rest_door(method, path, query, headers, body)
        if path.startswith("/.well-known/"):
            return self.discovery(method, path)
        if path.startswith("/oauth/"):
            return self.oauth_door(method, path, query, headers, body)
        return empty(404)

    def origin_of_public(self):
        return self.config.public_url

    # --- who may come in ---

    def is_ai(self, headers):
        token = bearer(headers)
        return token is not None and (same(token, self.config.ai_token) or self.oauth.valid_access(token))

    def is_phone(self, headers):
        token = bearer(headers)
        return token is not None and same(token, self.config.phone_token)

    def tools_for(self, headers):
        return mcp.Tools(Caller(self.desk, self.oauth.assistant(bearer(headers))))

    def ai_refused(self):
        metadata = f"{self.config.public_url}/.well-known/oauth-protected-resource"
        return empty(401, {"WWW-Authenticate": f'Bearer resource_metadata="{metadata}"'})

    # --- MCP ---

    def mcp_door(self, method, headers, body):
        if not self.is_ai(headers):
            return self.ai_refused()
        if method != "POST":
            return empty(405, {"Allow": "POST"})
        if not headers.get("content-type", "").lower().startswith("application/json"):
            return empty(415)
        try:
            message = json.loads(body)
        except (ValueError, RecursionError):
            message = None
        if not isinstance(message, dict):
            return self.run_mcp(self.tools_for(headers), body, None)
        params = message.get("params") if isinstance(message.get("params"), dict) else {}
        modern = isinstance(params.get("_meta"), dict) and mcp.META_VERSION in params["_meta"]
        if modern:
            problem = header_mismatch(headers, message)
            if problem:
                return json_reply(400, {"jsonrpc": "2.0", "id": message.get("id"), "error": {"code": HEADER_MISMATCH, "message": problem}})
        return self.run_mcp(self.tools_for(headers), body, message, modern)

    def run_mcp(self, tools, body, message, modern=False):
        capture = Capture()
        server = mcp.Server(tools, capture.write)
        # One HTTP request is one exchange: there is no handshake to remember
        server.initialized = True
        server.receive(body)
        if message is not None and ("id" not in message or "result" in message or "error" in message):
            return empty(202)
        if not capture.event.wait(REPLY_TIMEOUT_SECONDS):
            request_id = message.get("id") if message else None
            return json_reply(504, {"jsonrpc": "2.0", "id": request_id, "error": {"code": mcp.INTERNAL_ERROR, "message": "The server timed out"}})
        reply = json.loads(capture.data)
        return self.status_for(reply, modern), {"Content-Type": "application/json", "Cache-Control": "no-store"}, capture.data.rstrip(b"\n")

    @staticmethod
    def status_for(reply, modern):
        error = reply.get("error")
        if not isinstance(error, dict):
            return 200
        code = error.get("code")
        if modern and code == mcp.METHOD_NOT_FOUND:
            return 404
        if code in (mcp.PARSE_ERROR, mcp.INVALID_REQUEST, HEADER_MISMATCH) or (modern and code in (mcp.INVALID_PARAMS, mcp.UNSUPPORTED_VERSION)):
            return 400
        return 200

    # --- the phone ---

    def phone_door(self, method, path, query, headers, body):
        if not self.is_phone(headers):
            return empty(401, {"WWW-Authenticate": "Bearer"})
        if path == "/phone/next":
            return self.phone_next(method, query)
        if path == "/phone/reply":
            return self.phone_reply(method, body)
        if path == "/phone/state":
            return self.phone_state(method, body)
        return empty(404)

    def phone_next(self, method, query):
        if method != "GET":
            return empty(405, {"Allow": "GET"})
        values = first_values(query)
        instance = values.get("instance", "")
        if not instance or len(instance) > 64:
            return empty(400)
        try:
            wait = float(values.get("wait", "0"))
        except ValueError:
            return empty(400)
        return json_reply(200, {"commands": self.desk.take_commands(instance, wait)})

    def phone_reply(self, method, body):
        if method != "POST":
            return empty(405, {"Allow": "POST"})
        data = self.json_object(body)
        if data is None or not isinstance(data.get("cmd"), str) or not isinstance(data.get("reply"), dict):
            return empty(400)
        self.desk.take_reply(data["cmd"], data["reply"])
        return empty(204)

    def phone_state(self, method, body):
        if method != "POST":
            return empty(405, {"Allow": "POST"})
        data = self.json_object(body)
        if data is None or not isinstance(data.get("id"), str) or not mcp.REQUEST_ID.fullmatch(data["id"]):
            return empty(400)
        state = data.get("state")
        if not isinstance(state, dict) or not isinstance(state.get("status"), str):
            return empty(400)
        self.desk.take_state(data["id"], state)
        return empty(204)

    @staticmethod
    def json_object(body):
        try:
            data = json.loads(body)
        except ValueError:
            return None
        return data if isinstance(data, dict) else None

    # --- REST ---

    COLLECTION = "/v1/call-requests"
    ITEM_PATH = re.compile(r"^/v1/call-requests/(latest|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})(/cancel)?$")

    def rest_door(self, method, path, query, headers, body):
        # Open to anyone: some clients read it before they ask for a token, and it holds only what the tools are
        if path == "/openapi.json":
            return json_reply(200, self.openapi()) if method == "GET" else empty(405, {"Allow": "GET"})
        if not self.is_ai(headers):
            return self.ai_refused()
        if path == self.COLLECTION:
            if method != "POST":
                return empty(405, {"Allow": "POST"})
            args = self.json_object(body)
            if args is None:
                return json_reply(400, {"error": "invalid", "message": "JSON のオブジェクトで送ってください"})
            return self.run_tool(self.tools_for(headers).create, args)
        match = self.ITEM_PATH.match(path)
        if match is None:
            return empty(404)
        key, action = match.groups()
        if action == "/cancel":
            if method != "POST" or key == "latest":
                return empty(405, {"Allow": "POST"})
            return self.run_tool(self.tools_for(headers).cancel, {"id": key})
        if method != "GET":
            return empty(405, {"Allow": "GET"})
        arguments = {} if key == "latest" else {"id": key}
        wait = first_values(query).get("wait_seconds")
        if wait is not None:
            arguments["wait_seconds"] = int(wait) if wait.isdigit() else wait
        return self.run_tool(self.tools_for(headers).get, arguments)

    @staticmethod
    def run_tool(handler, arguments):
        stop = threading.Event()
        try:
            reply = handler(arguments, stop.wait)
        except mcp.ToolProblem as e:
            data = e.data if isinstance(e.data, dict) else {}
            status = {"not_found": 404, "busy": 409, "too_many": 429, "invalid": 422}.get(data.get("error"), 400)
            return json_reply(status, {"error": data.get("error", "bad_request"), "message": e.text, **{k: v for k, v in data.items() if k not in ("ok", "message", "error")}})
        return json_reply(200, {k: v for k, v in reply.items() if k != "ok"})

    def openapi(self):
        request_id = {"name": "id", "in": "path", "required": True, "schema": {"type": "string"}}
        status = {"description": "依頼の状態と結果", "content": {"application/json": {"schema": {"type": "object"}}}}
        return {
            "openapi": "3.1.0",
            "info": {"title": "AITEL 依頼の窓口", "version": mcp.SERVER_VERSION, "description": mcp.INSTRUCTIONS},
            "servers": [{"url": self.config.public_url}],
            "security": [{"bearerAuth": []}],
            "components": {"securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer"}}},
            "paths": {
                self.COLLECTION: {
                    "post": {
                        "operationId": "createCallRequest",
                        "summary": "電話の依頼を預ける（本人がスマホで承認するまで電話はかかりません）",
                        "requestBody": {"required": True, "content": {"application/json": {"schema": mcp.CREATE_SCHEMA}}},
                        "responses": {"200": status},
                    },
                },
                f"{self.COLLECTION}/latest": {"get": {"operationId": "getLatestCallRequest", "summary": "最新の依頼の状態と結果", "responses": {"200": status}}},
                f"{self.COLLECTION}/{{id}}": {
                    "get": {
                        "operationId": "getCallRequest",
                        "summary": "依頼の状態と結果",
                        "parameters": [request_id, {"name": "wait_seconds", "in": "query", "schema": {"type": "integer", "minimum": 0, "maximum": mcp.MAX_WAIT_SECONDS}}],
                        "responses": {"200": status},
                    },
                },
                f"{self.COLLECTION}/{{id}}/cancel": {"post": {"operationId": "cancelCallRequest", "summary": "電話が始まる前の依頼を取り下げる", "parameters": [request_id], "responses": {"200": status}}},
            },
        }

    # --- OAuth ---

    def discovery(self, method, path):
        if method != "GET":
            return empty(405, {"Allow": "GET"})
        if path in ("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"):
            return json_reply(200, self.oauth.protected_resource_metadata())
        if path == "/.well-known/oauth-authorization-server":
            return json_reply(200, self.oauth.server_metadata())
        return empty(404)

    def oauth_door(self, method, path, query, headers, body):
        if path == "/oauth/register":
            if method != "POST":
                return empty(405, {"Allow": "POST"})
            return json_reply(*self.oauth.register(self.json_object(body)))
        if path == "/oauth/token":
            if method != "POST":
                return empty(405, {"Allow": "POST"})
            if not headers.get("content-type", "").lower().startswith("application/x-www-form-urlencoded"):
                return json_reply(400, {"error": "invalid_request"})
            form = first_values(body.decode("utf-8", "replace"))
            if "client_id" not in form and basic_client_id(headers) is not None:
                form["client_id"] = basic_client_id(headers)
            return json_reply(*self.oauth.token(form))
        if path == "/oauth/authorize":
            if method == "GET":
                return self.authorize(first_values(query), None)
            if method == "POST":
                if not headers.get("content-type", "").lower().startswith("application/x-www-form-urlencoded"):
                    return self.page(400, "<p>フォームの形が正しくありません</p>")
                form = first_values(body.decode("utf-8", "replace"))
                return self.authorize(form, form.pop("owner_token", ""))
            return empty(405, {"Allow": "GET, POST"})
        return empty(404)

    def authorize(self, params, owner_token):
        """The consent page. [owner_token] None: show the page; otherwise the owner has pressed the button."""
        client_id = params.get("client_id")
        client = self.oauth.client(client_id) if isinstance(client_id, str) else None
        if client is None:
            return self.page(400, "<p>登録されていないクライアントです</p>")
        problem = self.oauth.redirect_problem(client, params.get("redirect_uri"))
        if problem is not None:
            return self.page(400, f"<p>{html.escape(problem)}</p>")
        redirect = params["redirect_uri"]
        if params.get("response_type") != "code":
            return self.redirect_error(redirect, params, "unsupported_response_type")
        if not params.get("code_challenge") or params.get("code_challenge_method") != "S256":
            return self.redirect_error(redirect, params, "invalid_request")
        if self.oauth.resource_problem(params.get("resource")):
            return self.redirect_error(redirect, params, "invalid_target")
        if owner_token is None:
            return self.consent_page(params, client, None)
        if not same(owner_token, self.config.ai_token):
            return self.consent_page(params, client, "トークンが違います")
        code = self.oauth.new_code(client_id, redirect, params["code_challenge"])
        return self.redirect(redirect, params, {"code": code})

    def redirect_error(self, redirect, params, error):
        return self.redirect(redirect, params, {"error": error})

    def redirect(self, redirect, params, values):
        extra = dict(values)
        if params.get("state") is not None:
            extra["state"] = params["state"]
        extra["iss"] = self.config.public_url
        separator = "&" if urllib.parse.urlsplit(redirect).query else "?"
        return empty(302, {"Location": redirect + separator + urllib.parse.urlencode(extra)})

    def consent_page(self, params, client, error):
        host = urllib.parse.urlsplit(params["redirect_uri"]).hostname
        keep = ("response_type", "client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "resource", "scope")
        hidden = "".join(f'<input type="hidden" name="{name}" value="{html.escape(params[name], quote=True)}">' for name in keep if name in params)
        notice = f'<p class="warn">{html.escape(error)}</p>' if error else ""
        return self.page(
            200,
            f"<h1>AITEL の窓口への接続を許可しますか？</h1>"
            f"<p>接続するもの：{html.escape(client[1])}</p>"
            f"<p>許可すると、接続先 <strong>{html.escape(host)}</strong> が電話の依頼を預けられるようになります。"
            f"電話は、スマホの確認の画面で本人が承認するまでかかりません。</p>"
            f"{notice}"
            f'<form method="post"><input type="password" name="owner_token" required autocomplete="off" '
            f'placeholder="AI 用のトークン（AITEL_AI_TOKEN）">{hidden}<button type="submit">許可する</button></form>',
        )

    @staticmethod
    def page(status, inner):
        body = f"<!doctype html><meta charset=utf-8><title>AITEL</title><style>{PAGE_STYLE}</style>{inner}".encode("utf-8")
        headers = {
            "Content-Type": "text/html; charset=utf-8",
            "Cache-Control": "no-store",
            "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'",
            "X-Frame-Options": "DENY",
            # Not no-referrer: a browser then sends "Origin: null" with the form, and the Origin check refuses it
            "Referrer-Policy": "same-origin",
        }
        return status, headers, body


class BadBody(Exception):
    """A chunked body that cannot be used. [status]: what to answer; None: the client went away."""

    def __init__(self, status):
        super().__init__()
        self.status = status


def read_chunked(rfile):
    """The whole body of a chunked request, within MAX_BODY_BYTES. Raises BadBody."""
    body = bytearray()
    while True:
        line = rfile.readline(MAX_CHUNK_LINE_BYTES + 1)
        if not line:
            raise BadBody(None)
        if len(line) > MAX_CHUNK_LINE_BYTES or not line.endswith(b"\n"):
            raise BadBody(400)
        size_text = line.split(b";", 1)[0].strip()
        if not CHUNK_SIZE.fullmatch(size_text):
            raise BadBody(400)
        size = int(size_text, 16)
        if size == 0:
            break
        if len(body) + size > MAX_BODY_BYTES:
            raise BadBody(413)
        chunk = rfile.read(size)
        ending = rfile.read(2)
        if len(chunk) < size or not ending:
            raise BadBody(None)
        if ending != b"\r\n":
            raise BadBody(400)
        body += chunk
    for _ in range(MAX_TRAILER_LINES):
        line = rfile.readline(MAX_CHUNK_LINE_BYTES + 1)
        if not line:
            raise BadBody(None)
        if line in (b"\r\n", b"\n"):
            return bytes(body)
    raise BadBody(400)


class RelayServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, max_connections):
        self.connections = threading.BoundedSemaphore(max_connections)
        super().__init__(address, handler)

    def process_request(self, request, client_address):
        # One thread per connection: more than this many are turned away at once
        if not self.connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.connections.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connections.release()

    def handle_error(self, request, client_address):
        # The type only: a traceback might quote what was being handled
        print(f"aitel-relay: connection failed: {sys.exc_info()[0].__name__}", file=sys.stderr, flush=True)


def make_server(desk, config, host, port, log=None, max_connections=MAX_CONNECTIONS, read_deadline_seconds=READ_DEADLINE_SECONDS):
    gateway = Gateway(desk, config, log=log)
    slots = threading.BoundedSemaphore(MAX_CONCURRENT)

    class Handler(BaseHTTPRequestHandler):
        server_version = "aitel-relay"
        sys_version = ""
        protocol_version = "HTTP/1.1"
        # So that a client that stops sending does not hold a thread for ever
        timeout = 60

        def respond(self, status, headers, body):
            self.send_response(status)
            for name, value in headers.items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body:
                self.wfile.write(body)

        def handle_one_request(self):
            # The socket timeout limits one read; this limits the whole request, however slowly it comes
            self.reading = threading.Timer(read_deadline_seconds, self.cut_off)
            self.reading.daemon = True
            self.reading.start()
            try:
                super().handle_one_request()
            finally:
                self.reading.cancel()

        def cut_off(self):
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        def refuse(self, status):
            self.close_connection = True
            self.respond(*empty(status))

        def read_body(self):
            """The whole body. None: answered already, or the client went away."""
            codings = self.headers.get_all("Transfer-Encoding")
            if codings:
                if "Content-Length" in self.headers or len(codings) > 1:
                    # Two ways to read the length: a proxy in front could read another one than this server does
                    self.refuse(400)
                    return None
                if codings[0].strip().lower() != "chunked":
                    self.refuse(411)
                    return None
                try:
                    return read_chunked(self.rfile)
                except BadBody as e:
                    if e.status is None:
                        self.close_connection = True
                    else:
                        self.refuse(e.status)
                    return None
            length = self.headers.get("Content-Length", "0")
            if not CONTENT_LENGTH.fullmatch(length):
                self.refuse(400)
                return None
            length = int(length)
            if length > MAX_BODY_BYTES:
                self.refuse(413)
                return None
            body = self.rfile.read(length) if length else b""
            if len(body) < length:
                # Cut off, or the client went away: there is nothing whole to handle
                self.close_connection = True
                return None
            return body

        def serve(self):
            body = self.read_body()
            self.reading.cancel()
            if body is None:
                return
            if not slots.acquire(blocking=False):
                self.respond(*empty(503))
                return
            try:
                result = gateway.handle(self.command, self.path, {k.lower(): v for k, v in self.headers.items()}, body)
            finally:
                slots.release()
            self.respond(*result)

        do_GET = do_POST = do_DELETE = do_PUT = do_PATCH = serve

        def log_message(self, format, *args):  # pylint: disable=redefined-builtin
            pass  # Gateway logs the route and the status; the request line holds the query

    return RelayServer((host, port), Handler, max_connections)


def main(argv):
    if "--new-token" in argv:
        print(new_token())
        return 0
    try:
        config = Config.from_environment(os.environ)
    except ConfigProblem as e:
        print(f"aitel-relay: {e}", file=sys.stderr)
        return 1
    server = make_server(Desk(), config, config.host, config.port)
    print(f"aitel-relay: serving on {config.host}:{config.port} for {config.public_url}", file=sys.stderr, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
