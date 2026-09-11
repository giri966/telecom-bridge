#!/usr/bin/env bash
# Takes both packet captures with dumpcap, the capture engine that ships with Wireshark.
#
#   ./scripts/capture-wireshark.sh
#
# Produces:
#   transaction_flow.pcap    the CER/CEA handshake, the sample transactions and DWR/DWA
#   docs/load-window.pcap    60 seconds of sustained 100 TPS
#
# Requirements: Wireshark installed, including Npcap with "Support loopback traffic
# capture" ticked on Windows. No administrator rights are needed at capture time provided
# Npcap was not installed with its "restrict to Administrators" option.
#
# The gateway runs on 8088 deliberately: that port is in Wireshark's default HTTP port
# list, so the REST side dissects as HTTP with no "Decode As" step for whoever opens the
# file. Diameter is recognised on 3868 automatically.
set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

GW_PORT="${CAPTURE_GATEWAY_PORT:-8088}"
DIA_PORT="${CAPTURE_DIAMETER_PORT:-3868}"
FILTER="tcp port $GW_PORT or tcp port $DIA_PORT"

WIRESHARK_DIR="${WIRESHARK_DIR:-/c/Program Files/Wireshark}"
DUMPCAP="$WIRESHARK_DIR/dumpcap.exe"
EDITCAP="$WIRESHARK_DIR/editcap.exe"
IFACE="${CAPTURE_IFACE:-\\Device\\NPF_Loopback}"
[ -x "$DUMPCAP" ] || { DUMPCAP=$(command -v dumpcap); EDITCAP=$(command -v editcap); IFACE="${CAPTURE_IFACE:-lo}"; }

if [ -z "${DUMPCAP:-}" ] || [ ! -x "$DUMPCAP" ]; then
  echo "dumpcap not found. Install Wireshark, or set WIRESHARK_DIR." >&2
  exit 1
fi
if [ ! -f gateway/target/gateway.jar ]; then
  echo "Build first: mvn clean package" >&2
  exit 1
fi

TMP="${TMPDIR:-/tmp}"

kill_services() {
  for port in "$GW_PORT" "$DIA_PORT"; do
    for pid in $(netstat -ano 2>/dev/null | grep ":$port " | grep -i listen | awk '{print $NF}' | sort -u); do
      taskkill //PID "$pid" //F > /dev/null 2>&1 || kill "$pid" 2>/dev/null
    done
  done
  sleep 1
}
trap kill_services EXIT

start_services() {
  java -jar diameter-simulator/target/diameter-simulator.jar --port="$DIA_PORT" > "$TMP/cap-sim.log" 2>&1 &
  java -jar gateway/target/gateway.jar \
       --server.port="$GW_PORT" --diameter.port="$DIA_PORT" --diameter.watchdog-interval=6s \
       > "$TMP/cap-gw.log" 2>&1 &
  until curl -s "http://localhost:$GW_PORT/actuator/health" 2>/dev/null | grep -q '"state":"OPEN"'; do
    sleep 1
  done
}

echo "=== capture 1 of 2: transaction_flow.pcap ==="
kill_services
# The capture starts first so the CER/CEA handshake falls inside the window.
"$DUMPCAP" -i "$IFACE" -f "$FILTER" -a duration:55 -w "$TMP/cap1.pcapng" -q &
DUMPCAP_PID=$!
sleep 3
start_services
echo "peer is OPEN, sending the sample transactions"
bash scripts/send-sample-requests.sh "http://localhost:$GW_PORT" | tail -4
echo "idling so the device-watchdog exchanges are captured"
wait $DUMPCAP_PID 2>/dev/null
"$EDITCAP" -F pcap "$TMP/cap1.pcapng" transaction_flow.pcap
echo "wrote transaction_flow.pcap"

echo
echo "=== capture 2 of 2: docs/load-window.pcap ==="
"$DUMPCAP" -i "$IFACE" -f "$FILTER" -a duration:65 -w "$TMP/cap2.pcapng" -q &
DUMPCAP_PID=$!
sleep 2
echo "driving 100 TPS for 60 seconds"
java -jar load-test/target/load-test.jar \
     --url="http://localhost:$GW_PORT/api/v1/charge" \
     --tps=100 --total=6000 --warmup=100 --progress-seconds=30 \
     --report="$TMP/cap2-report.txt" | grep -E "Completed|Latency|req/s"
wait $DUMPCAP_PID 2>/dev/null
"$EDITCAP" -F pcap "$TMP/cap2.pcapng" docs/load-window.pcap
echo "wrote docs/load-window.pcap"

echo
echo "Open either file in Wireshark with the display filter:  diameter || http"
