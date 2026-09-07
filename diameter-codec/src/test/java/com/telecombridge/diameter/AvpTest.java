package com.telecombridge.diameter;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AVP-level encoding rules from RFC 6733 section 4: header layout, flags, padding, grouped payloads. */
class AvpTest {

    @Test
    void unsigned32ResultCodeEncodesToTwelveBytesWithMandatoryFlag() {
        Avp avp = Avp.unsigned32(AvpCode.RESULT_CODE, ResultCode.DIAMETER_SUCCESS);

        byte[] bytes = DiameterCodec.encodeAvps(List.of(avp));

        assertThat(bytes).containsExactly(
                0x00, 0x00, 0x01, 0x0C,   // code 268
                0x40,                     // flags: M
                0x00, 0x00, 0x0C,         // length 12
                0x00, 0x00, 0x07, 0xD1);  // 2001
        assertThat(avp.length()).isEqualTo(12);
        assertThat(avp.paddedLength()).isEqualTo(12);
    }

    @Test
    void utf8StringNotMultipleOfFourIsPaddedButLengthFieldExcludesPadding() {
        // "TelecomBridge" is 13 bytes: length field 21, wire size 24
        Avp avp = Avp.utf8(AvpCode.PRODUCT_NAME, 0, "TelecomBridge");

        assertThat(avp.length()).isEqualTo(21);
        assertThat(avp.paddedLength()).isEqualTo(24);

        byte[] bytes = DiameterCodec.encodeAvps(List.of(avp));
        assertThat(bytes).hasSize(24);
        assertThat(bytes[5]).isEqualTo((byte) 0x00);
        assertThat(bytes[6]).isEqualTo((byte) 0x00);
        assertThat(bytes[7]).isEqualTo((byte) 0x15);            // 21
        assertThat(new String(bytes, 8, 13)).isEqualTo("TelecomBridge");
        assertThat(bytes[21]).isZero();
        assertThat(bytes[22]).isZero();
        assertThat(bytes[23]).isZero();
    }

    @Test
    void paddingRoundsToNextMultipleOfFour() {
        assertThat(DiameterCodec.pad4(8)).isEqualTo(8);
        assertThat(DiameterCodec.pad4(9)).isEqualTo(12);
        assertThat(DiameterCodec.pad4(10)).isEqualTo(12);
        assertThat(DiameterCodec.pad4(11)).isEqualTo(12);
        assertThat(DiameterCodec.pad4(12)).isEqualTo(12);
    }

    @Test
    void vendorSpecificAvpHasTwelveByteHeaderAndVendorBit() {
        Avp avp = Avp.vendorSpecific(1001, Avp.FLAG_MANDATORY, 10415L, new byte[] {1, 2});

        assertThat(avp.isVendorSpecific()).isTrue();
        assertThat(avp.isMandatory()).isTrue();
        assertThat(avp.headerLength()).isEqualTo(12);
        assertThat(avp.length()).isEqualTo(14);
        assertThat(avp.paddedLength()).isEqualTo(16);

        byte[] bytes = DiameterCodec.encodeAvps(List.of(avp));
        assertThat(bytes[4]).isEqualTo((byte) 0xC0);                 // V + M
        assertThat(bytes).hasSize(16);
        assertThat(bytes[8] << 24 | bytes[9] << 16 | bytes[10] << 8 | (bytes[11] & 0xFF)).isEqualTo(10415);

        Avp decoded = DiameterCodec.decodeAvps(bytes).get(0);
        assertThat(decoded).isEqualTo(avp);
        assertThat(decoded.vendorId()).isEqualTo(10415L);
    }

    @Test
    void unsigned64RoundTrips() {
        Avp avp = Avp.unsigned64(AvpCode.CC_TOTAL_OCTETS, 1_048_576L);
        Avp decoded = DiameterCodec.decodeAvps(DiameterCodec.encodeAvps(List.of(avp))).get(0);

        assertThat(decoded.asUnsigned64()).isEqualTo(1_048_576L);
        assertThat(decoded.length()).isEqualTo(16);
    }

    @Test
    void unsigned32TreatsHighBitAsUnsigned() {
        Avp avp = Avp.unsigned32(AvpCode.ORIGIN_STATE_ID, 0xFFFFFFFFL);
        assertThat(avp.asUnsigned32()).isEqualTo(4294967295L);
    }

    @Test
    void enumeratedIsInteger32() {
        Avp avp = Avp.enumerated(AvpCode.CC_REQUEST_TYPE, CcRequestType.INITIAL_REQUEST.code());
        assertThat(avp.dataLength()).isEqualTo(4);
        assertThat(avp.asInteger32()).isEqualTo(1);
    }

    @Test
    void ipv4AddressEncodesFamilyPrefix() throws Exception {
        Avp avp = Avp.address(AvpCode.HOST_IP_ADDRESS, InetAddress.getByName("127.0.0.1"));

        assertThat(avp.data()).containsExactly(0x00, 0x01, 127, 0, 0, 1);
        assertThat(avp.length()).isEqualTo(14);
        assertThat(avp.paddedLength()).isEqualTo(16);
        assertThat(avp.asAddress().getHostAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void groupedAvpPayloadIsSequenceOfPaddedChildren() {
        Avp subscriptionId = Avp.grouped(AvpCode.SUBSCRIPTION_ID,
                Avp.enumerated(AvpCode.SUBSCRIPTION_ID_TYPE, SubscriptionIdType.END_USER_E164.code()),
                Avp.utf8(AvpCode.SUBSCRIPTION_ID_DATA, "919876543210"));

        // child 1: 8 + 4 = 12; child 2: 8 + 12 = 20; group: 8 + 32 = 40
        assertThat(subscriptionId.length()).isEqualTo(40);

        List<Avp> children = subscriptionId.asGrouped();
        assertThat(children).hasSize(2);
        assertThat(children.get(0).asInteger32()).isZero();
        assertThat(children.get(1).asUtf8()).isEqualTo("919876543210");
        assertThat(subscriptionId.child(AvpCode.SUBSCRIPTION_ID_DATA)).map(Avp::asUtf8).contains("919876543210");
    }

    @Test
    void groupedChildWithOddLengthKeepsFollowingChildAligned() {
        Avp group = Avp.grouped(999,
                Avp.utf8(1, "abcde"),          // 13 -> padded 16
                Avp.unsigned32(2, 7));         // 12

        assertThat(group.length()).isEqualTo(8 + 16 + 12);
        List<Avp> children = group.asGrouped();
        assertThat(children).hasSize(2);
        assertThat(children.get(1).asUnsigned32()).isEqualTo(7L);
    }

    @Test
    void typedAccessorRejectsWrongLength() {
        Avp avp = Avp.utf8(AvpCode.ORIGIN_HOST, "abc");
        assertThatThrownBy(avp::asUnsigned32)
                .isInstanceOf(DiameterDecodeException.class)
                .hasMessageContaining("Unsigned32");
    }

    @Test
    void rawFactoryRejectsVendorBitWithoutVendorId() {
        assertThatThrownBy(() -> Avp.of(1, Avp.FLAG_VENDOR, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodingRejectsLengthShorterThanHeader() {
        byte[] bad = {0, 0, 0, 1, 0x40, 0, 0, 4};
        assertThatThrownBy(() -> DiameterCodec.decodeAvps(bad))
                .isInstanceOf(DiameterDecodeException.class)
                .hasMessageContaining("shorter than header");
    }

    @Test
    void decodingRejectsLengthBeyondBuffer() {
        byte[] bad = {0, 0, 0, 1, 0x40, 0, 0, 40, 1, 2, 3, 4};
        assertThatThrownBy(() -> DiameterCodec.decodeAvps(bad))
                .isInstanceOf(DiameterDecodeException.class)
                .hasMessageContaining("exceeds");
    }
}
