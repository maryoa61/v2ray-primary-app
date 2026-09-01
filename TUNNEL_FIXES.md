# Tunnel Stability & Performance Fixes (2026-08)

Review of the V2Ray/Xray + hev-socks5-tunnel Android integration. All items
below are code-level root causes for the reported symptoms
("tunnel drops sometimes / slow speeds / connects but nothing loads").

## 1. Sniffing was disabled → `.ir` direct routing never matched (MAJOR)
**File:** `service/XrayConfigGenerator.kt`
hev-socks5-tunnel hands Xray raw destination **IP** literals (it never sees
hostnames). With `sniffing.enabled = false` on the socks/http inbounds, Xray
could not recover the TLS SNI / HTTP Host / QUIC SNI, so the
`regexp:\.ir$ → direct` rule **could never match** — every Iranian site was
hauled through the overseas proxy (extreme slowness / timeouts), and every
foreign site still worked, which masked the bug.
- Enabled sniffing with `destOverride: ["http","tls","quic"]` on both inbounds
  (quic needed for HTTP/3 over UDP/443).
- `domainStrategy` changed from `IPIfNonMatch` to `AsIs` — the former forced
  an extra edge-side DNS resolution for every connection (extra RTT); `AsIs`
  lets direct traffic resolve locally and proxied traffic resolve remotely.

## 2. DNS took a round-trip through the tunnel for every query (MAJOR)
**File:** `service/XrayConfigGenerator.kt`, `service/V2RayVpnService.kt`
- Added a split DNS config: `.ir` domains → Shecan domestic resolvers
  (`178.22.122.100` / `185.51.200.2`), everything else → `1.1.1.1`.
- Added a `dns-out` outbound and a routing rule sending all port-53 traffic
  from the inbounds to it, so apps' DNS queries are answered inside Xray.
- VPN builder now advertises Shecan + 1.1.1.1 as DNS servers; the Shecan
  resolver IPs are also pinned in the **direct** IP rule so they are never
  proxied.

## 3. Android 14+ crash on disconnect (`startForeground` contract)
**File:** `service/V2RayVpnService.kt`
`stopVpnInternal()` sends `ACTION_STOP` via `startForegroundService()`
(required when the service may already be stopped), but the STOP path never
called `startForeground()` → system kills the process with
`RemoteServiceException` ("did not then call startForeground") within ~5s.
- `onStartCommand()` now promotes to foreground for BOTH actions via a single
  `enterForegroundNotification()` helper.
- Also guards against a duplicate `ACTION_START` while a session is active
  (double-tap connect previously spawned two xray processes racing on the
  TUN fd / port 10808).

## 4. hev-socks5-tunnel native tuning
**File:** `service/HevSocks5Tunnel.kt`
- `task-stack-size` 20480 → **86016** (upstream documented default). The old
  value risked native stack overflow → tunnel thread abort ("tunnel died
  randomly").
- Added `connect-timeout: 5000` (fail-fast on dead SOCKS sockets during core
  restarts), `tcp-read-write-timeout: 300000`, `udp-read-write-timeout: 60000`,
  `limit-nofile: 65535` (avoid EMFILE under heavy connection churn),
  `log-file/log-level`. Key names verified against upstream README.
- `writeConfig()` now takes the MTU and clamps it to 1280–1500.

## 5. MTU mismatch between TUN and tunnel config
**Files:** `service/V2RayVpnService.kt`, `service/HevSocks5Tunnel.kt`
The user-configured MTU (settings, default 1400) was ignored — the TUN used
the constant while... now both the `Builder.setMtu()` and hev's `tunnel.mtu`
use the same value read from `RuntimeSettings`. A mismatch silently drops
large packets (stalled downloads that look like "slow internet").

## 6. IPv6 leak / happy-eyeballs stalls
**File:** `service/V2RayVpnService.kt`
Only IPv4 routes were installed. On dual-stack networks apps either leaked
IPv6 traffic past the tunnel or stalled on AAAA timeouts. Added
`addAddress("fdfe:dcba:9876::1", 126)` + `addRoute("::", 0)`. hev relays v6
over the same SOCKS loopback.

## 7. `switchServer()` could hang forever
**File:** `service/VpnCoreManager.kt`
It waited on `vpnState.filter{ DISCONNECTED|ERROR }.first()` with no timeout.
If the service process was killed (low memory/OEM saver) before processing
`ACTION_STOP`, this suspended forever **while holding `lifecycleMutex`**,
freezing all later connect/switch actions. Bounded to 15s, after which the
new session starts anyway (the old process is already dead).

## 8. Subscription sync duplicated every server on every refresh
**File:** `data/V2RayRepository.kt`, `data/Daos.kt`
`insertServers` uses `OnConflictStrategy.REPLACE` keyed on the auto-generated
row id; freshly parsed links have id=0, so each sync inserted brand-new rows.
The list grew unboundedly, and the background pinger hit every duplicate
every cycle (battery/data). Now upserts on a natural key
(type+address+port+uuid+network+sni+flow+pbk+sid); added
`getAllServersOnce()` for the one-shot dedup snapshot.

## 9. VMess links with string `"port"` parsed as port 0
**File:** `data/V2RayRepository.kt`
Some exporters emit `"port":"443"` (string). `JSONObject.optInt` returns the
default for a String value → the client tried port 0 → immediate connect
failure. Parsing now coerces both string and number forms and clamps to
1–65535. Also reads `scy` (cipher) and `alpn` and string-form `aid`.

## 10. Auto-connect background loop reworked
**File:** `service/AutoConnectService.kt`
- Pings now run concurrently (bounded semaphore) instead of serially with up
  to 1.2 s each — with many servers a round took tens of seconds.
- Interval 20 s → 30 s; pinging skipped entirely while disconnected.
- Switching requires the current node to be unreachable for 2 consecutive
  rounds AND a healthy candidate ≥80 ms faster, preventing restart storms
  from transient packet loss (each restart itself looks like a "drop").

## 11. Missing Room migration 1→2
**File:** `data/V2RayDatabase.kt`
The migration chain started at 2→3; users on schema v1 crashed on update with
Room's "Migration didn't properly handle" exception. Added `MIGRATION_1_2`.

## Tests added
- `app/src/test/java/com/example/XrayConfigGeneratorTest.kt` — validates config
  structure, sniffing enabled, split DNS + dns-out, `AsIs` strategy, geoip
  gating, hev MTU propagation/clamping/stack-size.
- `app/src/test/java/com/example/ShareLinkParseTest.kt` — Robolectric tests for
  VMess string/numeric port, VLESS REALITY keys, invalid links.
