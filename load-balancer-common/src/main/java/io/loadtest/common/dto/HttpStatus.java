package io.loadtest.common.dto;

/**
 * HTTP methods supported by the load generator.
 *
 * Why enum instead of String?
 * - Type safety prevents typos at compile time
 * - Pattern matching in switch expressions (Java 21)
 * - gRPC protobuf generates enum by default
 */
public enum HttpStatus {
    CONTINUE(100),
    SWITCHING_PROTOCOLS(101),
    PROCESSING(102),
    OK(200),
    CREATED(201),
    ACCEPTED(202),
    NON_AUTHORITATIVE_INFORMATION(203),
    NO_CONTENT(204),
    RESET_CONTENT(205),
    PARTIAL_CONTENT(206),
    MULTI_STATUS(207),
    ALREADY_REPORTED(208),
    IM_USED(226),
    MULTIPLE_CHOICES(300),
    MOVED_PERMANENTLY(301),
    FOUND(302),
    SEE_OTHER(303),
    NOT_MODIFIED(304),
    USE_PROXY(305),
    TEMPORARY_REDIRECT(307),
    PERMANENT_REDIRECT(308),
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    PAYMENT_REQUIRED(402),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    PROXY_AUTHENTICATION_REQUIRED(407),
    REQUEST_TIMEOUT(408),
    CONFLICT(409),
    GONE(410),
    LENGTH_REQUIRED(411),
    PRECONDITION_FAILED(412),
    PAYLOAD_TOO_LARGE(413),
    URI_TOO_LONG(414),
    UNSUPPORTED_MEDIA_TYPE(415),
    RANGE_NOT_SATISFIABLE(416),
    EXPECTATION_FAILED(417),
    IM_A_TEAPOT(418),
    MISDIRECTED_REQUEST(421),
    UNPROCESSABLE_ENTITY(422),
    LOCKED(423),
    FAILED_DEPENDENCY(424),
    TOO_EARLY(425),
    UPGRADE_REQUIRED(426),
    PRECONDITION_REQUIRED(428),
    TOO_MANY_REQUESTS(429),
    REQUEST_HEADER_FIELDS_TOO_LARGE(431),
    UNAVAILABLE_FOR_LEGAL_REASONS(451),
    INTERNAL_SERVER_ERROR(500),
    NOT_IMPLEMENTED(501),
    BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503),
    GATEWAY_TIMEOUT(504),
    HTTP_VERSION_NOT_SUPPORTED(505),
    VARIANT_ALSO_NEGOTIATES(506),
    INSUFFICIENT_STORAGE(507),
    LOOP_DETECTED(508),
    NOT_EXTENDED(510),
    NETWORK_AUTHENTICATION_REQUIRED(511),

    // Non-standard codes we track
    CONNECTION_ERROR(-1),
    TIMEOUT_ERROR(-2),
    DNS_ERROR(-3),
    TLS_ERROR(-4);

    private final int code;

    HttpStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * Categorize status code for success/failure determination.
     * Using pattern matching for switch (Java 21 feature).
     */
    public StatusCategory category() {
        return switch (this) {
            case OK, CREATED, ACCEPTED, NON_AUTHORITATIVE_INFORMATION,
                 NO_CONTENT, RESET_CONTENT, PARTIAL_CONTENT,
                 MULTI_STATUS, ALREADY_REPORTED, IM_USED -> StatusCategory.SUCCESS;

            case CONTINUE, SWITCHING_PROTOCOLS, PROCESSING -> StatusCategory.INFORMATIONAL;

            case MULTIPLE_CHOICES, MOVED_PERMANENTLY, FOUND, SEE_OTHER,
                 NOT_MODIFIED, USE_PROXY, TEMPORARY_REDIRECT, PERMANENT_REDIRECT -> StatusCategory.REDIRECT;

            case BAD_REQUEST, UNAUTHORIZED, PAYMENT_REQUIRED, FORBIDDEN,
                 NOT_FOUND, METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, PROXY_AUTHENTICATION_REQUIRED,
                 REQUEST_TIMEOUT, CONFLICT, GONE, LENGTH_REQUIRED, PRECONDITION_FAILED,
                 PAYLOAD_TOO_LARGE, URI_TOO_LONG, UNSUPPORTED_MEDIA_TYPE, RANGE_NOT_SATISFIABLE,
                 EXPECTATION_FAILED, IM_A_TEAPOT, MISDIRECTED_REQUEST, UNPROCESSABLE_ENTITY,
                 LOCKED, FAILED_DEPENDENCY, TOO_EARLY, UPGRADE_REQUIRED, PRECONDITION_REQUIRED,
                 TOO_MANY_REQUESTS, REQUEST_HEADER_FIELDS_TOO_LARGE, UNAVAILABLE_FOR_LEGAL_REASONS -> StatusCategory.CLIENT_ERROR;

            case INTERNAL_SERVER_ERROR, NOT_IMPLEMENTED, BAD_GATEWAY,
                 SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT, HTTP_VERSION_NOT_SUPPORTED,
                 VARIANT_ALSO_NEGOTIATES, INSUFFICIENT_STORAGE, LOOP_DETECTED,
                 NOT_EXTENDED, NETWORK_AUTHENTICATION_REQUIRED -> StatusCategory.SERVER_ERROR;

            case CONNECTION_ERROR, TIMEOUT_ERROR, DNS_ERROR, TLS_ERROR -> StatusCategory.NETWORK_ERROR;
        };
    }

    /**
     * Resolve enum from numeric HTTP status code.
     * Pattern matching with guarded clauses for range matching.
     */
    public static HttpStatus fromCode(int code) {
        return switch (code) {
            case 100 -> CONTINUE;
            case 101 -> SWITCHING_PROTOCOLS;
            case 102 -> PROCESSING;
            case 200 -> OK;
            case 201 -> CREATED;
            case 202 -> ACCEPTED;
            case 203 -> NON_AUTHORITATIVE_INFORMATION;
            case 204 -> NO_CONTENT;
            case 205 -> RESET_CONTENT;
            case 206 -> PARTIAL_CONTENT;
            case 207 -> MULTI_STATUS;
            case 208 -> ALREADY_REPORTED;
            case 226 -> IM_USED;
            case 300 -> MULTIPLE_CHOICES;
            case 301 -> MOVED_PERMANENTLY;
            case 302 -> FOUND;
            case 303 -> SEE_OTHER;
            case 304 -> NOT_MODIFIED;
            case 305 -> USE_PROXY;
            case 307 -> TEMPORARY_REDIRECT;
            case 308 -> PERMANENT_REDIRECT;
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 402 -> PAYMENT_REQUIRED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 407 -> PROXY_AUTHENTICATION_REQUIRED;
            case 408 -> REQUEST_TIMEOUT;
            case 409 -> CONFLICT;
            case 410 -> GONE;
            case 411 -> LENGTH_REQUIRED;
            case 412 -> PRECONDITION_FAILED;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 414 -> URI_TOO_LONG;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 416 -> RANGE_NOT_SATISFIABLE;
            case 417 -> EXPECTATION_FAILED;
            case 418 -> IM_A_TEAPOT;
            case 421 -> MISDIRECTED_REQUEST;
            case 422 -> UNPROCESSABLE_ENTITY;
            case 423 -> LOCKED;
            case 424 -> FAILED_DEPENDENCY;
            case 425 -> TOO_EARLY;
            case 426 -> UPGRADE_REQUIRED;
            case 428 -> PRECONDITION_REQUIRED;
            case 429 -> TOO_MANY_REQUESTS;
            case 431 -> REQUEST_HEADER_FIELDS_TOO_LARGE;
            case 451 -> UNAVAILABLE_FOR_LEGAL_REASONS;
            case 500 -> INTERNAL_SERVER_ERROR;
            case 501 -> NOT_IMPLEMENTED;
            case 502 -> BAD_GATEWAY;
            case 503 -> SERVICE_UNAVAILABLE;
            case 504 -> GATEWAY_TIMEOUT;
            case 505 -> HTTP_VERSION_NOT_SUPPORTED;
            case 506 -> VARIANT_ALSO_NEGOTIATES;
            case 507 -> INSUFFICIENT_STORAGE;
            case 508 -> LOOP_DETECTED;
            case 510 -> NOT_EXTENDED;
            case 511 -> NETWORK_AUTHENTICATION_REQUIRED;
            default -> {
                // Dynamic status codes that we don't explicitly enumerate
                if (code >= 100 && code < 200) yield INFORMATIONAL;
                else if (code >= 200 && code < 300) yield SUCCESS; // treated as success
                else if (code >= 300 && code < 400) yield REDIRECT;
                else if (code >= 400 && code < 500) yield CLIENT_ERROR;
                else if (code >= 500 && code < 600) yield SERVER_ERROR;
                else yield HttpStatus.UNKNOWN;
            }
        };
    }

    public enum StatusCategory {
        INFORMATIONAL,
        SUCCESS,
        REDIRECT,
        CLIENT_ERROR,
        SERVER_ERROR,
        NETWORK_ERROR
    }
}
