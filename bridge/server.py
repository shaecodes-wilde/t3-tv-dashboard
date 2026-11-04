"""Authenticated HTTPS dashboard feed. Standard library only."""
import argparse
import hmac
import json
import logging
import shlex
import sqlite3
import ssl
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from reader import read_environment
from actions import Actions

ALERT_STATUSES = {"waiting", "completed", "failed"}


class Tracker:
    """Persist baselines and event IDs so reconnects/restarts do not repeat alerts."""
    def __init__(self, path):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS baseline (environment TEXT PRIMARY KEY);
            CREATE TABLE IF NOT EXISTS seen (environment TEXT, thread TEXT, fingerprint TEXT,
                PRIMARY KEY(environment,thread));
            CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT,
                created REAL NOT NULL, payload TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT);
        """)
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO metadata VALUES ('streamId',?)", (str(uuid.uuid4()),))
        self.stream_id = self.db.execute("SELECT value FROM metadata WHERE key='streamId'").fetchone()[0]

    def update(self, environment):
        env = environment["id"]
        first = not self.db.execute("SELECT 1 FROM baseline WHERE environment=?", (env,)).fetchone()
        with self.db:
            for task in environment["threads"]:
                prior = self.db.execute("SELECT fingerprint FROM seen WHERE environment=? AND thread=?", (env, task["id"])).fetchone()
                changed = prior is None or prior[0] != task["eventKey"]
                if changed and not first and task["status"] in ALERT_STATUSES:
                    event = {"environment": environment["label"], "environmentId": env,
                             "threadId": task["id"], "title": task["title"], "status": task["status"]}
                    self.db.execute("INSERT INTO events(created,payload) VALUES (?,?)", (time.time(), json.dumps(event)))
                self.db.execute("INSERT OR REPLACE INTO seen VALUES (?,?,?)", (env, task["id"], task["eventKey"]))
            self.db.execute("INSERT OR IGNORE INTO baseline VALUES (?)", (env,))
            self.db.execute("DELETE FROM events WHERE id < (SELECT COALESCE(MAX(id),0)-200 FROM events)")

    def events(self):
        return [dict(json.loads(row[1]), id=row[0]) for row in self.db.execute("SELECT id,payload FROM events ORDER BY id")]


class Feed:
    def __init__(self, config, state):
        self.config = config
        self.tracker = Tracker(state)
        self.lock = threading.Lock()
        self.snapshot = {"environments": [], "events": [], "generatedAt": 0}

    def poll(self):
        environments = []
        for source in self.config["environments"]:
            try:
                if source.get("ssh"):
                    remote = "python3 - " + shlex.quote(source["userdata"])
                    result = subprocess.run(["ssh", "-T", "-oBatchMode=yes", "-oConnectTimeout=5", "--", source["ssh"], remote],
                                            input=Path(__file__).with_name("reader.py").read_text(), text=True,
                                            capture_output=True, timeout=12, check=True)
                    env = json.loads(result.stdout)
                else:
                    env = read_environment(source["userdata"])
                source["id"] = env["id"]
                env.update(label=source["label"], online=True, checkedAt=int(time.time()))
                self.tracker.update(env)
            except Exception:
                logging.exception("Cannot read environment %s", source["label"])
                env = {"id": source.get("id", source["label"]), "label": source["label"],
                       "online": False, "threads": [], "error": "Connection unavailable. Check the bridge log."}
            environments.append(env)
        with self.lock:
            self.snapshot = {"environments": environments, "events": self.tracker.events(), "streamId": self.tracker.stream_id, "generatedAt": int(time.time())}

    def run(self):
        while True:
            self.poll()
            time.sleep(5)


def make_handler(feed, token, actions=None):
    class Handler(BaseHTTPRequestHandler):
        def authorized(self):
            return hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token)

        def json_response(self, status, value):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass  # The persisted action receipt remains available to a retry.

        def do_POST(self):
            if not self.authorized():
                self.json_response(401, {"error": "Dashboard authorization failed."})
                return
            if self.path not in {"/v1/thread", "/v1/history", "/v1/action"} or actions is None:
                self.json_response(404, {"error": "Unknown endpoint."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 131072:
                    raise ValueError("Request is empty or too large.")
                self.connection.settimeout(90)
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("Invalid request.")
                result = actions.mutate(body) if self.path == "/v1/action" else actions.read(self.path.rsplit('/',1)[1], body)
                self.json_response(200, result)
            except (ValueError, UnicodeError) as error:
                self.json_response(400, {"error": str(error)})
            except Exception:
                logging.exception("Dashboard request failed")
                self.json_response(503, {"error": "Connection unavailable. Your draft is saved; retry to check delivery."})

        def do_GET(self):
            if not self.authorized():
                self.send_error(401)
                return
            if self.path != "/v1/dashboard":
                self.send_error(404)
                return
            with feed.lock:
                body = json.dumps(feed.snapshot).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass  # Never log authorization headers or task titles.
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    path = Path(args.config).resolve()
    config = json.loads(path.read_text())
    feed = Feed(config, path.with_name("events.sqlite"))
    actions = Actions(config, path.with_name("actions.sqlite"))
    server = ThreadingHTTPServer((config.get("host", "127.0.0.1"), config.get("port", 8743)), make_handler(feed, config["token"], actions))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(config["certificate"], config["privateKey"])
    server.socket = context.wrap_socket(server.socket, server_side=True)
    threading.Thread(target=feed.run, daemon=True).start()
    logging.warning("T3 TV dashboard bridge listening on https://%s:%s", *server.server_address)
    server.serve_forever()


if __name__ == "__main__":
    main()
