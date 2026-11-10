#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Sets up, starts and tries the AITEL relay, so that nobody has to type long commands.

    python aitel_setup.py init https://your-url    make the two tokens and write relay.env
    python aitel_setup.py serve                    start the relay with relay.env
    python aitel_setup.py check [--local]          try the relay the way the AIs do; no call is placed
    python aitel_setup.py tokens                   show again which token goes where
    python aitel_setup.py rotate phone|ai|all      make new tokens
    python aitel_setup.py url https://new-url      change the public URL
    python aitel_setup.py install-systemd          Linux: place the programs, relay.env and the unit

The steps for people are in docs/mcp-setup.md. The tokens stay in relay.env, which only its owner can
read: they are never on a command line. Standard library only, Python 3.8 or later.
"""

import argparse
import http.client
import json
import os
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ENV_NAME = "relay.env"
SYSTEM_ENV = "/etc/aitel/relay.env"
AI_KEY = "AITEL_AI_TOKEN"
PHONE_KEY = "AITEL_PHONE_TOKEN"
URL_KEY = "AITEL_PUBLIC_URL"
PORT_KEY = "AITEL_RELAY_PORT"
HOST_KEY = "AITEL_RELAY_HOST"
DEFAULT_PORT = "8787"
DEFAULT_HOST = "127.0.0.1"
TOKEN_BYTES = 32
URL_SHAPE = re.compile(r"https://[A-Za-z0-9.-]+(:[0-9]+)?")
ENV_HEADER = "# AITEL relay settings. Keep this file private: it holds the tokens."
# The name in the request `check` sends. No number goes with it, so the phone only asks back
CHECK_SHOP = "接続の確かめ"
TOOLS = ("create_call_request", "get_call_request", "cancel_call_request")
# Longer than the relay waits for the phone before it answers
CHECK_TIMEOUT_SECONDS = 40
# Cloudflare turns away the name urllib gives itself (403, error code 1010), before the server sees anything
USER_AGENT = "aitel-setup/1"
QUICK_TIMEOUT_SECONDS = 5

UNIT_PATH = ("etc", "systemd", "system", "aitel-relay.service")
UNIT = """[Unit]
Description=AITEL relay
Wants=network-online.target
After=network-online.target

[Service]
EnvironmentFile=/etc/aitel/relay.env
ExecStart={python} /opt/aitel/aitel_relay.py
Restart=always
DynamicUser=yes
MemoryMax=128M
TasksMax=64

[Install]
WantedBy=multi-user.target
"""


class Problem(Exception):
    """Something the user can put right. The message says how."""


class Unreachable(Exception):
    """Nothing answered at that address. [certificate]: something did, but its certificate was refused."""

    def __init__(self, certificate=False):
        super().__init__()
        self.certificate = certificate


def say(text=""):
    print(text, flush=True)


# --- the settings file ---


def read_env(path):
    values = {}
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key.strip()] = value
    return values


def env_path(args):
    if args.env:
        return args.env
    next_to_me = os.path.join(HERE, ENV_NAME)
    if not os.path.exists(next_to_me) and os.path.exists(SYSTEM_ENV):
        return SYSTEM_ENV
    return next_to_me


def load(path):
    try:
        values = read_env(path)
    except FileNotFoundError:
        raise Problem(f"{path} がありません。先に python aitel_setup.py init https://あなたの URL を実行してください") from None
    except PermissionError:
        raise Problem(f"{path} を読む権限がありません。sudo を付けて実行してください") from None
    missing = [key for key in (URL_KEY, AI_KEY, PHONE_KEY) if not values.get(key)]
    if missing:
        raise Problem(f"{path} に {'、'.join(missing)} がありません。ファイルを消して python aitel_setup.py init からやり直してください")
    return values


def update_env(path, changes):
    """Only the lines of [changes]: a setting added by hand stays. The file keeps its permissions."""
    with open(path, encoding="utf-8-sig") as f:
        lines = f.read().splitlines()
    left = dict(changes)
    for i, line in enumerate(lines):
        key = line.split("=", 1)[0].strip()
        if key in left:
            lines[i] = f"{key}={left.pop(key)}"
    lines.extend(f"{key}={value}" for key, value in left.items())
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def make_private_on_windows(path):
    """Nobody else's permissions, inherited ones included. By name from whoami: USERNAME alone can name a local account."""
    try:
        me = subprocess.run(["whoami"], stdout=subprocess.PIPE, check=True).stdout.decode("mbcs").strip()
        subprocess.run(["icacls", path, "/inheritance:r", "/grant:r", f"{me}:F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    except (OSError, subprocess.CalledProcessError) as e:
        raise Problem(f"{path} を本人だけが読める形にできませんでした（{type(e).__name__}）。ふつうの内蔵ドライブのフォルダで実行してください") from None


def create_private(path, text):
    """A new file only its owner can read, restricted before the secrets are in it."""
    # The mode is applied to the new file by the OS (and only narrowed by the umask)
    os.close(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600))
    try:
        if os.name == "nt":
            make_private_on_windows(path)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    except BaseException:
        os.remove(path)
        raise


def new_token():
    return secrets.token_urlsafe(TOKEN_BYTES)


def clean_url(raw):
    url = raw.strip().rstrip("/")
    if not URL_SHAPE.fullmatch(url):
        raise Problem("URL は https:// で始まる、入口だけの形で入れてください（例：https://example.ngrok-free.app。最後に /mcp は付けません）")
    return url


# --- what the user copies ---


def show_ai_token(token, url):
    say("AI 用のトークン（Grok の許可の画面と、Muse の安全な入力の欄に貼ります。スマホには入れません）")
    say(f"  {token}")
    say("Grok と Muse に教える URL")
    say(f"  {url}/mcp")


def show_phone_token(token, url):
    say("端末用のトークン（スマホの AITEL の「設定」に貼ります。Grok や Muse には渡しません）")
    say(f"  {token}")
    say("スマホの「サーバーの URL」に入れる URL")
    say(f"  {url}")


def show_keep_safe():
    say()
    say("トークンはパスワードの管理アプリなどに控えてください。チャットやメールに貼らないでください。")


def init(args):
    path = env_path(args)
    url = clean_url(args.url)
    ai_token, phone_token = new_token(), new_token()
    text = "\n".join((ENV_HEADER, f"{URL_KEY}={url}", f"{AI_KEY}={ai_token}", f"{PHONE_KEY}={phone_token}")) + "\n"
    try:
        create_private(path, text)
    except FileExistsError:
        raise Problem(f"{path} はもうあります。作り直すなら python aitel_setup.py rotate all、URL を変えるなら python aitel_setup.py url を使います") from None
    say(f"{path} に書きました（本人だけが読めます）。")
    say()
    show_ai_token(ai_token, url)
    say()
    show_phone_token(phone_token, url)
    show_keep_safe()
    say()
    say("次は python aitel_setup.py serve でサーバーを動かします。")
    return 0


def tokens(args):
    values = load(env_path(args))
    show_ai_token(values[AI_KEY], values[URL_KEY])
    say()
    show_phone_token(values[PHONE_KEY], values[URL_KEY])
    return 0


def rotate(args):
    path = env_path(args)
    values = load(path)
    changes = {}
    if args.which in ("ai", "all"):
        changes[AI_KEY] = new_token()
    if args.which in ("phone", "all"):
        changes[PHONE_KEY] = new_token()
    update_env(path, changes)
    say("新しいトークンを作りました。古いトークンは、サーバーを動かし直すと使えなくなります。")
    say()
    if AI_KEY in changes:
        show_ai_token(changes[AI_KEY], values[URL_KEY])
        say("Grok と Muse のコネクタを、新しいトークンでつなぎ直してください。")
        say()
    if PHONE_KEY in changes:
        show_phone_token(changes[PHONE_KEY], values[URL_KEY])
        say("スマホの設定で、古いトークンを「消す」を押してから、新しいトークンを入れてください。")
    show_keep_safe()
    return 0


def change_url(args):
    path = env_path(args)
    load(path)
    url = clean_url(args.url)
    update_env(path, {URL_KEY: url})
    say(f"URL を {url} にしました。サーバーを動かし直して、スマホと Grok・Muse の URL も変えてください。")
    return 0


# --- starting it ---


def port_taken(host, port):
    try:
        with socket.create_connection(("127.0.0.1" if host == "0.0.0.0" else host, int(port)), 1):
            return True
    except OSError:
        return False


def serve(args):
    values = load(env_path(args))
    host, port = values.get(HOST_KEY, DEFAULT_HOST), values.get(PORT_KEY, DEFAULT_PORT)
    if port_taken(host, port):
        raise Problem(f"ポート {port} はもう使われています。前に動かしたサーバーが残っていないか確かめてください")
    os.environ.update(values)
    try:
        import aitel_relay  # pylint: disable=import-outside-toplevel
    except ImportError:
        raise Problem("aitel_relay.py と aitel_mcp.py を、このファイルと同じフォルダに置いてください") from None
    say("サーバーを動かします。止めるときは Ctrl+C を押します。使っている間は、このウィンドウを閉じないでください。")
    try:
        return aitel_relay.main([])
    except OSError as e:
        raise Problem(f"ポート {port} で待ち受けられませんでした（{type(e).__name__}）") from None


# --- trying it ---


def fetch(method, url, headers=None, body=None, timeout=CHECK_TIMEOUT_SECONDS):
    """(status, body). An answer with an error status is an answer; no answer raises Unreachable."""
    request = urllib.request.Request(url, data=body, method=method, headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as reply:
            return reply.status, reply.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except urllib.error.URLError as e:
        raise Unreachable(isinstance(e.reason, ssl.SSLCertVerificationError)) from None
    except (OSError, http.client.HTTPException):
        raise Unreachable() from None


def bearer(token):
    return {"Authorization": f"Bearer {token}"}


def mcp(base, token, message_id, method, params=None):
    """(status, the reply's JSON, or None when the body was not JSON)."""
    message = {"jsonrpc": "2.0", "id": message_id, "method": method}
    if params is not None:
        message["params"] = params
    status, body = fetch(
        "POST",
        f"{base}/mcp",
        {**bearer(token), "Content-Type": "application/json", "Accept": "application/json, text/event-stream"},
        json.dumps(message).encode("utf-8"),
    )
    try:
        return status, json.loads(body)
    except ValueError:
        return status, None


def call_tool(base, token, message_id, name, arguments):
    status, reply = mcp(base, token, message_id, "tools/call", {"name": name, "arguments": arguments})
    result = reply.get("result") if isinstance(reply, dict) else None
    content = result.get("structuredContent") if isinstance(result, dict) else None
    if status != 200 or not isinstance(content, dict):
        raise Problem(f"{name} の答えが読めませんでした（HTTP {status}）。サーバーのログを見てください")
    return content


def check_refuses_without_token(base):
    status, _ = fetch("POST", f"{base}/mcp", {"Content-Type": "application/json"}, b"{}", QUICK_TIMEOUT_SECONDS * 3)
    if status != 401:
        raise Problem(f"トークンなしの呼び出しは 401 になるはずが、{status} でした。URL の先がこのサーバーではないか、トンネルの先でサーバーが動いていません")
    say("[1/5] トークンなしでは断られます（401）……OK")


def check_public_url(base, public):
    status, body = fetch("GET", f"{base}/.well-known/oauth-protected-resource", timeout=QUICK_TIMEOUT_SECONDS * 3)
    try:
        resource = json.loads(body).get("resource") if status == 200 else None
    except (ValueError, AttributeError):
        resource = None
    if resource != f"{public}/mcp":
        raise Problem(f"サーバーの知っている URL が relay.env の AITEL_PUBLIC_URL と違います（oauth-protected-resource の resource が {resource}）。サーバーを動かし直してください")
    say("[2/5] URL の設定が合っています（oauth-protected-resource）……OK")


def check_ai_token(base, token):
    status, _ = mcp(base, token, 1, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "aitel-setup", "version": "1"}})
    if status == 401:
        raise Problem("AI 用のトークンが通りません。relay.env を変えたあとなら、サーバーを動かし直してください")
    status, reply = mcp(base, token, 2, "tools/list")
    result = reply.get("result") if isinstance(reply, dict) else None
    found = [tool.get("name") for tool in result.get("tools", [])] if isinstance(result, dict) else []
    missing = [name for name in TOOLS if name not in found]
    if status != 200 or missing:
        raise Problem(f"ツールの一覧が足りません（HTTP {status}、足りないもの：{', '.join(missing)}）。aitel_relay.py と aitel_mcp.py の版を確かめてください")
    say("[3/5] AI 用のトークンでツールの一覧が取れます（create_call_request ほか）……OK")


def check_phone_token(base, token):
    # POST on the phone's door: the token is looked at first, then the method is refused. GET would
    # count as the phone having restarted, and could take a request away from the real phone
    status, _ = fetch("POST", f"{base}/phone/next", bearer(token), b"", QUICK_TIMEOUT_SECONDS * 3)
    if status == 401:
        raise Problem("端末用のトークンが通りません。relay.env を変えたあとなら、サーバーを動かし直してください")
    if status != 405:
        raise Problem(f"端末用の入口の答えがおかしいです（HTTP {status}）。URL の先がこのサーバーか確かめてください")
    say("[4/5] 端末用のトークンが通ります……OK")


def check_phone_answers(base, token):
    say("[5/5] 足りない依頼を送ってみます。電話はかかりません（15 秒ほどかかることがあります）")
    reply = call_tool(base, token, 3, "create_call_request", {"shop_name": CHECK_SHOP})
    status = reply.get("status")
    if status == "needs_info":
        say("      スマホが受け取って、足りないことを聞き返しました（needs_info）……OK")
    elif status == "delivering":
        # Left there, it would stand in the way of the next request for an hour
        call_tool(base, token, 4, "cancel_call_request", {"id": reply.get("id")})
        say("      スマホがまだ取りに来ていません（delivering）。依頼は取り下げました。")
        say("      スマホの設定がまだなら、ここまでで正しい状態です。設定のあと、アプリを開いたままもう一度確かめてください。")
    elif reply.get("error") == "busy":
        say("      いま 1 件の依頼が動いているので、ここは確かめませんでした（その依頼には触っていません）。")
    else:
        raise Problem(f"思っていない答えでした（status：{status}、error：{reply.get('error')}）。スマホの AITEL の版を確かめてください")


def run_checks(base, values):
    public = values[URL_KEY]
    check_refuses_without_token(base)
    check_public_url(base, public)
    check_ai_token(base, values[AI_KEY])
    check_phone_token(base, values[PHONE_KEY])
    check_phone_answers(base, values[AI_KEY])


def local_answers(local):
    try:
        fetch("POST", f"{local}/mcp", {"Content-Type": "application/json"}, b"{}", QUICK_TIMEOUT_SECONDS)
        return True
    except Unreachable:
        return False


def check(args):
    values = load(env_path(args))
    local = f"http://127.0.0.1:{values.get(PORT_KEY, DEFAULT_PORT)}"
    base = local if args.local else values[URL_KEY]
    say(f"確かめる先：{base}")
    try:
        run_checks(base, values)
    except Unreachable as e:
        if e.certificate:
            raise Problem(f"{base} の証明書を確かめられませんでした。macOS の python.org の Python なら「Install Certificates.command」を実行してください") from None
        if not args.local and local_answers(local):
            raise Problem(f"サーバーは動いていますが、{base} から届きません。トンネルが動いているか、URL が合っているかを確かめてください") from None
        raise Problem(f"{base} に届きません。python aitel_setup.py serve でサーバーが動いているか確かめてください") from None
    say()
    say("確かめ終わりました。電話はかかっていません。")
    return 0


# --- Linux, always on ---


def install_systemd(args):
    root = args.root
    if root == os.sep and (os.name == "nt" or os.geteuid() != 0):
        raise Problem("Linux の root で実行します：sudo python3 aitel_setup.py install-systemd")
    if root == os.sep and not os.path.isdir("/run/systemd/system"):
        raise Problem("この環境では systemd が動いていません。docs/mcp-setup.md の「パソコン 1 台で」の形で動かしてください")
    source_env = env_path(args)
    if os.path.abspath(source_env) == SYSTEM_ENV:
        raise Problem(f"{SYSTEM_ENV} はもうあります。作り直すときは、先にこのファイルを消してください")
    load(source_env)
    target_env = os.path.join(root, "etc", "aitel", ENV_NAME)
    if os.path.exists(target_env):
        raise Problem(f"{target_env} はもうあります。{ENV_NAME} を置き換えるときは、先にそのファイルを消してください")
    programs = [
        os.path.join(HERE, "aitel_relay.py"),
        os.path.join(HERE, "aitel_setup.py"),
        next((p for p in (os.path.join(HERE, "aitel_mcp.py"), os.path.join(HERE, "..", "mcp", "aitel_mcp.py")) if os.path.exists(p)), os.path.join(HERE, "aitel_mcp.py")),
    ]
    for program in programs:
        if not os.path.exists(program):
            raise Problem(f"{os.path.basename(program)} がありません。aitel_relay.py、aitel_mcp.py、aitel_setup.py を同じフォルダに置いてください")
    opt = os.path.join(root, "opt", "aitel")
    os.makedirs(opt, exist_ok=True)
    for program in programs:
        shutil.copy(program, opt)
        # The service runs as an unnamed user: whatever the umask was, it must be able to read these
        os.chmod(os.path.join(opt, os.path.basename(program)), 0o644)
    os.chmod(opt, 0o755)
    unit = os.path.join(root, *UNIT_PATH)
    os.makedirs(os.path.dirname(unit), mode=0o755, exist_ok=True)
    with open(unit, "w", encoding="utf-8", newline="\n") as f:
        f.write(UNIT.format(python=sys.executable))
    os.makedirs(os.path.dirname(target_env), mode=0o700, exist_ok=True)
    # Moved, so that one copy of the tokens exists. Last, so that a failure above changes nothing
    shutil.move(source_env, target_env)
    if os.name != "nt":
        os.chmod(target_env, 0o600)
    say("置きました：")
    say(f"  {opt}（aitel_relay.py、aitel_mcp.py、aitel_setup.py）")
    say(f"  {target_env}（{source_env} から移しました）")
    say(f"  {unit}")
    say()
    say("次のコマンドで動かします：")
    say("  sudo systemctl daemon-reload")
    say("  sudo systemctl enable --now aitel-relay")
    say("  sudo python3 /opt/aitel/aitel_setup.py check")
    return 0


def parser():
    top = argparse.ArgumentParser(prog="aitel_setup.py", description="AITEL の窓口サーバーを準備して動かします。手順は docs/mcp-setup.md にあります。")
    commands = top.add_subparsers(dest="command", metavar="コマンド")

    def command(name, function, summary):
        sub = commands.add_parser(name, help=summary, description=summary)
        sub.add_argument("--env", help=f"設定のファイル（省くと、このファイルの横の {ENV_NAME}）")
        sub.set_defaults(function=function)
        return sub

    command("init", init, "トークンを 2 つ作って設定のファイルに書く").add_argument("url", help="外から届く URL（https:// から。最後に /mcp は付けない）")
    command("serve", serve, "設定のファイルを読んでサーバーを動かす")
    command("check", check, "サーバーを確かめる（電話はかからない）").add_argument("--local", action="store_true", help="外の URL ではなく、このパソコンの中のサーバーを確かめる")
    command("tokens", tokens, "どのトークンをどこに入れるかを、もう一度出す")
    command("rotate", rotate, "トークンを作り直す").add_argument("which", choices=("ai", "phone", "all"), help="作り直すトークン")
    command("url", change_url, "外から届く URL を変える").add_argument("url", help="新しい URL")
    command("install-systemd", install_systemd, "Linux：プログラム、設定のファイル、systemd のユニットを置く").add_argument("--root", default=os.sep, help=argparse.SUPPRESS)
    return top


def readable_output():
    """A pipe or a file gets UTF-8, whatever the code page; nothing it cannot show stops the program."""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding=None if stream.isatty() else "utf-8", errors="replace")


def main(argv):
    readable_output()
    top = parser()
    args = top.parse_args(argv)
    if args.command is None:
        top.print_help()
        return 0
    try:
        return args.function(args)
    except Problem as e:
        print(f"だめでした：{e}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
