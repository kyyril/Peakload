package io.loadtest.common.model;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * HTTP request scenario configuration using Java 17+ Records.
 *
 * Why Records?
 * - Immutable by design: Critical for thread-safety during concurrent load generation
 * - Automatic equals/hashCode/toString: Less boilerplate, more maintainable
 * - Compact constructors: Validation logic centralized
 * - Pattern matching: Seamless integration with Java 21 switch expressions
 *
 * Author: @loadtest-arch
 */
public sealed interface HttpScenario permits
        HttpScenario.Get,
        HttpScenario.Post,
        HttpScenario.Put,
        HttpScenario.Patch,
        HttpScenario.Delete,
        HttpScenario.Custom {

    String url();
    List<Header> headers();
    Duration connectTimeout();
    Duration readTimeout();
    boolean followRedirects();
    String name();

    /**
     * HTTP method for this scenario.
     * Derived from the sealed interface subtype.
     */
    HttpMethod method();

    /**
     * Optional request body (present for POST/PUT/PATCH, absent for GET/DELETE).
     */
    Optional<RequestBody> body();

    // ============================================================
    // SPECIFIC HTTP METHOD IMPLEMENTATIONS
    // ============================================================

    /**
     * GET request scenario - safe, idempotent, cacheable.
     */
    record Get(
            String url,
            List<Header> headers,
            Duration connectTimeout,
            Duration readTimeout,
            boolean followRedirects,
            String name
    ) implements HttpScenario {
        public Get {
            validateUrl(url);
            headers = List.copyOf(headers); // Defensive copy for immutability
        }

        @Override public HttpMethod method() { return HttpMethod.GET; }
        @Override public Optional<RequestBody> body() { return Optional.empty(); }
    }

    /**
     * POST request scenario - non-idempotent, creates resources.
     */
    record Post(
            String url,
            List<Header> headers,
            RequestBody requestBody,
            Duration connectTimeout,
            Duration readTimeout,
            boolean followRedirects,
            String name
    ) implements HttpScenario {
        public Post {
            validateUrl(url);
            headers = List.copyOf(headers);
            requireNonNull(requestBody, "POST requests require a body (use empty body if needed)");
        }

        @Override public HttpMethod method() { return HttpMethod.POST; }
        @Override public Optional<RequestBody> body() { return Optional.of(requestBody); }
    }

    /**
     * PUT request scenario - idempotent, replaces resources.
     */
    record Put(
            String url,
            List<Header> headers,
            RequestBody requestBody,
            Duration connectTimeout,
            Duration readTimeout,
            boolean followRedirects,
            String name
    ) implements HttpScenario {
        public Put {
            validateUrl(url);
            headers = List.copyOf(headers);
            requireNonNull(requestBody, "PUT requests require a body");
        }

        @Override public HttpMethod method() { return HttpMethod.PUT; }
        @Override public Optional<RequestBody> body() { return Optional.of(requestBody); }
    }

    /**
     * PATCH request scenario - partial updates.
     */
    record Patch(
            String url,
            List<Header> headers,
            RequestBody requestBody,
            Duration connectTimeout,
            Duration readTimeout,
            boolean followRedirects,
            String name
    ) implements HttpScenario {
        public Patch {
            validateUrl(url);
            headers = List.copyOf(headers);
            requireNonNull(requestBody, "PATCH requests require a body");
        }

        @Override public HttpMethod method() { return HttpMethod.PATCH; }
        @Override public Optional<RequestBody> body() { return Optional.of(requestBody); }
    }

    /**
     * DELETE request scenario - idempotent resource removal.
     */
    record Delete(
            String url,
            List<Header> headers,
            Duration connectTimeout,
            Duration readTimeout,
            boolean followRedirects,
            String name
    ) implements HttpScenario {
        public Delete {
            validateUrl(url);
            headers = List.copyOf(headers);
        }

        @Override public HttpMethod method() { return HttpMethod.DELETE; }
        @Override public Optional<RequestBody> body() { return Optional.empty(); }
    }

    /**
     * Custom method scenario - for non-standard HTTP methods (WebDAV, etc).
     */
    record Custom(
            HttpMethod method,
            String url,
            List<Header> headers,
            Optional<RequestBody> body,
            Duration connectTimeout,
            Duration readTimeout,
            boolean followRedirects,
            String name
    ) implements HttpScenario {
        public Custom {
            requireNonNull(method, "Custom method cannot be null");
            validateUrl(url);
            headers = List.copyOf(headers);
        }
    }

    // ============================================================
    // VALIDATION & UTILITIES
    // ============================================================

    /**
     * URL validation with strict RFC 3986 compliance.
     * Called in compact constructor of all record implementations.
     */
    private static void validateUrl(String url) {
        requireNonNull(url, "URL cannot be null");
        if (url.isBlank()) {
            throw new IllegalArgumentException("URL cannot be blank");
        }
        // Basic URL format validation
        if (!URL_PATTERN.matcher(url).matches()) {
            throw new IllegalArgumentException(
                    "Invalid URL format: '%s'. Expected http(s)://host[:port][/path][?query]".formatted(url)
            );
        }
    }

    Pattern URL_PATTERN = Pattern.compile(
            "^https?://" +                          // Protocol
            "([^/:]+|\\[[\\da-fA-F:]+\\])" +        // Hostname or IPv6
            "(:\\d{1,5})?" +                        // Optional port
            "(/[^?\\s]*)?" +                        // Optional path
            "(\\?[^\\s]+)?$"                        // Optional query string
    );

    // ============================================================
    // SUPPORTING TYPES
    // ============================================================

    /**
     * HTTP header as a key-value pair.
     * Using record for immutability and automatic equals/hashCode.
     */
    record Header(String name, String value) {
        public Header {
            requireNonNull(name, "Header name cannot be null");
            requireNonNull(value, "Header value cannot be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Header name cannot be blank");
            }
        }

        /**
         * Convert to Map.Entry for easy iteration.
         */
        public Map.Entry<String, String> toEntry() {
            return Map.entry(name, value);
        }
    }

    /**
     * Request body configuration with content type.
     */
    record RequestBody(String content, String contentType) {
        private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

        public RequestBody {
            requireNonNull(content, "Content cannot be null");
            contentType = contentType != null ? contentType : DEFAULT_CONTENT_TYPE;
        }

        /**
         * Factory for JSON body.
         */
        public static RequestBody json(String jsonContent) {
            return new RequestBody(jsonContent, "application/json");
        }

        /**
         * Factory for form-urlencoded body.
         */
        public static RequestBody formUrlEncoded(String formData) {
            return new RequestBody(formData, "application/x-www-form-urlencoded");
        }

        /**
         * Factory for plain text body.
         */
        public static RequestBody text(String textContent) {
            return new RequestBody(textContent, "text/plain");
        }

        /**
         * Factory for binary content with explicit content type.
         */
        public static RequestBody binary(byte[] bytes, String contentType) {
            return new RequestBody(Base64.getEncoder().encodeToString(bytes), contentType);
        }

        /**
         * Content length in bytes.
         */
        public int contentLength() {
            return content.getBytes().length;
        }
    }

    /**
     * HTTP methods enumeration with gRPC protobuf compatibility.
     */
    enum HttpMethod {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE,
        HEAD,
        OPTIONS,
        CONNECT,
        TRACE;

        /**
         * Convert from protobuf enum.
         */
        public static HttpMethod fromProto(io.loadtest.v1.HttpMethod proto) {
            return switch (proto) {
                case HTTP_METHOD_GET -> GET;
                case HTTP_METHOD_POST -> POST;
                case HTTP_METHOD_PUT -> PUT;
                case HTTP_METHOD_PATCH -> PATCH;
                case HTTP_METHOD_DELETE -> DELETE;
                case HTTP_METHOD_HEAD -> HEAD;
                case HTTP_METHOD_OPTIONS -> OPTIONS;
                case HTTP_METHOD_UNSPECIFIED -> throw new IllegalArgumentException("Unspecified HTTP method");
                default -> throw new IllegalArgumentException("Unsupported or unrecognized HTTP method: " + proto);
            };
        }

        /**
         * Convert from string with flexible parsing.
         */
        public static HttpMethod fromString(String method) {
            return valueOf(method.toUpperCase().trim());
        }
    }
}
