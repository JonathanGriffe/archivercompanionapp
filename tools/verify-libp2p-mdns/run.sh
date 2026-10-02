#!/usr/bin/env bash
# Prove that the companion app finds a desktop through the mDNS libp2p actually
# speaks, rather than through a reading of its source.
#
# `p2pnode` is a libp2p node with mDNS on, configured as the desktop's is and
# built against the same libp2p-mdns. `Libp2pMdnsTest` starts it and looks it up
# with `P2pMdns.ask`, the code that ships, over this machine's own LAN interface.
#
# Needs a LAN interface that is up and takes multicast; the test skips without one.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

echo "building the libp2p node..."
( cd "$here/p2pnode" && cargo build --quiet )
binary="$here/p2pnode/target/debug/p2pnode"

echo "looking it up..."
cd "$root"
./gradlew :app:testDebugUnitTest \
    --tests '*Libp2pMdnsTest*' \
    -Dp2pnode="$binary" \
    "$@"
