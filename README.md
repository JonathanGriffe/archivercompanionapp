# Archiver Companion

The phone half of [archiverclient](../archiverclient). It indexes everything on
the phone that is a photograph or a video, keeps that index current in the
background, pairs with a desktop node by QR code, announces itself on the local
network, and serves its camera roll over the HTTP interface the desktop already
expects.

Nothing is uploaded anywhere. The desktop connects to the phone and pulls.

## How it fits together

```
  phone                                          desktop (ac-node)
  ───────────────────────────────────────        ──────────────────────────
  MediaStore ──▶ index (Room) ──▶ MD5

  HTTPS server  ◀── GET /v1/items ───────────────  scan, every 16 h
   (our cert)   ◀── GET /v1/items/{id}/bytes ────  fetch

  heartbeat     ─── POST /v1/hello ─────────────▶  "this phone is here"
  pairing       ─── POST /v1/pair ──────────────▶  QR read off the screen
  discovery     ─── _p2p._udp.local? ───────────▶  libp2p mDNS: where it went
```

Both directions are TLS with a certificate pinned by fingerprint. The desktop
pins ours at pairing; we pin its fingerprint out of the QR code. There is no
certificate authority anywhere and no trust-on-first-use prompt: the one thing
that crossed a channel nobody on the wifi can listen to is the code on the
screen, so that is what everything else is anchored to.

### The two things that are easy to get wrong

**The desktop re-reads the whole listing on every scan** and retires anything
absent from it. `/v1/items` is therefore never a delta, and an item is never left
out merely because nobody has hashed it yet.

**A `size` or `md5` the desktop cannot match makes it discard the download**, and
it only retries three times, an hour apart. So both are published only from a
read that actually observed those bytes, and an unhashed item offers neither.
Being silent costs an unverified copy; being wrong costs the file.

### Staying reachable

The desktop never asks whether the phone is there — it only listens. It also
scans each source about every sixteen hours, so a computer that has collected
from this phone more recently than that already has what it is going to ask for.
That is what the heartbeat is built around: announce only when there is something
to announce.

| Situation | What happens |
|---|---|
| Nothing paired | Silent. Only pairing changes it. |
| Not on wifi | Silent. Only wifi arriving changes it. |
| A computer collected less than 16 h ago | Silent until that cycle is up. |
| On wifi, and nothing has collected for 16 h | Announce every 15 minutes until one does. |
| Freshly paired, never collected from | Announce — there is no deadline to wait out. |
| Our address changed since a computer last heard from us | Tell that computer, even when otherwise silent, at every recompute until it answers — it fetches from where it last heard us. |
| A computer turns the phone away (`403`) | Keep announcing. Believed after 3 refusals in a row spanning an hour, then silent towards it until paired again. |

Staleness is measured against the *least* recently served computer, so a laptop
collecting daily does not keep the phone quiet for a desktop that has not.

Announcements above that floor are events, not ticks: wifi arriving, our address
changing, a computer asking for a file, new media, a fresh pairing. Each wakes the loop to recompute;
whether anything is sent is the policy's decision. The watchdog wakes it every
quarter hour as well, because the loop's own timeout runs on a clock that stops
while the phone sleeps.

**How soon a computer finds the phone.** The desktop counts a phone present for
five minutes after a hello (`listener.rs::FRESH`), and we announce every fifteen,
so the phone looks present a third of the time. That is enough: a desktop whose
scan is due and finds the phone absent looks again five minutes later
(`import_link.rs::AWAY_RETRY`), and probes that close together can hardly miss a
five-minute window. Over every phase between the two clocks, the desktop finds an
announcing phone within twenty minutes about 99% of the time, and within about
half an hour always. `HeartbeatPolicyTest` works it out.

**Being turned away.** A desktop answers `403` to a token it does not know — the
phone source removed, or paired again — but also, briefly, when it cannot read
its own database. One refusal is therefore not believed. Three in a row,
spanning at least an hour, are: the computer is marked as no longer accepting
this phone and is not announced to again until it is paired afresh.

See `net/HeartbeatPolicy.kt`, which is pure and carries the reasoning.

### Finding a computer that has moved

A hello goes to wherever the computer last answered. When that fails, the phone
looks for it, cheapest first, and only then:

1. **libp2p's mDNS.** The desktop runs `libp2p-mdns`, which is not DNS-SD: asked
   for `_p2p._udp.local` it answers with TXT records like
   `dnsaddr=/ip4/192.168.1.20/tcp/4001/p2p/12D3KooW…` and no SRV or A records,
   so no general-purpose resolver ever reaches an address. `net/P2pMdns.kt`
   reads the TXT strings itself and keeps the addresses carrying the peer id the
   pairing code gave as `i=`. One question, three seconds, multicast lock held
   for exactly that long.
2. **A sweep**, only when mDNS named nowhere — it is off on that computer, or
   unheard: a connect to every address in the phone's own /24 on the port the
   desktop keeps for phones. At most once an hour per port, so a computer that is
   simply switched off is not hunted for all night.

Neither is trusted. Each yields candidates, and the pinned hello decides; the
token is written only after the handshake has proved the certificate.

libp2p sends its answers to the multicast group on 5353, never back to the
asker's own port, so the lookup has to bind 5353. That port is shared — Android's
own mDNS, Play services and other apps may hold it too — which works because all
of them set `SO_REUSEADDR`. If something has taken it exclusively, the bind fails,
the lookup hears nothing, and the sweep covers it.

## Building

Needs JDK 17+ and an Android SDK with platform 36.

```sh
./gradlew assembleDebug          # build
./gradlew test                   # 95 unit tests, no device needed; two need
                                 # the harnesses below and skip themselves
./gradlew lintDebug
./gradlew installDebug           # onto a connected phone
```

### Verifying against the desktop's rustls

One property cannot be checked from the Android side: whether the desktop's
rustls will complete a handshake with the certificate we mint. A certificate that
parses, names the right host and verifies under its own key can still be refused
outright — marking it `CA:true`, the intuitive thing for something used as a
trust anchor, earns `invalid peer certificate: Other(CaUsedAsEndEntity)` and
nothing here would say so.

```sh
./tools/verify-rustls-pinning/run.sh     # needs cargo
```

It raises a socket through the app's own `CompanionServer.serverSocketFactory`
and connects with a Rust client replicating `phone.rs::pinned`.

### Verifying against libp2p's mDNS

The same blind spot, the other way round: a parser can agree with a reading of
`libp2p-mdns` and still not with libp2p. This starts a libp2p node configured as
the desktop's is, on the same `libp2p-mdns`, and finds it with `P2pMdns.ask` over
this machine's LAN interface.

```sh
./tools/verify-libp2p-mdns/run.sh        # needs cargo, and a LAN interface
```

## End-to-end, against a real desktop

1. `cd ../archiverclient && cargo run -p ac-desktop`, on the same wifi.
2. Sources tab → add a phone source → **Sign in again** shows a QR.
3. Scan it in the app. The desktop logs `paired a phone`.
4. With `RUST_LOG=debug`, watch for `a phone says it is here` within a minute.
5. `cargo run -p ac-node -- import source scan <dir>` → non-zero `owed`, then a
   fetch pulls real photographs with matching digests.
6. Take a photo; it appears in the next scan. Delete one; it is retired.

Background behaviour is worth checking separately, because it fails silently:

- Toggle wifi with the app force-stopped (`adb shell am force-stop …`) and
  confirm a hello follows — that is the worker path.
- Confirm the phone falls silent once a computer has collected from it, and
  starts announcing again a scan cycle later.
- Leave it overnight and confirm the service is still up in the morning
  (`adb shell dumpsys activity services …`).

## Notes on the desktop side

The interface implemented here is the desktop's phone source,
`crates/ac-import/src/sources/phone.rs` and `phone/listener.rs`, as committed on
its `main`. Constants mirrored from it are named after their originals
(`listener.rs::FRESH`, `import_link.rs::AWAY_RETRY`, `ledger.rs::SCAN_INTERVAL`).
The two tools above check the TLS and mDNS halves against the desktop's own
crate versions; the end-to-end run is still the only thing that proves the whole
of it agrees.

## Layout

```
net/     identity and certificates, the HTTPS server, pairing,
         the heartbeat and its policy, finding a moved computer
media/   the MediaStore walk, the extension filter, MD5
data/    Room: the index and the paired computers
work/    WorkManager: indexing, hashing, the watchdog, the network wake
service/ the foreground service and what keeps it alive
ui/      pairing, status, permissions
```
