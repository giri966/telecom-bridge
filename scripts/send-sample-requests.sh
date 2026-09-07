#!/usr/bin/env bash
# Sends the traffic the PCAP deliverable must show: ten successful charges followed by
# the three failure paths (peer rejects, peer silent, bad payload).
# Usage: ./scripts/send-sample-requests.sh [http://localhost:8080]
set -u
BASE_URL="${1:-http://localhost:8080}"
ENDPOINT="$BASE_URL/api/v1/charge"

charge() {
  local msisdn="$1" label="$2"
  local out
  out=$(curl -s -w ' HTTP %{http_code}' -H 'Content-Type: application/json' \
        -d "{\"msisdn\":\"$msisdn\",\"requestType\":\"INITIAL\",\"requestNumber\":0,\"requestedOctets\":1048576}" \
        "$ENDPOINT")
  printf '%-28s %s\n' "$label" "$out"
}

echo "Health: $(curl -s "$BASE_URL/actuator/health")"
echo

for i in $(seq -w 1 10); do
  charge "9198765432$i" "CCR #$i (9198765432$i)"
done

echo
charge 919876540000 "user unknown -> 422/5030"
charge 919876549999 "credit limit -> 422/4012"
charge 919876545555 "blackhole   -> 504"

echo
printf '%-28s %s\n' "validation -> 400" "$(curl -s -w ' HTTP %{http_code}' -H 'Content-Type: application/json' -d '{"msisdn":"abc"}' "$ENDPOINT")"
