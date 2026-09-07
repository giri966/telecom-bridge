#!/usr/bin/env bash
# Produces transaction_flow.pcap using the gateway's own built-in recorder, which needs
# no Wireshark, no Npcap and no administrator rights.
#
#   ./scripts/capture-transaction-flow.sh
#
# The file contains the CER/CEA handshake, ten successful CCR/CCA transactions, the three
# failure paths, and a few DWR/DWA watchdog exchanges, with the REST call that drove each one.
# Open it in Wireshark and apply the display filter:  diameter || http
#
# The payload bytes are real. The Ethernet/IP/TCP headers around them are reconstructed; see
# PcapWriter and the README for why, and for how to take a genuine tcpdump capture instead.
set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

PORT="${CAPTURE_GATEWAY_PORT:-8082}"
DIAMETER_PORT="${CAPTURE_DIAMETER_PORT:-3868}"
OUT="${1:-transaction_flow.pcap}"
START_SIMULATOR="${START_SIMULATOR:-yes}"

if [ ! -f gateway/target/gateway.jar ]; then
  echo "Build first: mvn clean package" >&2
  exit 1
fi

cleanup() {
  for port in "$PORT" $([ "$START_SIMULATOR" = "yes" ] && echo "$DIAMETER_PORT"); do
    for pid in $(netstat -ano 2>/dev/null | grep ":$port " | grep -i listen | awk '{print $NF}' | sort -u); do
      taskkill //PID "$pid" //F > /dev/null 2>&1 || kill "$pid" 2>/dev/null
    done
  done
}
trap cleanup EXIT

rm -f "$OUT"

if [ "$START_SIMULATOR" = "yes" ]; then
  echo "Starting the Diameter simulator on $DIAMETER_PORT"
  java -jar diameter-simulator/target/diameter-simulator.jar --port="$DIAMETER_PORT" > /tmp/capture-sim.log 2>&1 &
fi

echo "Starting the gateway on $PORT with capture to $OUT"
java -jar gateway/target/gateway.jar \
     --server.port="$PORT" \
     --diameter.port="$DIAMETER_PORT" \
     --diameter.watchdog-interval=6s \
     --capture.file="$OUT" > /tmp/capture-gw.log 2>&1 &

until curl -s "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -q '"state":"OPEN"'; do
  sleep 1
done
echo "Peer is OPEN; sending the sample transactions"

bash scripts/send-sample-requests.sh "http://localhost:$PORT" | tail -5

echo "Idling so the device-watchdog exchange is captured too"
sleep 16

cleanup
sleep 1
echo
echo "Wrote $OUT ($(stat -c %s "$OUT" 2>/dev/null || echo '?') bytes)"
echo "Open it in Wireshark with the display filter:  diameter || http"
