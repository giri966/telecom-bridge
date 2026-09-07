#!/usr/bin/env bash
# Linux/macOS equivalent of capture-pcap.ps1: captures loopback HTTP + Diameter into transaction_flow.pcap.
# Usage: sudo ./scripts/capture-pcap.sh [seconds] [output]
set -euo pipefail
SECONDS_TO_RUN="${1:-60}"
OUTPUT="${2:-transaction_flow.pcap}"
IFACE="lo"
[[ "$(uname)" == "Darwin" ]] && IFACE="lo0"

echo "Capturing on $IFACE (tcp port 8080 or 3868) for ${SECONDS_TO_RUN}s -> $OUTPUT"
tcpdump -i "$IFACE" -w "$OUTPUT" -G "$SECONDS_TO_RUN" -W 1 'tcp port 8080 or tcp port 3868'

if command -v tshark >/dev/null; then
  echo; echo "Captured Diameter/HTTP frames:"
  tshark -r "$OUTPUT" -Y 'diameter || http' -T fields -e frame.number -e ip.src -e tcp.srcport -e ip.dst -e tcp.dstport -e _ws.col.Protocol -e _ws.col.Info
fi
