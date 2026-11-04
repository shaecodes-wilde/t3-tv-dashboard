"""Versioned, read-only adapter for T3 Code's v2 SQLite projections.

Also runs over SSH: python3 - /path/to/userdata < reader.py
No message bodies, secrets, or database writes.
"""
import json
import sqlite3
import sys
from datetime import datetime, timezone
from pathlib import Path

ACTIVE = {"preparing", "queued", "starting", "running", "waiting"}


def read_environment(userdata):
    root = Path(userdata).expanduser()
    connection = sqlite3.connect((root / "statev2.sqlite").as_uri() + "?mode=ro", uri=True, timeout=2)
    connection.row_factory = sqlite3.Row
    try:
        connection.execute("PRAGMA query_only=ON")
        connection.execute("BEGIN")
        rows = connection.execute("""
            SELECT t.thread_id, t.title, t.updated_at, t.payload_json,
                   p.title AS project, r.run_id, r.status, r.completed_at
            FROM orchestration_v2_projection_threads t
            LEFT JOIN projection_projects p ON p.project_id=t.project_id
            LEFT JOIN orchestration_v2_projection_runs r ON r.run_id=(
                SELECT run_id FROM orchestration_v2_projection_runs
                WHERE thread_id=t.thread_id
                ORDER BY CASE WHEN status IN ('preparing','starting','running','waiting') THEN 0 ELSE 1 END, ordinal DESC LIMIT 1)
            WHERE t.deleted_at IS NULL AND t.archived_at IS NULL
            ORDER BY t.updated_at DESC
        """).fetchall()
        # A parent's turn may finish while delegated work remains active.
        busy_parents = set()
        parents = {}
        for row in rows:
            payload = json.loads(row["payload_json"])
            lineage = payload.get("lineage") or {}
            if lineage.get("relationshipToParent") == "subagent":
                parents[row["thread_id"]] = lineage.get("parentThreadId")
            if row["status"] in ACTIVE and lineage.get("relationshipToParent") == "subagent":
                busy_parents.add(lineage.get("parentThreadId"))
        for parent in list(busy_parents):
            visited = set()
            while parent in parents and parent not in visited:
                visited.add(parent)
                parent = parents[parent]
                busy_parents.add(parent)
        threads = []
        now = datetime.now(timezone.utc).isoformat()
        for row in rows:
            payload = json.loads(row["payload_json"])
            if payload.get("settledAt") or payload.get("settledOverride") == "settled":
                continue
            if (payload.get("lineage") or {}).get("relationshipToParent") == "subagent":
                continue
            snoozed = payload.get("snoozedUntil")
            if snoozed and snoozed > now:
                continue
            pending = connection.execute("""
                SELECT runtime_request_id, kind FROM orchestration_v2_projection_runtime_requests
                WHERE thread_id=? AND status='pending' ORDER BY runtime_request_id
            """, (row["thread_id"],)).fetchall()
            status = row["status"] or "idle"
            if pending:
                status = "waiting"
            elif row["thread_id"] in busy_parents or status == "waiting":
                status = "running"
            event_key = ":".join([row["run_id"] or "idle", status] + [p["runtime_request_id"] for p in pending])
            threads.append({
                "id": row["thread_id"], "title": row["title"],
                "project": row["project"] or "No project", "status": status,
                "runId": row["run_id"], "updatedAt": row["updated_at"],
                "completedAt": row["completed_at"], "eventKey": event_key,
                "pending": len(pending),
                "model": (payload.get("modelSelection") or {}).get("model", ""),
            })
        return {"id": (root / "environment-id").read_text().strip(), "threads": threads}
    finally:
        connection.close()


if __name__ == "__main__":
    print(json.dumps(read_environment(sys.argv[1])))
