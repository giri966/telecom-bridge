#!/usr/bin/env bash
# Starts the simulator and the gateway, runs the load test against them, and writes a
# report into docs/. Run it twice, once per case, or pass your own arguments.
#
#   ./scripts/run-load-tests.sh                    # both cases, short runs (~7 min total)
#   ./scripts/run-load-tests.sh full               # the full 100 TPS x 500 000 run (~83 min)
#
# Case A uses the delay the brief mandates (50-100 ms) and is expected to report FAIL on
# p95: a uniform [50, 100] delay has a p95 of 97.5 ms on its own, so the end-to-end p95
# cannot come in under 100 ms. Case B gives the peer headroom (50-80 ms) and passes.
# Read the "gateway + client overhead" row in either report for the part the gateway owns.
set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

GATEWAY_PORT="${GATEWAY_PORT:-8080}"
MODE="${1:-short}"
mkdir -p docs

stop_services() {
  for port in "$GATEWAY_PORT" 3868; do
    if command -v netstat > /dev/null; then
      for pid in $(netstat -ano 2>/dev/null | grep ":$port " | grep -i listen | awk '{print $NF}' | sort -u); do
        taskkill //PID "$pid" //F > /dev/null 2>&1 || kill "$pid" 2>/dev/null
      done
    fi
  done
  sleep 1
}

run_case() {
  local label="$1" min_delay="$2" max_delay="$3" total="$4"
  local report="docs/load-test-$label.txt"

  stop_services
  echo "=== $label: simulator delay ${min_delay}-${max_delay} ms, $total requests at 100 TPS ==="
  java -jar diameter-simulator/target/diameter-simulator.jar \
       --min-delay="$min_delay" --max-delay="$max_delay" > "/tmp/sim-$label.log" 2>&1 &
  GATEWAY_PORT="$GATEWAY_PORT" java -jar gateway/target/gateway.jar > "/tmp/gw-$label.log" 2>&1 &

  curl -s --retry 60 --retry-delay 1 --retry-connrefused --retry-all-errors \
       -o /dev/null "http://localhost:$GATEWAY_PORT/actuator/health"

  java -jar load-test/target/load-test.jar \
       --url="http://localhost:$GATEWAY_PORT/api/v1/charge" \
       --tps=100 --total="$total" --warmup=500 --progress-seconds=30 --report="$report"

  echo "--- simulator delay accuracy on this host ---"
  grep stats "/tmp/sim-$label.log" | tail -1
  echo "report written to $report"
}

if [ ! -f gateway/target/gateway.jar ]; then
  echo "Build first: mvn clean package" >&2
  exit 1
fi

if [ "$MODE" = "full" ]; then
  run_case "full-500k-50-100ms" 50 100 500000
else
  run_case "mandated-50-100ms" 50 100 30000
  run_case "headroom-50-80ms"  50 80  12000
fi

stop_services
echo "Done. Reports are in docs/."
