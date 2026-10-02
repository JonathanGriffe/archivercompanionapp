#!/usr/bin/env bash
# Prove that the desktop's rustls completes a handshake with the certificate the
# companion app mints.
#
# The app raises a socket through its own `CompanionServer.serverSocketFactory`,
# and `pincheck` connects to it having replicated `phone.rs::pinned` — the same
# ureq major version, the same single pinned root, the same `Fixed` resolver.
#
# Nothing else in the test suite can catch a certificate rustls will not accept:
# it parses, it names the right host and it verifies under its own key whether or
# not the handshake would succeed.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

echo "building the pinning harness..."
( cd "$here/pincheck" && cargo build --quiet )
binary="$here/pincheck/target/debug/pincheck"

echo "running the handshake against it..."
cd "$root"
./gradlew :app:testDebugUnitTest \
    --tests '*RustlsPinningTest*' \
    -Dpincheck="$binary" \
    "$@"
