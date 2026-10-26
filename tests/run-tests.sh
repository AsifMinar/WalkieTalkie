#!/usr/bin/env bash
# Security and protocol tests for the encryption, packet parser and trust rules.
# Runs on any desktop JDK 11+ (the crypto API is the same one Android uses). No phone needed.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
javac -nowarn -d "$OUT" src/com/walkietalkie/wifi/Wire.java src/com/walkietalkie/wifi/Crypto.java \
  src/com/walkietalkie/wifi/PeerTable.java src/com/walkietalkie/wifi/HoldQueue.java \
  tests/SecTest.java tests/TrustTest.java tests/NetSim.java tests/HoldTest.java
for t in SecTest TrustTest NetSim HoldTest; do echo "== $t"; java -cp "$OUT" com.walkietalkie.wifi.$t; done
