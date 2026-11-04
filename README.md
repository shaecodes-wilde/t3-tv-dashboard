# T3 Dashboard for Android TV

A native, remote-controlled dashboard and voice-reply client for T3 Code environments. Built for the Bell FibeTV box (Android 14) and its directional remote.

- Environment cards for multiple local or SSH-connected T3 environments.
- Needs attention, Working, and All tasks views; OK opens the conversation and latest response.
- Voice or keyboard replies with editable drafts, explicit Send, and queued or in-flight delivery.
- Structured question answers and explicit one-time permission decisions.
- Live completion, pending-question/approval, and failure alerts.
- Optional nine-second pop-ups over TV playback, without taking remote focus.
- Pause alerts, send a test alert, or stop monitoring from Settings.
- A Python bridge reads dashboard status in read-only SQLite transactions. Conversations and replies use T3 protocol 2 with dedicated `orchestration:read` / `orchestration:operate` client credentials. Remote environment traffic uses a configured SSH connection. The app never writes the T3 database or accesses provider credentials.

## Architecture and requirements

The TV connects to a Python HTTPS bridge using a private TLS certificate and bearer token. Dashboard status comes from read-only SQLite transactions. Conversations and replies use a small Node.js adapter with scoped T3 credentials. For remote environments, SSH runs the adapter on that host, where its credential remains.

Requirements: Linux x86-64 for the build scripts, Python 3.10+, OpenSSL, curl, tar, unzip, zip, SSH, and Node.js 22+ on each environment host. T3 Code must be running with its v2 database projections and protocol 2 API available. The app targets Android 14 with minimum Android 8.0; runtime validation was on Android 14.

This is a self-hosted development app. Live typed replies were verified against two environments. Actual microphone transcription with the physical Fibe remote still needs confirmation; support depends on the device's speech service.

The APK contains no connection credentials. Installation sends configuration directly into app-private storage. Generated credentials, TLS keys, signing keys, runtime databases, logs, device captures, and build outputs are excluded from Git. Keep `.private/` private, including action receipts that may contain submitted text.

The bridge host must remain awake and connected. Reserve its LAN address to keep the certificate valid for that address. The generated certificate lasts 825 days and must then be renewed and reinstalled on the TV. Keep the bridge on a trusted LAN; do not expose it directly to the public internet.

## Use with the Fibe remote

Open **T3 Dashboard** from the TV's apps. Use the arrow keys and OK to select an environment, filter, or task. Inside a task, choose **Reply / Speak**. Choose **Speak**, say your message into the remote, review or edit the transcript, then select **Send**. **Keyboard / edit** also supports the TV keyboard’s dictation. The physical remote mic button normally belongs to Google Assistant; use the on-screen Speak button if it opens Assistant. Back preserves an unsent draft. Back from the dashboard returns to TV while monitoring continues.

For visible alerts over other apps, choose **Settings → Enable pop-ups over TV** and allow **Display over other apps** for T3 Dashboard. Use **Send a test alert** to check it. Some apps can suppress overlays; normal Android notifications are also posted, though TV launchers may not display them like phones do.

**Pause task alerts** keeps the dashboard live and silently advances the alert cursor. **Stop monitoring and close** stops the background service and disables its boot restart until the app is opened again.

## Development

No Gradle or third-party Android dependencies are needed. The bridge’s T3 API adapter requires Node.js 22+ on each environment host (native fetch and WebSocket). The app uses native Android views and a foreground monitoring service. Linux build tools are downloaded to `.tools/`, under this workspace (not RAM-backed `/tmp`).

```bash
bash scripts/bootstrap-tools.sh
bash scripts/build.sh
python3 -m unittest discover -s tests -v
node --test --test-concurrency=2 tests/test_t3_api.mjs
```

## Configure and install

From the repository root, replace `YOUR_LAN_IP` with the bridge computer's LAN IP:

```bash
python3 scripts/configure.py --host YOUR_LAN_IP
```

This generates `.private/bridge.json`, `.private/connection.json`, and the TLS certificate. It refuses to overwrite existing credentials. The script starts with one local environment and a Fedora SSH example. Edit the `environments` list to match your hosts, removing the SSH entry if unused. Each entry needs a display `label`, an absolute `userdata` path, and an optional `ssh` host alias. The SSH account must have read access to the live database and its WAL files. Host keys must already be trusted; the bridge does not disable verification. Environment IDs are discovered by the dashboard poller.

Conversations and replies also require an `api` object on each environment. Replace these placeholders with absolute paths **on that environment's host**:

```json
{
  "label": "workstation",
  "userdata": "/home/USER/.t3/userdata",
  "api": {
    "node": "/absolute/path/to/node",
    "helper": "/absolute/path/to/t3-tv-dashboard/bridge/t3_api.mjs",
    "session": "/absolute/path/to/t3-tv-dashboard/.private/t3-local-session.json"
  }
}
```

For the local environment, issue a scoped session from the repository root. Use the CLI for that running T3 environment; replace `t3` with its absolute path if needed:

```bash
umask 077
t3 auth session issue \
  --scope orchestration:read --scope orchestration:operate \
  --ttl 90d --label 'T3 TV dashboard' --json \
  > .private/t3-local-session.json
```

For a remote environment, copy `bridge/t3_api.mjs` to a private application directory on that host and issue its scoped session there. Set its `api.helper`, `api.node`, and `api.session` to those remote paths. Do not reuse another environment's session token. The remote host also needs Python 3 and trusted, noninteractive SSH access.

Start the bridge:

```bash
python3 bridge/server.py --config .private/bridge.json
```

Allow only the TV's address to reach TCP port 8743 through the host firewall. Enable debugging on the TV and authorize your computer. Wireless debugging may require `adb pair` before connecting; use the pairing and connection ports shown on the device.

```bash
.tools/platform-tools/adb connect TV_IP:ADB_PORT
bash scripts/install.sh TV_IP:ADB_PORT
```

The installer installs the APK, copies `.private/connection.json` into private storage, and launches **T3 Dashboard** (`com.wilde.t3tv`). Allow notification permission if prompted.

For automatic bridge startup, create a user systemd unit named `t3-tv-bridge.service`, with an absolute `ExecStart` pointing to the bridge command above, then enable it at login. If the project moves, update the service and absolute configuration paths. The TV monitor restarts after a normal boot once the app has been opened; force-stopping it prevents restarts until it is opened again.

This is a locally signed **debuggable development APK**, so the ADB installer can configure private storage without embedding secrets. Keep debugging paired only with trusted computers. For wider distribution, add a pairing UI, use a release signing key, and disable debugging.

## Operations

If you created the optional user service:

```bash
systemctl --user status t3-tv-bridge
journalctl --user -u t3-tv-bridge -n 50 --no-pager
systemctl --user restart t3-tv-bridge
```

An offline environment is shown explicitly. The bridge polls every five seconds after each read; network delays can increase the actual interval. The app polls the bridge every five seconds after each response and marks a feed older than 45 seconds stale. No completion is inferred from a disconnected host.

Old completed tasks appear in the dashboard on first connection, but do not trigger alerts. The bridge persists event history in `.private/events.sqlite`; the TV persists its event cursor. New run IDs and distinct pending request IDs can trigger fresh alerts even if the displayed status remains the same. Alert bursts are combined into one notification. Retention is the latest 201 events; this dashboard is not an audit log.

The database adapter targets the installed T3 v2 projection schema. T3 upgrades that change these tables may require updating `bridge/reader.py`. Settled, archived, deleted, snoozed, and subagent threads are omitted. Active descendants keep a parent in Working until they finish. Task conversations use T3’s bounded snapshot and history APIs. Voice recognition is handled by Android’s system speech service; the dashboard does not record or retain audio. The system service may use online recognition.

## Remove

Uninstall **T3 Dashboard** on the TV, or use `adb uninstall com.wilde.t3tv`. Stop and disable the bridge with `systemctl --user disable --now t3-tv-bridge`. Remove its user unit and the narrowly scoped UFW rule if no longer wanted. Preserve `.private/debug.keystore` if you intend to install compatible updates later.

## Android references

- [Wireless ADB setup](https://developer.android.com/tools/adb)
- [Android 14 foreground service requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

## Replies and credential renewal (v0.2)

**Send / continue** lets T3 start or update the task according to its current state. **Queue follow-up** runs after the active turn. **Update current task** sends an in-flight instruction and is offered when the task is active. Structured questions appear under **Requests** and are answered against their specific request IDs. Permission requests require an explicit decision and confirmation; session-wide and permanent grants stay in the desktop T3 client.

Drafts are stored privately on the TV per environment, thread, and request. Before sending, the exact payload and a stable request ID are saved. After a timeout, **Retry delivery** uses that same ID. The bridge persists prepared commands and receipts in `.private/actions.sqlite`; T3’s command IDs prevent duplicate execution if the connection drops after acceptance. A submitted draft is locked until delivery is confirmed or explicitly discarded. Discarding never cancels a message already accepted by T3.

The scoped T3 credentials expire after 90 days. Renew them without printing tokens:

```bash
python3 scripts/renew-t3-sessions.py
```

The renewal script expects the local CLI at `~/.t3/bin/t3` and remote `t3` on the SSH account's PATH. Adjust these paths if your installation differs. Each environment must have an `api` configuration. The script replaces session files without printing tokens and attempts to revoke the previous sessions.

Deploy changes to `bridge/t3_api.mjs` to every remote host as well as the bridge host, then restart the bridge.

If removing the app, also revoke the **T3 TV dashboard** client sessions in each T3 environment.
