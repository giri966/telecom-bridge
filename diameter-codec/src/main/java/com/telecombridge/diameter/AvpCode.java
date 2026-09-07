package com.telecombridge.diameter;

/**
 * AVP codes from RFC 6733 (base protocol) and RFC 4006 (Credit-Control).
 * All of these are IETF standard AVPs (no Vendor-Id).
 */
public final class AvpCode {

    // ---- RFC 6733 base protocol ----
    public static final int SESSION_ID = 263;
    public static final int ORIGIN_HOST = 264;
    public static final int ORIGIN_REALM = 296;
    public static final int DESTINATION_REALM = 283;
    public static final int DESTINATION_HOST = 293;
    public static final int HOST_IP_ADDRESS = 257;
    public static final int VENDOR_ID = 266;
    public static final int PRODUCT_NAME = 269;
    public static final int FIRMWARE_REVISION = 267;
    public static final int AUTH_APPLICATION_ID = 258;
    public static final int ACCT_APPLICATION_ID = 259;
    public static final int VENDOR_SPECIFIC_APPLICATION_ID = 260;
    public static final int SUPPORTED_VENDOR_ID = 265;
    public static final int RESULT_CODE = 268;
    public static final int ERROR_MESSAGE = 281;
    public static final int FAILED_AVP = 279;
    public static final int ORIGIN_STATE_ID = 278;
    public static final int DISCONNECT_CAUSE = 273;
    public static final int EVENT_TIMESTAMP = 55;

    // ---- RFC 4006 Credit-Control ----
    public static final int CC_REQUEST_TYPE = 416;
    public static final int CC_REQUEST_NUMBER = 415;
    public static final int SERVICE_CONTEXT_ID = 461;
    public static final int SUBSCRIPTION_ID = 443;
    public static final int SUBSCRIPTION_ID_TYPE = 450;
    public static final int SUBSCRIPTION_ID_DATA = 444;
    public static final int REQUESTED_SERVICE_UNIT = 437;
    public static final int GRANTED_SERVICE_UNIT = 431;
    public static final int USED_SERVICE_UNIT = 446;
    public static final int CC_TOTAL_OCTETS = 421;
    public static final int CC_INPUT_OCTETS = 412;
    public static final int CC_OUTPUT_OCTETS = 414;
    public static final int CC_TIME = 420;
    public static final int VALIDITY_TIME = 448;
    public static final int SERVICE_IDENTIFIER = 439;
    public static final int RATING_GROUP = 432;
    public static final int MULTIPLE_SERVICES_CREDIT_CONTROL = 456;
    public static final int TERMINATION_CAUSE = 295;

    private AvpCode() {
    }
}
