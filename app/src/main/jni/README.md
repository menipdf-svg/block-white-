# Native tunnel engine

`hev-socks5-tunnel/` is a vendored copy of
[heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
(MIT, with lwIP, libyaml and hev-task-system — each keeps its own LICENSE).

Upstream commit: `780b396b0fc3d0355fc0614e221e3446933669c4` (2026-10-18).
Removed from the copy: Windows/Apple/Docker files and the bundled Java sample.

## Local changes (search for `WhitelistFilter` in the source)

So the filter can tell which app opened each connection, every session sends
its original addresses in the SOCKS5 username:

```
<username from config>|<t or u>|<src ip>|<src port>|<dst ip>|<dst port>
```

- `src/hev-socks5-session.h` — `char user[192]` in `HevSocks5SessionData`,
  declaration of `hev_socks5_session_set_origin()`.
- `src/hev-socks5-session.c` — `hev_socks5_session_set_origin()`, and
  `hev_socks5_session_run()` uses the per-session username.
- `src/hev-socks5-session-tcp.c`, `src/hev-socks5-session-udp.c` — call
  `hev_socks5_session_set_origin()` at construct time.

The destination is the address the app actually connected to (the mapped
"fake" IP when mapdns is used), which is what
`ConnectivityManager.getConnectionOwnerUid()` needs. The configured username is
a random secret, so other apps can't use the local SOCKS5 server.
