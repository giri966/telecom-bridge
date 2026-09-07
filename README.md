# Telecom-Bridge Gateway

A REST-to-Diameter gateway for online charging. A client POSTs a JSON charge request,
the gateway turns it into a Diameter **Credit-Control-Request** (Ro/Gy, RFC 4006) on a
persistent TCP connection, and the **Credit-Control-Answer** comes back as JSON. Nothing
blocks: the HTTP thread hands the request to Netty and is free immediately; the answer
is matched to the request by its **Hop-by-Hop identifier** and completes a
`CompletableFuture` that finishes the HTTP response.

The repository also contains the Diameter **server simulator** (plays the Online
Charging System on port 3868) and a **load-test runner** that drives 100 TPS and reports
HdrHistogram percentiles plus the gateway's live heap.

```
                HTTP/JSON                          Diameter over TCP (port 3868)
 ┌──────────┐  POST /api/v1/charge  ┌─────────────────────────────┐   CER/CEA, DWR/DWA   ┌───────────────────┐
 │  client  │ ────────────────────▶ │   gateway (Spring WebFlux)  │ ◀──────────────────▶ │ diameter-simulator│
 │ curl /   │ ◀──────────────────── │  ChargeController           │   CCR ────────────▶  │  (Netty server)   │
 │ load-test│  200 / 422 / 503 /504 │  ChargingService            │   ◀──────────── CCA  │  50–100 ms delay  │
 └──────────┘                       │  DiameterClient (Netty)     │                      └───────────────────┘
                                    │   ├─ PendingRequests map    │
                                    │   │  hop-by-hop → future    │
                                    │   └─ diameter-codec         │
                                    └─────────────────────────────┘
```

## Deliverable status

| Required | State |
|---|---|
| Microservice source | Done. `gateway/`, builds and runs, 83 tests green. |
| Simulator source | Done. `diameter-simulator/`, standalone fat jar on port 3868. |
| README with setup | This file. |
| `transaction_flow.pcap` | Checked in at [`transaction_flow.pcap`](transaction_flow.pcap): 162 frames, CER/CEA, 13 CCR/12 CCA, DWR/DWA, each with the REST call that drove it. Recorded by the gateway itself because Wireshark and Npcap need administrator rights that were unavailable, so the payloads are real but the TCP/IP headers are reconstructed. Read [the section below](#capturing-transaction_flowpcap) before submitting it, and re-take it with Wireshark if a genuine wire capture is required. |
| Load-test report at 100 TPS × 500 000 | Done. [`docs/load-test-full-500k-50-100ms.txt`](docs/load-test-full-500k-50-100ms.txt): 500 000 requests in 83 minutes at a measured 100.0 req/s, zero errors, heap flat, p95 gateway overhead 3.1 ms. Two shorter runs are checked in beside it. See [Load test](#load-test). |

Read the [load-test section](#the-two-constraints-in-the-brief-contradict-each-other)
before judging the p95 number: the brief's mandated simulator delay makes its own p95
target unreachable, and the report separates the two so you can see which is which.

## Contents

1. [Modules](#modules)
2. [Quick start](#quick-start)
3. [REST API](#rest-api)
4. [Diameter implementation](#diameter-implementation)
5. [Asynchronous design and concurrency](#asynchronous-design-and-concurrency)
6. [Resource management](#resource-management)
7. [Error handling](#error-handling)
8. [Observability](#observability)
9. [Simulator](#simulator)
10. [Tests](#tests)
11. [Capturing transaction_flow.pcap](#capturing-transaction_flowpcap)
12. [Load test](#load-test)
13. [Docker](#docker)
14. [Design decisions](#design-decisions)
15. [Evaluation criteria map](#evaluation-criteria-map)
16. [Limitations and next steps](#limitations-and-next-steps)

---

## Modules

| Module | What it is | Key classes |
|---|---|---|
| `diameter-codec` | RFC 6733 message model, AVP encode/decode with padding rules, Netty frame/message codecs. Shared by client and server. | `DiameterMessage`, `Avp`, `DiameterCodec`, `netty/DiameterPipeline` |
| `diameter-simulator` | Standalone Netty server on 3868. Answers CER, DWR, DPR and CCR (after a 50–100 ms delay). Ships as a fat jar. | `DiameterSimulator`, `SimulatorHandler` |
| `gateway` | Spring Boot 3.5 WebFlux service with `POST /api/v1/charge`, the async Diameter client, health and metrics. Ships as a Boot jar. | `DiameterClient`, `PendingRequests`, `ChargingService`, `ChargeController`, `GatewayExceptionHandler` |
| `load-test` | Fixed-rate HTTP load generator with HdrHistogram percentiles and gateway heap sampling. Fat jar. | `LoadTestRunner` |

Build tool: Maven multi-module. Java 17 language level (built and tested on Temurin 21).

## Quick start

Prerequisites: JDK 17+, Maven 3.9+. Wireshark with Npcap for the PCAP. Docker is optional.

```bash
# 1. Build everything and run all 83 tests
mvn clean verify

# 2. Terminal A: the Diameter server simulator on port 3868
java -jar diameter-simulator/target/diameter-simulator.jar

# 3. Terminal B: the gateway on port 8080 (use GATEWAY_PORT=8081 if 8080 is taken)
java -jar gateway/target/gateway.jar

# 4. Terminal C: one charge
curl -s -H 'Content-Type: application/json' \
     -d '{"msisdn":"919876543210","requestType":"INITIAL","requestedOctets":1048576}' \
     http://localhost:8080/api/v1/charge
```

Expected reply:

```json
{"sessionId":"gateway.telecom-bridge.local;1788632113;1","resultCode":2001,"resultText":"DIAMETER_SUCCESS",
 "success":true,"grantedOctets":1048576,"validitySeconds":3600,"hopByHopId":2397129608,"latencyMs":69}
```

Gateway log when it starts (the CER/CEA handshake is visible before any traffic):

```
Netty started on port 8080 (http)
Starting Diameter client gateway.telecom-bridge.local -> localhost:3868 (timeout 2000 ms, watchdog 30 s, maxPending 10000)
TCP connected to localhost/127.0.0.1:3868; sending CER hbh=0x8ee14b87
Peer ocs.telecom-bridge.local OPEN (CEA Result-Code 2001, product DiameterSimulator)
```

`scripts/send-sample-requests.sh` (or `.ps1`) sends ten successful charges followed by
every failure path; it is the traffic to generate while capturing the PCAP.

All settings live in `gateway/src/main/resources/application.yml` under `diameter.*`
and can be overridden with environment variables (`DIAMETER_HOST`, `DIAMETER_PORT`,
`DIAMETER_REQUEST_TIMEOUT`, `DIAMETER_WATCHDOG_INTERVAL`, `GATEWAY_PORT`,
`GATEWAY_LOG_LEVEL=DEBUG` logs every Diameter message).

## REST API

### `POST /api/v1/charge`

Request body:

| Field | Type | Required | Maps to | Notes |
|---|---|---|---|---|
| `msisdn` | string, 5–15 digits | yes | `Subscription-Id { Subscription-Id-Type = END_USER_E164, Subscription-Id-Data }` | E.164 without `+` |
| `requestType` | `INITIAL` \| `UPDATE` \| `TERMINATION` \| `EVENT` | no (default `INITIAL`) | `CC-Request-Type` 1..4 | |
| `requestNumber` | integer ≥ 0 | no (default 0) | `CC-Request-Number` | increments within a session |
| `sessionId` | string | no | `Session-Id` | pass the value returned by INITIAL when sending UPDATE/TERMINATION; generated when absent |
| `requestedOctets` | integer ≥ 0 | no | `Requested-Service-Unit { CC-Total-Octets }` | omitted when absent |
| `serviceContextId` | string | no | `Service-Context-Id` | default `32251@3gpp.org` (3GPP PS charging) |

Response body (`ChargeResponse`), returned whenever the peer answered:

| Field | Meaning |
|---|---|
| `sessionId` | Session-Id used on the wire |
| `resultCode` / `resultText` | Diameter `Result-Code` and its symbolic name |
| `success` | true for 2xxx |
| `grantedOctets`, `validitySeconds` | from `Granted-Service-Unit` / `Validity-Time`, present on success |
| `hopByHopId` | unsigned Hop-by-Hop id of the CCR, so a response can be matched to a PCAP frame |
| `latencyMs` | CCR-to-CCA time measured inside the gateway |

HTTP status mapping:

| Situation | Status | Body |
|---|---|---|
| CCA with Result-Code 2xxx | **200** | `ChargeResponse`, `success=true` |
| CCA with Result-Code 3xxx/4xxx/5xxx (peer rejected, e.g. 4012 credit limit, 5030 unknown user) | **422** | `ChargeResponse`, `success=false` |
| Payload fails validation / malformed JSON | **400** | `{"error":"VALIDATION_FAILED","details":[...]}` |
| No OPEN Diameter connection (peer down, still connecting, CEA not yet received) or in-flight limit reached | **503** + `Retry-After: 2` | `{"error":"DIAMETER_PEER_UNAVAILABLE"}` |
| CCR sent, no CCA within `diameter.request-timeout` (2 s) | **504** | `{"error":"DIAMETER_TIMEOUT","hopByHopId":...}` |
| Connection dropped while the CCR was in flight | **503** | `{"error":"DIAMETER_PEER_UNAVAILABLE"}` |

Examples of each, captured from a real run:

```
HTTP 200  {"sessionId":"gateway.telecom-bridge.local;1788632113;2","resultCode":2001,"resultText":"DIAMETER_SUCCESS","success":true,"grantedOctets":1048576,"validitySeconds":3600,"hopByHopId":2397129609,"latencyMs":69}
HTTP 422  {"sessionId":"gateway.telecom-bridge.local;1788632113;11","resultCode":5030,"resultText":"DIAMETER_USER_UNKNOWN","success":false,"hopByHopId":2397129618,"latencyMs":72}
HTTP 422  {"sessionId":"gateway.telecom-bridge.local;1788632113;12","resultCode":4012,"resultText":"DIAMETER_CREDIT_LIMIT_REACHED","success":false,"hopByHopId":2397129619,"latencyMs":71}
HTTP 504  {"error":"DIAMETER_TIMEOUT","message":"No answer received for hop-by-hop 0x8ee14b94 within 2000 ms","hopByHopId":2397129620}
HTTP 400  {"error":"VALIDATION_FAILED","message":"Request body is invalid","details":["msisdn: msisdn must be 5 to 15 digits"]}
```

Why 422 rather than 200 for a Diameter failure: the exchange itself worked, so 5xx would
be wrong, but a client that only checks the status code must not mistake "credit limit
reached" for a successful grant. The Diameter code is always in the body either way.

## Diameter implementation

### Message flow

```
 startup   gateway ── CER (257, app 0, Auth-Application-Id 4) ──▶ simulator
           gateway ◀── CEA (Result-Code 2001) ─────────────────── simulator     state = OPEN

 idle      gateway ── DWR (280) ──▶ simulator          every diameter.watchdog-interval (30 s)
           gateway ◀── DWA (2001) ─ simulator          two silent intervals close the socket

 charge    gateway ── CCR (272, app 4, hbh=N) ──▶ simulator
           gateway ◀── CCA (272, hbh=N, 2001) ── simulator     50–100 ms later

 shutdown  gateway ── DPR (282, Disconnect-Cause REBOOTING) ──▶ simulator
           gateway ◀── DPA (2001) ────────────────────────────── simulator     then TCP close
```

### Header (20 bytes, `DiameterMessage`)

| Offset | Size | Field | Values used |
|---|---|---|---|
| 0 | 1 | Version | 1 |
| 1 | 3 | Message Length | header + all AVPs **including padding** |
| 4 | 1 | Command Flags | `R` 0x80 request, `P` 0x40 proxiable, `E` 0x20 error, `T` 0x10 retransmit |
| 5 | 3 | Command Code | 257 CE, 280 DW, 282 DP, 272 CC |
| 8 | 4 | Application-ID | 0 for base messages, 4 for Credit-Control |
| 12 | 4 | Hop-by-Hop Identifier | correlation key, unique per connection |
| 16 | 4 | End-to-End Identifier | unique for 4 min; upper 12 bits from the clock |

An answer copies command code, application id, Hop-by-Hop and End-to-End from the
request, clears R and keeps P (`DiameterMessage.createAnswer()`).

### AVPs (`Avp`, `AvpCode`)

Every AVP is `code(4) flags(1) length(3) [vendor-id(4)] data padding`. The **Length**
field counts header and data but not padding; on the wire the AVP is padded with zero
bytes to a multiple of 4. The Message Length in the header counts the padded size. This
is the rule that Wireshark checks first, and it is covered by `AvpTest`:

```
Product-Name "TelecomBridge" (13 bytes)          Result-Code 2001
00 00 01 0D  code 269                            00 00 01 0C  code 268
00           flags (not mandatory)               40           flags M
00 00 15     length 21 = 8 + 13                  00 00 0C     length 12
54 65 6C ... 65  data                            00 00 07 D1  2001
00 00 00     3 bytes padding, not in Length      (no padding, 12 is a multiple of 4)
```

AVPs used, all IETF standard (no Vendor-Id), all with the M bit except Product-Name:

| AVP | Code | Type | In |
|---|---|---|---|
| Session-Id | 263 | UTF8String | CCR (first AVP), CCA |
| Origin-Host / Origin-Realm | 264 / 296 | DiameterIdentity | every message |
| Destination-Realm | 283 | DiameterIdentity | CCR |
| Host-IP-Address | 257 | Address (2-byte family + IPv4) | CER, CEA |
| Vendor-Id | 266 | Unsigned32 = 0 | CER, CEA |
| Product-Name | 269 | UTF8String | CER, CEA |
| Auth-Application-Id | 258 | Unsigned32 = 4 | CER, CEA, CCR, CCA |
| Result-Code | 268 | Unsigned32 | every answer |
| Service-Context-Id | 461 | UTF8String | CCR |
| CC-Request-Type | 416 | Enumerated (1 INITIAL, 2 UPDATE, 3 TERMINATION, 4 EVENT) | CCR, CCA |
| CC-Request-Number | 415 | Unsigned32 | CCR, CCA |
| Subscription-Id | 443 | Grouped { Subscription-Id-Type 450 = 0 END_USER_E164, Subscription-Id-Data 444 } | CCR |
| Requested-Service-Unit | 437 | Grouped { CC-Total-Octets 421 Unsigned64 } | CCR |
| Granted-Service-Unit | 431 | Grouped { CC-Total-Octets 421 } | CCA |
| Validity-Time | 448 | Unsigned32 | CCA |
| Failed-AVP | 279 | Grouped | CCA 5005 |
| Disconnect-Cause | 273 | Enumerated | DPR |

Session-Id format is `<Origin-Host>;<boot-seconds>;<counter>` (RFC 6733 section 8.8).
Identifiers are minted by `IdentifierGenerator`: Hop-by-Hop is a random start plus a
counter so a restarted gateway never reuses ids that a slow peer might still answer.

### Framing

`DiameterFrameDecoder` is a `LengthFieldBasedFrameDecoder(offset 1, length 3,
adjustment -4)`: it reads the 24-bit Message Length and cuts the TCP stream into whole
messages, whether the kernel delivers half a message or three at once
(`DiameterPipelineTest` covers both). `DiameterMessageDecoder` then parses one frame;
any malformed byte raises `DiameterDecodeException`, which closes the connection, as
RFC 6733 requires for unparseable input.

### Peer state machine (`DiameterClient`, `PeerState`)

```
CLOSED ──connect()──▶ CONNECTING ──TCP up, CER sent──▶ WAIT_CEA ──CEA 2001 + app 4──▶ OPEN
   ▲                        │ connect refused              │ CEA bad / 5 s timeout        │ DWA missed / socket closed
   └── reconnect-delay ◀────┴──────────────────────────────┴──────────────────────────────┘
OPEN ──stop()──▶ CLOSING (DPR sent) ──DPA or 1 s──▶ CLOSED, no reconnect
```

Only in `OPEN` does `send()` accept traffic; otherwise the REST call gets 503 without
touching the network. A CEA that is not 2001 or does not advertise application 4 is
treated as a failed handshake.

## Asynchronous design and concurrency

The question the challenge asks is "how is the REST-to-Diameter mapping handled, and is
a `ConcurrentHashMap` tracking Hop-by-Hop ids?" Yes, and here is the full path of one
request:

1. Reactor Netty's HTTP event loop decodes the JSON and calls `ChargeController`.
2. `ChargingService` builds the CCR (no identifiers yet) and calls
   `DiameterClient.send(builder)`, which returns a `CompletableFuture` immediately.
   `Mono.fromFuture` bridges it into the WebFlux chain, so the HTTP thread is released.
3. Inside `send()`: pick a fresh Hop-by-Hop id, arm a timeout on the Diameter event loop,
   `putIfAbsent(hop, Pending{future, timeoutTask, startNanos})` in `PendingRequests`
   (a `ConcurrentHashMap`), then `channel.writeAndFlush(message)`. Registration happens
   **before** the write so an answer can never arrive for an id that is not yet in the map.
4. The simulator answers 50–100 ms later. On the Diameter event loop `onAnswer()` does
   `pending.remove(hop)`, cancels the timeout, records the timer, and completes the future.
5. Completing the future resumes the Reactor chain: `CcrMapper.toResponse` builds the
   JSON and Reactor Netty writes the HTTP response.

Every map entry leaves by exactly one of three routes: the answer arrives, the timeout
fires (`DiameterTimeoutException` → 504), or the connection drops (`failAll` →
`DiameterUnavailableException` → 503). A late answer that arrives after its timeout is
counted in `diameter.answers.unmatched` and dropped. That invariant is what keeps the
map, and therefore heap, flat over half a million requests.

Thread model:

| Threads | Count | Do |
|---|---|---|
| Reactor Netty HTTP event loops | CPU count | HTTP parsing, JSON, response writing |
| `diameter-io` Netty event loop | 2 (`diameter.io-threads`) | all socket I/O, codec, timeouts, watchdog, state changes |
| none | | no thread ever waits on a Diameter answer |

Everything that touches peer state (`onConnected`, `onMessage`, `onIdle`,
`onDisconnected`) runs on the single event loop of the channel, so those methods need no
locks. `send()` runs on HTTP threads and only reads volatile fields, uses the concurrent
map and Netty's thread-safe write path.

Backpressure: `diameter.max-pending-requests` (10 000) caps in-flight CCRs. Above it,
`send()` fails fast with 503 rather than queueing without bound. At 100 TPS and 100 ms
the steady state is about 10 entries.

## Resource management

- **One TCP connection**, opened at startup and kept alive with DWR/DWA. Reconnect is
  scheduled on the event loop after `reconnect-delay`, never in a busy loop.
- **Bounded pools**: two Diameter I/O threads, Reactor Netty defaults for HTTP; the
  simulator uses 1 boss + 4 worker threads.
- **Sockets closed** on: decode error, CEA failure, watchdog miss, DPR from the peer,
  application stop. `stop()` sends DPR, waits up to 1 s for DPA, closes the channel,
  fails any leftover futures and shuts the event loop down gracefully.
  `DiameterClient` implements `SmartLifecycle` with a late phase so it stops before the
  web server during Spring's graceful shutdown.
- **Buffers**: `DiameterMessageEncoder` sizes the outbound `ByteBuf` exactly from
  `encodedLength()`; Netty releases inbound buffers after `DiameterMessageDecoder` has
  copied the AVP payloads into plain byte arrays that live only as long as the message.
- **Timeouts** are `ScheduledFuture`s on the event loop; they are cancelled when the
  answer arrives, so the scheduler does not accumulate dead tasks.
- **Frame size cap** of 64 KiB protects against a peer that advertises a huge length.

## Error handling

| Failure | Detection | Client sees | Log |
|---|---|---|---|
| Simulator not running at startup | connect refused | 503 until the peer is up; reconnect every 2 s | `Cannot connect to Diameter peer ...; retrying in 2000 ms` |
| Simulator stops while running | `channelInactive` | in-flight requests 503, new ones 503, automatic reconnect | `Connection to ocs... closed with N requests in flight` |
| Simulator up but silent for one request | timeout task | 504 with the Hop-by-Hop id | `Request hbh=0x... timed out after 2000 ms` |
| Simulator rejects (5030, 4012, 5005, ...) | Result-Code | 422 with the code | debug line per answer |
| Peer speaks garbage | `DiameterDecodeException` | connection closed, reconnect | `Malformed Diameter message from peer` |
| Peer never answers DWR | second idle period | connection closed, reconnect | `Peer ... did not answer DWR` |
| Too many in flight | map size | 503 immediately | metric `diameter.request.rejected.busy` |
| Invalid JSON / fields | Bean Validation | 400 with field list | |

The simulator has three MSISDN suffixes that trigger these paths deliberately:
`...0000` → 5030 DIAMETER_USER_UNKNOWN, `...9999` → 4012 DIAMETER_CREDIT_LIMIT_REACHED,
`...5555` → no answer at all (exercises the 504 path). Any other number succeeds.

## Observability

- **Logs** (SLF4J/Logback): INFO for connection lifecycle and state changes, WARN for
  timeouts/503s/peer oddities, DEBUG (`GATEWAY_LOG_LEVEL=DEBUG`) for one line per
  message in each direction with command, hop-by-hop, end-to-end, flags and length.
- **Health**: `GET /actuator/health` has a `diameter` component: `UP` only when the
  peer is OPEN, with `state`, `peer` and `pendingRequests` details.
- **Metrics** (Micrometer, `/actuator/metrics/<name>` and `/actuator/prometheus`):

| Metric | Type | Meaning |
|---|---|---|
| `diameter.request` | timer with p50/p95/p99 | CCR→CCA round trip |
| `diameter.pending.requests` | gauge | current map size; must return to 0 after load |
| `diameter.peer.open` | gauge | 1 when OPEN |
| `diameter.request.timeouts` | counter | 504s |
| `diameter.request.rejected.busy` | counter | 503s from the in-flight cap |
| `diameter.answers.unmatched` | counter | late answers after timeout |
| `diameter.reconnects` | counter | |
| `jvm.memory.used`, `jvm.gc.*` | Boot defaults | what the load test samples |

## Simulator

```bash
java -jar diameter-simulator/target/diameter-simulator.jar \
     --port=3868 --min-delay=50 --max-delay=100 \
     --origin-host=ocs.telecom-bridge.local --origin-realm=telecom-bridge.local
```

Every option also reads an environment variable (`SIM_PORT`, `SIM_MIN_DELAY_MS`,
`SIM_MAX_DELAY_MS`, `SIM_ORIGIN_HOST`, ...); `SIM_LOG_LEVEL=DEBUG` logs every message.

Behaviour per connection (`SimulatorHandler`):

- Requires a CER first. A CER without `Auth-Application-Id 4` is answered 5010
  DIAMETER_NO_COMMON_APPLICATION and the socket closed. Any other message before a CER
  closes the transport (unknown peer).
- DWR → DWA 2001. DPR → DPA 2001, then close.
- CCR: validates Session-Id, Auth-Application-Id, CC-Request-Type, CC-Request-Number
  (missing → 5005 DIAMETER_MISSING_AVP with Failed-AVP); otherwise schedules the CCA on
  the connection's own event loop after a uniformly random delay in `[min, max]` ms.
  The grant equals the requested octets (or 1 MiB if none) with Validity-Time 3600.
- Unknown command → answer with the E bit and 3001 DIAMETER_COMMAND_UNSUPPORTED.
- Prints a stats line every 10 s while traffic flows, including how accurately it is
  honouring its own delay:
  `stats connections(active=1, total=1) cer=1 dwr=1 ccr=6010 cca=6001 (ok=6001, fail=0, dropped=0) protocolErrors=0 delayOvershoot(avg=8.4ms, max=28.8ms)`.
  Expect sub-millisecond overshoot on Linux and roughly 8 ms on Windows, where the OS
  scheduler tick is 15.6 ms; the load-test section has the measurements.

The same jar is started in-process by the gateway's integration tests on a random port.

## Tests

`mvn clean verify` runs 83 tests in about a minute.

| Class | Covers |
|---|---|
| `AvpTest` (14) | AVP header layout, M/V flags, **padding** (13-byte string → length 21, wire 24), Unsigned32/64, Enumerated, Address, Grouped with odd-length children, decoder rejection of bad lengths |
| `DiameterCodecTest` (12) | 20-byte header layout, Message Length includes padding, CCR round trip, decoding a hand-assembled CEA byte string, CER with Address AVP, `createAnswer` semantics, E bit, decoder strictness (version, truncation, trailing bytes) |
| `DiameterPipelineTest` (4) | Netty framing of coalesced and fragmented TCP reads, encoder bytes, malformed frame surfaces as an exception |
| `SimulatorHandlerTest` (11) | CER/CEA, rejection of unknown application, request-before-CER, DWR, delayed CCA with grant, the three test hooks, 5005 with Failed-AVP, DPR, unsupported command |
| `CcrMapperTest` (5) | JSON → CCR AVP by AVP (Session-Id first, M bits, grouped Subscription-Id), defaults, UPDATE mapping, CCA → response for success and failure |
| `ChargeControllerTest` (6) | HTTP contract with the service mocked: 200, 422, 503 with Retry-After, 504 with hop-by-hop, 400 for validation and malformed JSON |
| `DiameterClientTest` (7) | Real sockets to the in-process simulator: handshake to OPEN, **300 concurrent CCRs correlated correctly**, timeout leaves no pending entry, peer down → 503 then automatic reconnect, in-flight requests fail on drop, pending cap, DPR on stop |
| `GatewayEndToEndTest` (6) | Full Spring context + simulator: 200, 422/5030, 504, 400, health shows the peer, metrics exposed |
| `LoadTestRunnerTest` (4) | The generated subscriber numbers never collide with the simulator's failure hooks (a regression that silently produced one 504 per 8 000 requests), stay valid for the endpoint, and rotate before repeating; option parsing |
| `PacerTest` (6) | The rule that stops a paused generator replaying every missed slot at once, including a reproduction of the five-hour sleep that wrecked the first 500 000-request attempt, and that pacing recovers by itself afterwards |
| `PcapWriterTest` (8) | The capture file parses back as valid libpcap: header, frame lengths, IP and TCP checksums, per-direction sequence numbers, segmentation of oversized writes, and that stream direction comes from who spoke first rather than from port numbers |

## Capturing transaction_flow.pcap

The deliverable must show the REST call followed by CER/CEA and 5–10 CCR/CCA pairs,
all on loopback. [`transaction_flow.pcap`](transaction_flow.pcap) is checked in,
with a decoded listing beside it in
[`docs/transaction_flow-summary.txt`](docs/transaction_flow-summary.txt).

### How this capture was made, and what that means

Capturing loopback traffic on Windows needs Npcap or `pktmon`, and both need administrator
rights that were not available on the machine this was built on. So the gateway can record
its own traffic instead: pass `--capture.file=...` and a tap sits first in both Netty
pipelines, copying every byte crossing the HTTP socket and the Diameter socket into a
libpcap file.

**Be clear about what that file is.** The payload bytes are real, exactly what the sockets
carried, with real timestamps. The Ethernet, IPv4 and TCP headers around them are
synthesised by `PcapWriter`: sequence numbers, ACKs, lengths and checksums are all
internally consistent, so Wireshark reassembles the streams and its Diameter and HTTP
dissectors work normally, but it is a reconstruction at the link and transport layers
rather than a `tcpdump` of the wire. If the evaluation requires a genuine capture, take one
with Wireshark using the instructions further down; the application is unchanged either way.

```bash
./scripts/capture-transaction-flow.sh          # writes transaction_flow.pcap
```

The committed file holds 162 frames with no checksum errors:

| | CER | CEA | DWR | DWA | CCR | CCA |
|---|---|---|---|---|---|---|
| frames | 1 | 1 | 3 | 3 | 13 | 12 |

There are 12 CCAs to 13 CCRs because one request deliberately exercises the black-hole
subscriber and is answered by the gateway with a 504 rather than by the peer. Each
transaction appears as the four frames the brief describes:

```
  25   0.907  127.0.0.1:6775 -> 127.0.0.1:8082  HTTP      POST /api/v1/charge HTTP/1.1
  26   1.054  127.0.0.1:6768 -> 127.0.0.1:3868  DIAMETER  CCR  app=4 hbh=0xe93cfc65 Type=INITIAL_REQUEST MSISDN=919876543201
  27   1.117  127.0.0.1:3868 -> 127.0.0.1:6768  DIAMETER  CCA  app=4 hbh=0xe93cfc65 Result-Code=2001 DIAMETER_SUCCESS
  28   1.136  127.0.0.1:8082 -> 127.0.0.1:6775  HTTP      HTTP/1.1 200 OK
```

Two bugs were found by reading that listing rather than by trusting the code. Inbound HTTP
requests were missing entirely, because Reactor Netty's `doOnConnection` hook only runs once
a request has already been read, so the tap had to move to `doOnChannelInit`. And the writer
decided stream direction from port numbers, which is wrong whenever an ephemeral port is
numerically below the service port, as 6775 is below 8082; it now takes the first party to
send a byte as the client. Both are covered by tests.

### Taking a genuine Wireshark capture instead

**Windows** (Wireshark with Npcap installed with *Support loopback traffic* ticked):

1. Start the simulator and the gateway as in Quick start.
2. In an elevated PowerShell: `.\scripts\capture-pcap.ps1 -Seconds 60` (uses
   `tshark` on `\Device\NPF_Loopback` with filter `tcp port 8080 or tcp port 3868`
   and writes classic pcap format with `-F pcap`). Or open Wireshark, choose
   *Adapter for loopback traffic capture*, capture filter `tcp port 8080 or tcp port 3868`,
   and later *File → Save As → pcap*.
3. In another window: `.\scripts\send-sample-requests.ps1`.
4. Stop the capture. Apply display filter `diameter || http` and you will see, in order:
   `HTTP POST /api/v1/charge`, `CCR`, `CCA`, `HTTP/1.1 200` repeated ten times, with the
   `CER`/`CEA` pair near the top (captured only if the gateway was started after the
   capture began; restart the gateway during the capture to be sure). If the capture
   runs longer than 30 s of idle you also get `DWR`/`DWA`.

**Linux/macOS**: `sudo ./scripts/capture-pcap.sh 60` (tcpdump on `lo`/`lo0`).

Wireshark decodes port 3868 as Diameter automatically. Because the codec follows the
RFC byte for byte, every AVP shows by name and the *Message Length* / *AVP Length*
fields validate without the "malformed" marker. The `hopByHopId` in each JSON response
equals the *Hop-by-Hop Identifier* Wireshark shows for the matching CCR/CCA frames.

## Load test

Requirement: 100 TPS for 500 000 transactions (83 minutes), stable memory, p95 < 100 ms.

```bash
# Both short cases end to end: starts the simulator and gateway, writes reports to docs/
./scripts/run-load-tests.sh

# The full deliverable run, 500 000 requests, about 83 minutes
./scripts/run-load-tests.sh full

# Or drive it by hand against an already-running gateway
java -jar load-test/target/load-test.jar --url=http://localhost:8080/api/v1/charge --tps=100 --total=500000
```

The runner is an **open-model** generator: a scheduler fires one request every 10 ms
regardless of how earlier ones are doing (`scheduleAtFixedRate`), which is how real
traffic behaves and the honest way to measure percentiles under fixed TPS. Latency goes
into an HdrHistogram; a warm-up of 1 000 requests is excluded. Every 10 s it prints a
progress line and samples the gateway heap from Actuator; at the end it writes
`load-test-report-<timestamp>.txt` with percentiles, status counts, heap first/last/min/max
and the gateway's own `diameter_request_seconds` quantiles, then prints
`RESULT: PASS` or `FAIL` against the constraints. Exit code 0 on PASS.

Other options: `--warmup=1000 --timeout-ms=5000 --progress-seconds=10 --p95-limit-ms=100
--report=<file> --actuator=<base url>`.

MSISDNs rotate through 100 000 distinct numbers whose last four digits never hit a test
hook, so every request is a normal success.

### The two constraints in the brief contradict each other

The brief asks for a simulator that answers each CCR after **50–100 ms**, and for an
end-to-end **p95 under 100 ms**. Those cannot both hold. A delay drawn uniformly from
[50, 100] has a p95 of **97.5 ms by construction**, before a single byte of HTTP is
parsed. Any real gateway adds a few milliseconds on top, so the end-to-end p95 lands just
above 100 ms no matter how good the code is. This is a property of the specification, not
of the implementation, and the honest thing to do is measure the two parts separately
rather than quietly shrink the simulator delay until the number looks right.

The report therefore breaks every request into the part the peer owns and the part this
service owns, measured per request rather than by subtracting percentiles. All three runs
below are in [`docs/`](docs) and were produced by the commands in this section.

**Run A, the full deliverable: 500 000 requests at the mandated 50–100 ms peer**
([full report](docs/load-test-full-500k-50-100ms.txt)), 83 minutes at exactly 100.0 req/s:

```
Elapsed         : 5005.1 s              Achieved rate : 100.0 req/s
Completed       : 500000 / 500000       HTTP 200: 500000,  5xx: 0,  transport err: 0
Latency (ms)    : p50=84.4  p90=102.8  p95=108.3  p99=116.8  p99.9=118.8  max=186.6
Breakdown of the end-to-end figure above, per request:
  peer round trip (CCR->CCA, dominated by the simulator's own delay)
                : p50=82.0  p90=100.0  p95=106.0  p99=114.0  max=143.0  mean=83.2 ms
  gateway + client overhead on top of that (HTTP, JSON, sockets)
                : p50=2.2   p90=2.8    p95=3.1    p99=4.2    max=98.6   mean=2.2 ms
Gateway heap    : first=29.0 MB  last=27.7 MB  min=23.2 MB  max=67.6 MB  (166 samples)
  pending now = 0     unmatched answers = 0     timeouts = 0
Constraint errors == 0   : OK      all completed : OK      run continuous : OK
Constraint p95 < 100 ms  : VIOLATED                        RESULT: FAIL
```

Half a million transactions, not one error, not one timeout, not one unmatched answer, and
the pending-request table back at zero. The gateway contributes **3.1 ms at p95**; the
emulated peer accounts for the other 106 ms. The verdict is an honest FAIL against the
literal target, and it would stay FAIL however fast the gateway got.

Memory over 83 minutes is the answer to the leak question: the heap ends at **27.7 MB
having started at 29.0 MB**, sawtoothing between 23 and 68 MB across 166 samples. Nothing
accumulates across half a million correlated request/response pairs.

**Run B, a shorter run at the same settings**
([full report](docs/load-test-mandated-50-100ms.txt)), 30 000 requests, for comparison:
p95 108.6 ms end to end, 3.3 ms of gateway overhead. The numbers hold at both scales.

**Run C, the same gateway against a peer with headroom** (`--max-delay=80`,
[full report](docs/load-test-headroom-50-80ms.txt)), 12 000 requests:

```
Latency (ms)    : p50=78.8  p90=94.5  p95=95.5  p99=104.9  max=113.2
  peer round trip : p95=93.0 ms          gateway + client overhead : p95=4.0 ms
Constraint p95 < 100 ms : OK   errors == 0 : OK   RESULT: PASS
```

```bash
java -jar diameter-simulator/target/diameter-simulator.jar --min-delay=50 --max-delay=80
```

So: **3–4 ms of gateway overhead at p95, zero errors in 542 000 requests across three runs,
no unmatched answers, no timeouts, and a pending table back at zero every time.**

Three measurement notes worth knowing before reading any figure from this repository:

- **A paused generator must not replay its backlog.** The first attempt at the 83-minute
  run was left going overnight. The laptop hit its 15-minute idle sleep timer after 34
  minutes, and on wake `scheduleAtFixedRate` fired roughly 296 000 missed ticks in one
  burst: in-flight requests went from 9 to 294 993 and the run was worthless. Slots missed
  during a pause are now dropped rather than replayed (`Pacer`), an overload guard caps
  in-flight requests, and the report fails a run that was not continuous, so this can never
  again masquerade as a healthy result. The committed 500 000-request run reports
  `run continuous : OK`.

- **Windows timer granularity, and a fix that turned out not to work.** Windows ticks
  every 15.6 ms and rounds timed waits up to the next tick, so the simulator's "50–100 ms"
  delay actually fires about **8 ms late on average and 16 ms late at p95**. The
  widely-repeated cure is to park a daemon thread in `Thread.sleep(Long.MAX_VALUE)`, which
  used to make HotSpot ask Windows for a 1 ms timer. I implemented that, measured a drop
  to 0.9 ms, and believed it. It was wrong: the measurement had other JVMs running
  alongside it that were holding the system timer down, and re-running the A/B on a quiet
  machine shows the trick does nothing on JDK 21:

  |                             | no pin | with pin |
  |---|---|---|
  | `ScheduledExecutorService`  | avg 8.3, p95 15.7 ms | avg 7.5, p95 15.8 ms |
  | `ScheduledExecutorService` + hop to event loop | avg 8.7, p95 16.0 ms | avg 9.6, p95 15.9 ms |
  | Netty `NioEventLoop.schedule` | avg 8.4, p95 16.4 ms | avg 9.1, p95 16.4 ms |

  The class was deleted rather than left in place looking like a fix. What survives is the
  measurement: the simulator reports its own accuracy in every stats line
  (`delayOvershoot(avg=8.4ms, max=28.8ms)`), so the emulated peer can never quietly drift
  away from its contract. On Windows the peer therefore answers slightly later than
  configured, which inflates the *peer* half of the breakdown above and not the gateway's
  overhead; on Linux and in the Docker image the overshoot is sub-millisecond.
- **Loopback HTTP on Windows is not free.** Measured with a keep-alive socket and no
  Diameter involved: bare Netty answers in ~1.0 ms, Reactor Netty without Spring in
  ~1.4 ms, and the full WebFlux stack in ~2.7 ms for a trivial route. So most of the
  gateway's 2–4 ms overhead is that platform and framework floor rather than the Diameter
  code, and none of it is the correlation logic.

A JMeter or Gatling script would do the same job (JMeter: HTTP sampler + Constant
Throughput Timer at 6 000/min, 500 000 iterations); the custom runner was chosen so the
report and pass/fail logic live in the repository and need no extra install.

## Docker

```bash
docker compose up --build
# gateway on http://localhost:8080, simulator on 3868, gateway reaches it as "simulator"
```

Both images are multi-stage (Maven build → Temurin 21 JRE) with explicit heap flags.

## Design decisions

- **Own codec on Netty instead of jDiameter.** Four message types and seventeen AVPs are
  a few hundred lines; jDiameter is a large, XML-configured stack that would hide exactly
  the header, padding and correlation work the challenge wants to see. Netty gives the
  non-blocking socket, frame splitting and single-threaded-per-channel model for free.
- **TCP, not SCTP.** Acceptable per the brief; SCTP would need `sun.nio.ch.sctp`, which
  is unsupported on Windows. The transport is isolated in `DiameterClient` so an SCTP
  channel could be swapped in.
- **WebFlux + `CompletableFuture`.** Reactor is the natural async model in Spring Boot 3;
  the Diameter client stays framework-free (`CompletableFuture`) so it is testable
  without Spring and reusable elsewhere.
- **Futures complete on the Diameter event loop.** The remaining work (build JSON, write
  HTTP) is microseconds, so a thread hop would cost more than it saves. If heavier
  post-processing were added, `publishOn` a parallel scheduler after `fromFuture`.
- **Register before write; remove exactly once.** The two rules that make the
  correlation map leak-proof and race-free.
- **422 for Diameter rejections** (see REST API). **503 vs 504**: 503 when we could not
  even try (or the link died), 504 when we tried and the peer stayed silent.
- **Timeouts on the event loop, not a separate scheduler.** One fewer thread, and
  `cancel()` on completion keeps the timer queue small.
- **Simulator delay on the event loop** rather than `Thread.sleep`, so 100 TPS costs no
  threads and the ordering of answers can differ from requests, which is precisely what
  the correlation logic must handle.
- **The simulator times the delay on a `ScheduledExecutorService`, not on the event loop.**
  Parking has nanosecond precision; Netty rounds its selector timeout up to a whole
  millisecond. The write is hopped back to the event loop so the channel is still only
  touched from its own thread. On Windows this makes no measurable difference because the
  OS tick dominates both, but on the Linux hosts this would really run on, it does.
- **The simulator measures its own timing error** and the load test separates peer time
  from gateway time, per request. A benchmark you cannot decompose is a benchmark you
  cannot trust: the first version of this load test reported a clean-looking p95 that was
  hiding both an 8 ms simulator drift and one timed-out request in every 8 000.

## Evaluation criteria map

| Criterion | Where to look |
|---|---|
| Concurrency management, ConcurrentHashMap of Hop-by-Hop ids | `PendingRequests`, `DiameterClient.send/onAnswer/onTimeout/onDisconnected`, `DiameterClientTest.concurrentRequestsAreCorrelatedByHopByHop` |
| Protocol accuracy: header, command codes, application ids, AVP padding | `DiameterMessage`, `Avp`, `DiameterCodec`, `AvpTest`, `DiameterCodecTest`, Wireshark on the PCAP |
| Resource management: sockets, pools, leaks | `DiameterClient.stop`, `SmartLifecycle` phase, `max-pending-requests`, timeout cancellation, load-test heap samples |
| Error handling: server down → 503/504 | `GatewayExceptionHandler`, `DiameterClientTest.peerDownFailsFastAndReconnectsWhenPeerReturns`, `GatewayEndToEndTest.silentPeerReturns504` |
| Lead maturity: clean code, SLF4J, unit tests, Docker | package layout, log lines with hop-by-hop ids, 83 tests, two Dockerfiles + compose |

## Limitations and next steps

- Single peer, no failover or realm-based routing; a `PeerTable` with N `DiameterClient`s
  and round-robin would be the next step.
- No TLS/DTLS on the Diameter link and no SCTP.
- Session state is not stored: UPDATE/TERMINATION work when the caller passes back the
  `sessionId`, but the gateway does not validate it belongs to a live session.
- Only `CC-Total-Octets` is mapped; `Multiple-Services-Credit-Control` with rating
  groups is not.
- Micrometer's timer percentiles are decaying estimates, fine for dashboards; the
  load-test HdrHistogram is the exact figure.
- On the development machine that produced the sample outputs, port 8080 was already
  taken by a local Apache, hence `GATEWAY_PORT=8081` in some logs.
- The end-to-end p95 target cannot be met against a peer whose own p95 is 97.5 ms; see
  the load-test section for the decomposition and for the headroom configuration that
  does pass.
