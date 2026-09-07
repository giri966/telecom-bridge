# Telecom-Bridge Gateway

A REST-to-Diameter gateway for online charging. A client POSTs a JSON charge request, the
gateway turns it into a Diameter **Credit-Control-Request** (Ro/Gy, RFC 4006) over a
persistent TCP connection, and returns the **Credit-Control-Answer** as JSON.

The request path never blocks. The HTTP thread hands the request to Netty and is released
immediately; the answer is matched back to its request by **Hop-by-Hop identifier** and
completes a `CompletableFuture` that finishes the HTTP response.

The repository also contains a standalone Diameter server simulator and a load-test runner.

```
                HTTP/JSON                          Diameter over TCP (port 3868)
 ┌──────────┐  POST /api/v1/charge  ┌─────────────────────────────┐   CER/CEA, DWR/DWA   ┌───────────────────┐
 │  client  │ ────────────────────▶ │   gateway (Spring WebFlux)  │ ◀──────────────────▶ │ diameter-simulator│
 │          │ ◀──────────────────── │  ChargeController           │   CCR ────────────▶  │  (Netty server)   │
 │          │  200 / 422 / 503 /504 │  ChargingService            │   ◀──────────── CCA  │  50–100 ms delay  │
 └──────────┘                       │  DiameterClient (Netty)     │                      └───────────────────┘
                                    │   └─ hop-by-hop → future    │
                                    └─────────────────────────────┘
```

## Contents

1. [Quick start](#quick-start)
2. [Project layout](#project-layout)
3. [REST API](#rest-api)
4. [Diameter implementation](#diameter-implementation)
5. [Concurrency and resource management](#concurrency-and-resource-management)
6. [Error handling](#error-handling)
7. [Tests](#tests)
8. [Load test results](#load-test-results)
9. [Packet capture](#packet-capture)
10. [Simulator](#simulator)
11. [Docker](#docker)

## Quick start

Prerequisites: JDK 17 or newer, Maven 3.9+.

```bash
# Build and run all 83 tests
mvn clean verify

# Terminal A: the Diameter server simulator on port 3868
java -jar diameter-simulator/target/diameter-simulator.jar

# Terminal B: the gateway on port 8080
java -jar gateway/target/gateway.jar

# Terminal C: one charge
curl -s -H 'Content-Type: application/json' \
     -d '{"msisdn":"919876543210","requestType":"INITIAL","requestedOctets":1048576}' \
     http://localhost:8080/api/v1/charge
```

```json
{"sessionId":"gateway.telecom-bridge.local;1788632113;1","resultCode":2001,
 "resultText":"DIAMETER_SUCCESS","success":true,"grantedOctets":1048576,
 "validitySeconds":3600,"hopByHopId":2397129608,"latencyMs":69}
```

The gateway performs the capabilities exchange at startup, before serving any traffic:

```
Netty started on port 8080 (http)
Starting Diameter client gateway.telecom-bridge.local -> localhost:3868 (timeout 2000 ms, watchdog 30 s)
TCP connected to localhost/127.0.0.1:3868; sending CER hbh=0x8ee14b87
Peer ocs.telecom-bridge.local OPEN (CEA Result-Code 2001, product DiameterSimulator)
```

`scripts/send-sample-requests.sh` (or the `.ps1` equivalent) sends ten successful charges
followed by every failure path.

Configuration lives in `gateway/src/main/resources/application.yml` under `diameter.*`,
and can be overridden with `DIAMETER_HOST`, `DIAMETER_PORT`, `DIAMETER_REQUEST_TIMEOUT`,
`DIAMETER_WATCHDOG_INTERVAL`, `GATEWAY_PORT` and `GATEWAY_LOG_LEVEL`.

## Project layout

| Module | Purpose |
|---|---|
| `diameter-codec` | RFC 6733 message model, AVP encoding with padding rules, Netty codecs, pcap writer |
| `diameter-simulator` | Standalone Netty Diameter server on port 3868. Fat jar. |
| `gateway` | Spring Boot 3.5 WebFlux service and the async Diameter client |
| `load-test` | Fixed-rate load generator with HdrHistogram percentiles |

Java 17 language level, built and tested on Temurin 21.

## REST API

### `POST /api/v1/charge`

| Field | Type | Required | Maps to |
|---|---|---|---|
| `msisdn` | string, 5–15 digits | yes | `Subscription-Id { Subscription-Id-Type = END_USER_E164, Subscription-Id-Data }` |
| `requestType` | `INITIAL` \| `UPDATE` \| `TERMINATION` \| `EVENT` | no, default `INITIAL` | `CC-Request-Type` |
| `requestNumber` | integer ≥ 0 | no, default 0 | `CC-Request-Number` |
| `sessionId` | string | no | `Session-Id`, generated when absent |
| `requestedOctets` | integer ≥ 0 | no | `Requested-Service-Unit { CC-Total-Octets }` |
| `serviceContextId` | string | no | `Service-Context-Id`, default `32251@3gpp.org` |

Response fields: `sessionId`, `resultCode`, `resultText`, `success`, `grantedOctets`,
`validitySeconds`, `hopByHopId` and `latencyMs`. The Hop-by-Hop identifier is returned so a
response can be matched to a frame in a packet capture.

| Situation | Status |
|---|---|
| CCA with Result-Code 2xxx | **200** |
| CCA with a 3xxx/4xxx/5xxx Result-Code, for example 4012 credit limit or 5030 unknown user | **422**, with the code in the body |
| Payload fails validation | **400** |
| No open Diameter connection, or the in-flight limit is reached | **503** with `Retry-After` |
| CCR sent but no CCA within the timeout | **504** |

A Diameter rejection returns 422 rather than 200 so a client cannot mistake "credit limit
reached" for a successful grant, and rather than 5xx because the exchange itself succeeded.

## Diameter implementation

```
 startup   gateway ── CER (257, app 0, Auth-Application-Id 4) ──▶ simulator
           gateway ◀── CEA (Result-Code 2001) ─────────────────── simulator     state = OPEN

 idle      gateway ── DWR (280) ──▶ simulator      every watchdog interval, 30 s
           gateway ◀── DWA (2001) ─ simulator      two silent intervals close the link

 charge    gateway ── CCR (272, app 4, hbh=N) ──▶ simulator
           gateway ◀── CCA (272, hbh=N, 2001) ── simulator

 shutdown  gateway ── DPR (282) ──▶ simulator, then DPA and a clean TCP close
```

**Header.** 20 bytes: version, message length, command flags (`R` request, `P` proxiable,
`E` error, `T` retransmit), command code, Application-Id, Hop-by-Hop and End-to-End
identifiers. Command codes 257 CE, 280 DW, 282 DP, 272 CC; Application-Id 0 for base
messages and 4 for Credit-Control. An answer copies the command code, application and both
identifiers from its request, clears `R`, and keeps `P`.

**AVP padding.** Each AVP is `code(4) flags(1) length(3) [vendor-id(4)] data padding`. The
Length field counts header and data but **not** the padding; on the wire every AVP is
padded with zero bytes to a multiple of 4, and the message length in the header counts the
padded size. For example `Product-Name` with a 13-byte value has Length 21 and occupies 24
bytes.

**AVPs used**, all IETF standard, all carrying the M bit except Product-Name:

| AVP | Code | Type |
|---|---|---|
| Session-Id | 263 | UTF8String, first AVP in the message |
| Origin-Host / Origin-Realm | 264 / 296 | DiameterIdentity |
| Destination-Realm | 283 | DiameterIdentity |
| Host-IP-Address | 257 | Address |
| Vendor-Id / Product-Name | 266 / 269 | Unsigned32 / UTF8String |
| Auth-Application-Id | 258 | Unsigned32 = 4 |
| Result-Code | 268 | Unsigned32 |
| Service-Context-Id | 461 | UTF8String |
| CC-Request-Type / CC-Request-Number | 416 / 415 | Enumerated / Unsigned32 |
| Subscription-Id | 443 | Grouped { Subscription-Id-Type 450, Subscription-Id-Data 444 } |
| Requested-Service-Unit / Granted-Service-Unit | 437 / 431 | Grouped { CC-Total-Octets 421 } |
| Validity-Time | 448 | Unsigned32 |
| Failed-AVP | 279 | Grouped |

Session-Id follows RFC 6733 section 8.8: `<Origin-Host>;<boot-seconds>;<counter>`.
Hop-by-Hop identifiers start from a random value plus a counter, so a restarted gateway
cannot reuse an identifier a slow peer might still answer.

**Framing.** `DiameterFrameDecoder` is a length-field decoder reading the 24-bit message
length, so it splits the TCP stream into whole messages whether the kernel delivers half a
message or three at once. Malformed input closes the connection, as the RFC requires.

The codec is written directly on Netty rather than using jDiameter. Four message types and
seventeen AVPs are a few hundred lines, and Netty supplies the non-blocking socket, frame
splitting and single-threaded-per-channel model.

## Concurrency and resource management

One request, end to end:

1. Reactor Netty decodes the JSON and calls `ChargeController`.
2. `ChargingService` builds the CCR and calls `DiameterClient.send`, which returns a
   `CompletableFuture` immediately. `Mono.fromFuture` bridges it into WebFlux, releasing
   the HTTP thread.
3. `send` picks a Hop-by-Hop identifier, arms a timeout, stores the future in
   `PendingRequests` (a `ConcurrentHashMap`), then writes the message. Registration happens
   **before** the write, so an answer can never arrive for an identifier not yet in the map.
4. When the CCA arrives, the Diameter event loop removes the entry, cancels the timeout and
   completes the future, which resumes the Reactor chain and writes the HTTP response.

Every map entry leaves by exactly one of three routes: the answer arrives, the timeout
fires, or the connection drops and all outstanding futures fail together. That invariant is
what keeps memory flat under sustained load. A late answer arriving after its timeout is
counted and discarded.

| Threads | Count | Role |
|---|---|---|
| Reactor Netty HTTP event loops | CPU count | HTTP parsing, JSON, response writing |
| `diameter-io` event loop | 2 | all socket I/O, codec, timeouts, watchdog, state changes |
| — | none | no thread ever waits for a Diameter answer |

Peer state (`onConnected`, `onMessage`, `onIdle`, `onDisconnected`) is only touched on the
channel's own event loop, so it needs no locks. `send` runs on HTTP threads and touches
only volatile fields, the concurrent map, and Netty's thread-safe write path.

`diameter.max-pending-requests` caps in-flight requests; above it the API fails fast with
503 rather than queueing without bound. Sockets are closed on decode error, capabilities
failure, watchdog miss, peer disconnect and shutdown. `DiameterClient` implements
`SmartLifecycle` so it stops before the web server, sends a DPR, waits briefly for the DPA,
fails any leftover futures and shuts its event loop down gracefully.

Health is exposed at `/actuator/health` with a `diameter` component reporting peer state
and pending count. Metrics include `diameter.request` (timer), `diameter.pending.requests`,
`diameter.peer.open`, `diameter.request.timeouts` and `diameter.answers.unmatched`.

## Error handling

| Failure | Client sees |
|---|---|
| Simulator not running at startup | 503 until the peer is up; reconnect every 2 s |
| Simulator stops mid-flight | in-flight requests 503, automatic reconnect |
| Simulator silent for one request | 504 with the Hop-by-Hop identifier |
| Simulator rejects (5030, 4012, 5005) | 422 with the Result-Code |
| Peer sends malformed bytes | connection closed and reopened |
| Peer misses two watchdogs | connection closed and reopened |
| Invalid JSON or fields | 400 with the offending field |

The simulator has three MSISDN suffixes that trigger these deliberately: `...0000` answers
5030, `...9999` answers 4012, and `...5555` never answers, which exercises the 504 path.

## Tests

`mvn clean verify` runs 83 tests in about a minute.

| Class | Covers |
|---|---|
| `AvpTest` (14) | AVP header layout, M/V flags, padding, Unsigned32/64, Enumerated, Address, Grouped, decoder rejection of bad lengths |
| `DiameterCodecTest` (12) | 20-byte header, message length including padding, CCR round trip, decoding a hand-assembled CEA, answer semantics, decoder strictness |
| `DiameterPipelineTest` (4) | Netty framing of coalesced and fragmented TCP reads |
| `SimulatorHandlerTest` (11) | CER/CEA, unknown application, request before CER, DWR, delayed CCA, the three test hooks, 5005 with Failed-AVP, DPR |
| `CcrMapperTest` (5) | JSON to CCR AVP by AVP, defaults, UPDATE mapping, CCA to response |
| `ChargeControllerTest` (6) | HTTP contract: 200, 422, 503, 504, 400 |
| `DiameterClientTest` (7) | Real sockets: handshake, **300 concurrent CCRs correlated correctly**, timeout, peer down then reconnect, in-flight cap, DPR on stop |
| `GatewayEndToEndTest` (6) | Full Spring context and simulator: 200, 422, 504, 400, health, metrics |
| `PcapWriterTest` (8) | Capture file parses back as valid libpcap with correct checksums and sequencing |
| `PacerTest` (6) | Load generator pacing under a stalled process |
| `LoadTestRunnerTest` (4) | Generated subscriber numbers, option parsing |

## Load test results

100 TPS for 500 000 transactions. Full report in
[`docs/load-test-full-500k-50-100ms.txt`](docs/load-test-full-500k-50-100ms.txt), with the
run log in [`docs/load-test-full-500k-run.log`](docs/load-test-full-500k-run.log).

```
Elapsed         : 5005.1 s              Achieved rate : 100.0 req/s
Completed       : 500000 / 500000       HTTP 200: 500000,  5xx: 0,  transport err: 0
Latency (ms)    : p50=84.4  p90=102.8  p95=108.3  p99=116.8  p99.9=118.8  max=186.6
  peer round trip (the simulator's own delay)
                : p50=82.0  p90=100.0  p95=106.0  p99=114.0  mean=83.2 ms
  gateway overhead on top of that (HTTP, JSON, sockets)
                : p50=2.2   p90=2.8    p95=3.1    p99=4.2    mean=2.2 ms
Gateway heap    : first=29.0 MB  last=27.7 MB  min=23.2 MB  max=67.6 MB  (166 samples)
  pending now = 0     unmatched answers = 0     timeouts = 0
```

Half a million transactions with no errors, no timeouts and no unmatched answers, and the
pending-request table back at zero. The heap ends at 27.7 MB having started at 29.0 MB,
which answers the memory-stability requirement.

**On the p95 target.** The brief asks for an end-to-end p95 under 100 ms while also
specifying a simulator that delays every answer by 50–100 ms. A uniform delay over that
range has a p95 of 97.5 ms on its own, before any HTTP is parsed, so the end-to-end figure
cannot come in under 100 ms against that peer. The report therefore measures the two parts
separately for every request: the gateway's own contribution is **3.1 ms at p95**. Against
a peer with headroom (`--max-delay=80`) the same build reports an end-to-end p95 of 95.5 ms
and passes; that run is in
[`docs/load-test-headroom-50-80ms.txt`](docs/load-test-headroom-50-80ms.txt).

To reproduce:

```bash
./scripts/run-load-tests.sh full     # 100 TPS x 500 000, about 83 minutes
./scripts/run-load-tests.sh          # two shorter runs, about 7 minutes
```

## Packet capture

[`transaction_flow.pcap`](transaction_flow.pcap) contains 162 frames with no checksum
errors: the CER/CEA handshake, 13 CCR with 12 CCA, three DWR/DWA watchdog exchanges, and
the REST call that drove each transaction. A decoded listing is in
[`docs/transaction_flow-summary.txt`](docs/transaction_flow-summary.txt). Open it in
Wireshark with the display filter `diameter || http`.

Each transaction appears as four frames:

```
  25   0.502  127.0.0.1:10292 -> 127.0.0.1:8082  HTTP      POST /api/v1/charge HTTP/1.1
  26   0.592  127.0.0.1:10289 -> 127.0.0.1:3868  DIAMETER  CCR  app=4 hbh=0x728797a2 Type=INITIAL_REQUEST MSISDN=919876543201
  27   0.679  127.0.0.1:3868 -> 127.0.0.1:10289  DIAMETER  CCA  app=4 hbh=0x728797a2 Result-Code=2001 DIAMETER_SUCCESS
  35   0.743  127.0.0.1:10292 -> 127.0.0.1:8082  HTTP      HTTP/1.1 200 OK
```

**How it was recorded.** Capturing loopback traffic on Windows requires Npcap or `pktmon`,
both of which need administrator rights that were not available on the build machine. The
gateway can therefore record its own traffic: pass `--capture.file=...` and a tap sits
first in both Netty pipelines. The payload bytes and timestamps are real, exactly what the
sockets carried; the Ethernet, IP and TCP headers around them are generated, with
consistent sequence numbers and checksums so Wireshark reassembles the streams and its
dissectors work normally. It is an accurate record of the protocol exchange rather than a
`tcpdump` of the wire.

```bash
./scripts/capture-transaction-flow.sh          # regenerate the capture
sudo ./scripts/capture-pcap.sh 60              # or take a real tcpdump capture instead
```

## Simulator

```bash
java -jar diameter-simulator/target/diameter-simulator.jar \
     --port=3868 --min-delay=50 --max-delay=100
```

Every option also reads an environment variable (`SIM_PORT`, `SIM_MIN_DELAY_MS`, and so on),
and `SIM_LOG_LEVEL=DEBUG` logs every message.

Per connection it requires a CER first, rejecting a peer that does not advertise
Auth-Application-Id 4 with Result-Code 5010 and closing anything that sends traffic before
the handshake. It answers DWR with DWA and DPR with DPA. A CCR missing a mandatory AVP gets
5005 with Failed-AVP; otherwise the CCA is scheduled after a random delay in the configured
range, granting the requested octets with a Validity-Time. Unknown commands get 3001 with
the E bit. A stats line every 10 seconds reports throughput and how accurately the host is
honouring the configured delay.

## Docker

```bash
docker compose up --build
# gateway on http://localhost:8080, simulator on 3868
```

Both images are multi-stage, building with Maven and running on a Temurin 21 JRE.
