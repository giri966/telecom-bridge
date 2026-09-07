package com.telecombridge.diameter;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Message-level encoding: 20-byte header, flags, total length, round trips and decoder strictness. */
class DiameterCodecTest {

    private static DiameterMessage sampleCcr() {
        return DiameterMessage.request(CommandCode.CREDIT_CONTROL, ApplicationId.CREDIT_CONTROL)
                .proxiable()
                .hopByHopId(0x0000002A)
                .endToEndId(0x66d9a02a)
                .avp(Avp.utf8(AvpCode.SESSION_ID, "gateway.telecom-bridge.local;1725523200;42"))
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.telecom-bridge.local"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "telecom-bridge.local"))
                .avp(Avp.utf8(AvpCode.DESTINATION_REALM, "telecom-bridge.local"))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL))
                .avp(Avp.utf8(AvpCode.SERVICE_CONTEXT_ID, "32251@3gpp.org"))
                .avp(Avp.enumerated(AvpCode.CC_REQUEST_TYPE, CcRequestType.INITIAL_REQUEST.code()))
                .avp(Avp.unsigned32(AvpCode.CC_REQUEST_NUMBER, 0))
                .avp(Avp.grouped(AvpCode.SUBSCRIPTION_ID,
                        Avp.enumerated(AvpCode.SUBSCRIPTION_ID_TYPE, SubscriptionIdType.END_USER_E164.code()),
                        Avp.utf8(AvpCode.SUBSCRIPTION_ID_DATA, "919876543210")))
                .avp(Avp.grouped(AvpCode.REQUESTED_SERVICE_UNIT,
                        Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, 1_048_576L)))
                .build();
    }

    @Test
    void headerIsTwentyBytesInRfcLayout() {
        byte[] bytes = DiameterCodec.encode(sampleCcr());

        assertThat(bytes[0]).isEqualTo((byte) 1);                                  // version
        int length = (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
        assertThat(length).isEqualTo(bytes.length);                                 // Message Length = wire size
        assertThat(length % 4).isZero();
        assertThat(bytes[4]).isEqualTo((byte) 0xC0);                               // R + P
        assertThat(HexFormat.of().formatHex(bytes, 5, 8)).isEqualTo("000110");     // command 272
        assertThat(HexFormat.of().formatHex(bytes, 8, 12)).isEqualTo("00000004");  // application 4
        assertThat(HexFormat.of().formatHex(bytes, 12, 16)).isEqualTo("0000002a"); // hop-by-hop
        assertThat(HexFormat.of().formatHex(bytes, 16, 20)).isEqualTo("66d9a02a"); // end-to-end
    }

    @Test
    void messageLengthIncludesAvpPadding() {
        DiameterMessage msg = DiameterMessage.request(CommandCode.DEVICE_WATCHDOG, ApplicationId.BASE)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "abc"))   // 11 -> 12 on the wire
                .build();

        assertThat(msg.encodedLength()).isEqualTo(20 + 12);
        assertThat(DiameterCodec.encode(msg)).hasSize(32);
    }

    @Test
    void ccrRoundTripsThroughBytes() {
        DiameterMessage original = sampleCcr();

        DiameterMessage decoded = DiameterCodec.decode(DiameterCodec.encode(original));

        assertThat(decoded.isRequest()).isTrue();
        assertThat(decoded.isProxiable()).isTrue();
        assertThat(decoded.isError()).isFalse();
        assertThat(decoded.commandCode()).isEqualTo(CommandCode.CREDIT_CONTROL);
        assertThat(decoded.applicationId()).isEqualTo(ApplicationId.CREDIT_CONTROL);
        assertThat(decoded.hopByHopId()).isEqualTo(0x2A);
        assertThat(decoded.endToEndId()).isEqualTo(0x66d9a02a);
        assertThat(decoded.avps()).containsExactlyElementsOf(original.avps());
        assertThat(decoded.sessionId()).contains("gateway.telecom-bridge.local;1725523200;42");
        assertThat(decoded.avp(AvpCode.CC_REQUEST_TYPE).orElseThrow().asInteger32()).isEqualTo(1);
        assertThat(decoded.avp(AvpCode.SUBSCRIPTION_ID).orElseThrow()
                .child(AvpCode.SUBSCRIPTION_ID_DATA).orElseThrow().asUtf8()).isEqualTo("919876543210");
        assertThat(decoded.avp(AvpCode.REQUESTED_SERVICE_UNIT).orElseThrow()
                .child(AvpCode.CC_TOTAL_OCTETS).orElseThrow().asUnsigned64()).isEqualTo(1_048_576L);
    }

    @Test
    void decodesHandAssembledCeaBytes() {
        // CEA: version 1, length 0x40 (64 = 20 header + 12 + 20 + 12), flags 0x00, command 257,
        // app 0, hbh 1, e2e 0x66d9a001. AVPs: Result-Code 2001, Origin-Host "ocs.local"
        // (length field 17, padded to 20 on the wire), Auth-Application-Id 4
        String hex = "01000040" + "00000101" + "00000000" + "00000001" + "66d9a001"
                + "0000010c" + "4000000c" + "000007d1"
                + "00000108" + "40000011" + "6f63732e6c6f63616c" + "000000"
                + "00000102" + "4000000c" + "00000004";
        byte[] bytes = HexFormat.of().parseHex(hex);
        assertThat(bytes).hasSize(64);

        DiameterMessage cea = DiameterCodec.decode(bytes);

        assertThat(cea.isRequest()).isFalse();
        assertThat(cea.commandName()).isEqualTo("CEA");
        assertThat(cea.applicationId()).isZero();
        assertThat(cea.hopByHopId()).isEqualTo(1);
        assertThat(cea.resultCode()).contains(ResultCode.DIAMETER_SUCCESS);
        assertThat(cea.originHost()).contains("ocs.local");
        assertThat(cea.avp(AvpCode.AUTH_APPLICATION_ID).orElseThrow().asUnsigned32()).isEqualTo(4L);
    }

    @Test
    void cerWithAddressAvpRoundTrips() throws Exception {
        DiameterMessage cer = DiameterMessage.request(CommandCode.CAPABILITIES_EXCHANGE, ApplicationId.BASE)
                .hopByHopId(1).endToEndId(2)
                .avp(Avp.utf8(AvpCode.ORIGIN_HOST, "gateway.telecom-bridge.local"))
                .avp(Avp.utf8(AvpCode.ORIGIN_REALM, "telecom-bridge.local"))
                .avp(Avp.address(AvpCode.HOST_IP_ADDRESS, InetAddress.getByName("127.0.0.1")))
                .avp(Avp.unsigned32(AvpCode.VENDOR_ID, 0))
                .avp(Avp.utf8(AvpCode.PRODUCT_NAME, 0, "TelecomBridge"))
                .avp(Avp.unsigned32(AvpCode.AUTH_APPLICATION_ID, ApplicationId.CREDIT_CONTROL))
                .build();

        DiameterMessage decoded = DiameterCodec.decode(DiameterCodec.encode(cer));

        assertThat(decoded.commandName()).isEqualTo("CER");
        assertThat(decoded.avp(AvpCode.HOST_IP_ADDRESS).orElseThrow().asAddress().getHostAddress())
                .isEqualTo("127.0.0.1");
        assertThat(decoded.avp(AvpCode.PRODUCT_NAME).orElseThrow().isMandatory()).isFalse();
        assertThat(decoded.avps()).containsExactlyElementsOf(cer.avps());
    }

    @Test
    void createAnswerCopiesIdentifiersAndClearsRequestBit() {
        DiameterMessage ccr = sampleCcr();

        DiameterMessage cca = ccr.createAnswer()
                .avp(Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS))
                .build();

        assertThat(cca.isRequest()).isFalse();
        assertThat(cca.isProxiable()).isTrue();
        assertThat(cca.commandCode()).isEqualTo(ccr.commandCode());
        assertThat(cca.applicationId()).isEqualTo(ccr.applicationId());
        assertThat(cca.hopByHopId()).isEqualTo(ccr.hopByHopId());
        assertThat(cca.endToEndId()).isEqualTo(ccr.endToEndId());
        assertThat(cca.commandName()).isEqualTo("CCA");
        assertThat(DiameterCodec.encode(cca)[4]).isEqualTo((byte) 0x40);
    }

    @Test
    void errorAnswerSetsEBit() {
        DiameterMessage answer = sampleCcr().createAnswer().error().build();
        assertThat(answer.isError()).isTrue();
        assertThat(DiameterCodec.encode(answer)[4]).isEqualTo((byte) 0x60);
    }

    @Test
    void decoderRejectsWrongVersion() {
        byte[] bytes = DiameterCodec.encode(sampleCcr());
        bytes[0] = 2;
        assertThatThrownBy(() -> DiameterCodec.decode(bytes))
                .isInstanceOf(DiameterDecodeException.class)
                .hasMessageContaining("version");
    }

    @Test
    void decoderRejectsTruncatedMessage() {
        byte[] bytes = DiameterCodec.encode(sampleCcr());
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length - 8);
        assertThatThrownBy(() -> DiameterCodec.decode(truncated))
                .isInstanceOf(DiameterDecodeException.class);
    }

    @Test
    void decoderRejectsLengthNotMultipleOfFour() {
        byte[] bytes = DiameterCodec.encode(sampleCcr());
        bytes[3] = (byte) (bytes[3] + 1);
        assertThatThrownBy(() -> DiameterCodec.decode(bytes))
                .isInstanceOf(DiameterDecodeException.class)
                .hasMessageContaining("Invalid message length");
    }

    @Test
    void decoderRejectsTrailingGarbage() {
        byte[] bytes = DiameterCodec.encode(sampleCcr());
        byte[] withTrailer = java.util.Arrays.copyOf(bytes, bytes.length + 4);
        assertThatThrownBy(() -> DiameterCodec.decode(withTrailer))
                .isInstanceOf(DiameterDecodeException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    void toStringIsCompactAndLogFriendly() {
        assertThat(sampleCcr().toString())
                .startsWith("CCR(cmd=272 app=4 hbh=0x0000002a e2e=0x66d9a02a flags=RP");
    }
}
