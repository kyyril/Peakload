package io.loadtest.common.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.loadtest.common.model.HttpScenario;
import io.loadtest.common.model.LoadProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.DateTimeException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Scenario configuration parser with Java 21 pattern matching.
 *
 * Design Philosophy:
 * - Fail fast: Validate all configuration at parse time, not runtime
 * - Idempotent: Parse the same config multiple times yields identical result
 * - Immutable outputs: All parsed objects are records or immutable collections
 *
 * Pattern Matching Usage:
 * Jackson JsonNode has many subtypes (ObjectNode, ArrayNode, TextNode, etc.).
 * Pattern matching eliminates instanceof + cast boilerplate, making dispatching readable.
 */
public final class ScenarioParser {

    private final ObjectMapper objectMapper;

    public ScenarioParser() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * Parse a scenario configuration from a file.
     */
    public TestScenarioDefinition parse(Path configFile) throws ParseException {
        try {
            return parse(Files.readString(configFile));
        } catch (IOException e) {
            throw new ParseException("Failed to read config file: " + configFile, e);
        }
    }

    /**
     * Parse a scenario configuration from an input stream.
     */
    public TestScenarioDefinition parse(InputStream input) throws ParseException {
        try {
            return parse(new String(input.readAllBytes()));
        } catch (IOException e) {
            throw new ParseException("Failed to read input stream", e);
        }
    }

    /**
     * Parse a scenario configuration from a JSON string.
     */
    public TestScenarioDefinition parse(String json) throws ParseException {
        try {
            JsonNode root = objectMapper.readTree(json);
            return parseTestScenario(root);
        } catch (IOException e) {
            throw new ParseException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private TestScenarioDefinition parseTestScenario(JsonNode root) throws ParseException {
        requireNonNull(root, "Root node cannot be null");

        String scenarioId = parseStringField(root, "id", UUID.randomUUID()::toString);
        String scenarioName = parseStringField(root, "name", () -> "unnamed-scenario");

        // Parse HTTP scenarios (required)
        JsonNode scenariosNode = requiredField(root, "scenarios");
        if (!scenariosNode.isArray()) {
            throw new ParseException("'scenarios' field must be an array");
        }

        List<HttpScenario> httpScenarios = new ArrayList<>();
        for (JsonNode scenarioNode : scenariosNode) {
            httpScenarios.add(parseHttpScenario(scenarioNode));
        }

        if (httpScenarios.isEmpty()) {
            throw new ParseException("At least one HTTP scenario is required");
        }

        // Parse load profile (required)
        JsonNode loadProfileNode = requiredField(root, "loadProfile");
        LoadProfile loadProfile = parseLoadProfile(loadProfileNode);

        // Parse tags (optional)
        Map<String, String> tags = parseTagsField(root, "tags");

        return new TestScenarioDefinition(
                scenarioId,
                scenarioName,
                httpScenarios,
                loadProfile,
                tags,
                System.currentTimeMillis()
        );
    }
    /**
     * Parse HTTP scenario configuration.
     *
     * Pattern Matching Showcase:
     * The 'method' node type determines which scenario type to create.
     * Using pattern matching, we can route to the correct parser cleanly.
     */
    private HttpScenario parseHttpScenario(JsonNode node) throws ParseException {
        String url = parseStringField(node, "url");
        String methodStr = parseStringField(node, "method", () -> "GET").toUpperCase();

        List<io.loadtest.common.model.HttpScenario.Header> headers = parseHeaders(node);

        // Default connection configuration
        Duration connectTimeout = parseDurationField(node, "connectTimeout", () -> Duration.ofSeconds(5));
        Duration readTimeout = parseDurationField(node, "readTimeout", () -> Duration.ofSeconds(30));
        boolean followRedirects = parseBooleanField(node, "followRedirects", () -> true);
        String name = parseStringField(node, "name", () -> "unnamed");

        return switch (methodStr) {
            case "GET" -> new HttpScenario.Get(url, headers, connectTimeout, readTimeout, followRedirects, name);
            case "DELETE" -> new HttpScenario.Delete(url, headers, connectTimeout, readTimeout, followRedirects, name);
            case "POST" -> {
                HttpScenario.RequestBody body = parseRequiredBody(node);
                yield new HttpScenario.Post(url, headers, body, connectTimeout, readTimeout, followRedirects, name);
            }
            case "PUT" -> {
                HttpScenario.RequestBody body = parseRequiredBody(node);
                yield new HttpScenario.Put(url, headers, body, connectTimeout, readTimeout, followRedirects, name);
            }
            case "PATCH" -> {
                io.loadtest.common.model.HttpScenario.RequestBody body = parseRequiredBody(node);
                yield new HttpScenario.Patch(url, headers, body, connectTimeout, readTimeout, followRedirects, name);
            }
            case "HEAD", "OPTIONS", "CONNECT", "TRACE" -> {
                HttpScenario.HttpMethod method = HttpScenario.HttpMethod.fromString(methodStr);
                yield new HttpScenario.Custom(method, url, headers, Optional.empty(), connectTimeout, readTimeout, followRedirects, name);
            }
            default -> throw new ParseException("Unsupported HTTP method: " + methodStr);
        };
    }

    /**
     * Parse request body with format auto-detection.
     */
    private HttpScenario.RequestBody parseRequiredBody(JsonNode node) throws ParseException {
        JsonNode bodyNode = node.get("body");
        if (bodyNode == null || bodyNode.isNull()) {
            throw new ParseException("'body' field is required for POST/PUT/PATCH methods");
        }

        return parseBody(bodyNode, node.get("contentType"));
    }

    /**
     * Parse body with pattern matching on node type.
     *
     * Pattern Matching for JsonNode (Jackson polymorphic dispatch):
     * - TextNode: Plain text body (treated as raw content)
     * - ObjectNode: JSON body (serialized to string)
     * - ArrayNode: JSON body (serialized to string)
     * - BinaryNode: Base64 encoded raw data
     */
    private HttpScenario.RequestBody parseBody(JsonNode bodyNode, JsonNode contentTypeNode) throws ParseException {
        String contentType = contentTypeNode != null ? contentTypeNode.asText() : null;

        // Pattern matching on body node type
        return switch (bodyNode) {
            case com.fasterxml.jackson.databind.node.TextNode t -> {
                String text = t.asText();
                // Auto-detect content type if not specified
                yield contentType != null
                        ? new HttpScenario.RequestBody(text, contentType)
                        : detectContentType(text);
            }
            case com.fasterxml.jackson.databind.node.ObjectNode o -> {
                // JSON object body
                String json = o.toString();
                yield HttpScenario.RequestBody.json(json);
            }
            case com.fasterxml.jackson.databind.node.ArrayNode a -> {
                // JSON array body
                String json = a.toString();
                yield HttpScenario.RequestBody.json(json);
            }
            case com.fasterxml.jackson.databind.node.BinaryNode b -> {
                String base64 = Base64.getEncoder().encodeToString(b.binaryValue());
                yield contentType != null
                        ? new HttpScenario.RequestBody(base64, contentType)
                        : HttpScenario.RequestBody.binary(b.binaryValue(), "application/octet-stream");
            }
            case com.fasterxml.jackson.databind.node.NullNode n -> {
                // Empty body
                yield new HttpScenario.RequestBody("", "application/octet-stream");
            }
            default -> throw new ParseException("Unsupported body type: " + bodyNode.getNodeType());
        };
    }

    /**
     * Detect content type from content heuristics.
     */
    private HttpScenario.RequestBody detectContentType(String content) throws ParseException {
        if (content.isBlank()) {
            return new HttpScenario.RequestBody(content, "text/plain");
        }

        String trimmed = content.trim();

        // JSON detection: starts with { or [
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return HttpScenario.RequestBody.json(content);
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return HttpScenario.RequestBody.json(content);
        }

        // XML detection: starts with <
        if (trimmed.startsWith("<")) {
            return new HttpScenario.RequestBody(content, "application/xml");
        }

        // Form-urlencoded detection: contains key=value pairs with &
        if (trimmed.contains("=") && trimmed.contains("&")) {
            return HttpScenario.RequestBody.formUrlEncoded(content);
        }

        // Default to JSON for objects
        if (trimmed.contains(":") && trimmed.contains("\"")) {
            return HttpScenario.RequestBody.json(content);
        }

        return new HttpScenario.RequestBody(content, "text/plain");
    }

    /**
     * Parse load profile configuration.
     *
     * Profile type dispatch routing using pattern matching on string.
     */
    private LoadProfile parseLoadProfile(JsonNode node) throws ParseException {
        String type = parseStringField(node, "type", () -> "constant");
        long targetRps = parseLongField(node, "targetRps", () -> 100L);
        int maxConcurrency = parseIntField(node, "maxConcurrency", () -> 1000);

        return switch (type.toLowerCase()) {
            case "constant" -> new LoadProfile.Constant(
                    targetRps,
                    parseDurationField(node, "duration", () -> Duration.ofMinutes(1)),
                    maxConcurrency
            );

            case "rampup", "ramp-up", "ramp_up" -> new LoadProfile.RampUp(
                    targetRps,
                    parseDurationField(node, "rampUpDuration", () -> Duration.ofSeconds(30)),
                    parseDurationField(node, "holdDuration", () -> Duration.ofMinutes(1)),
                    parseDurationField(node, "rampDownDuration", () -> Duration.ofSeconds(15)),
                    maxConcurrency
            );

            case "spike", "burst" -> new LoadProfile.Spike(
                    targetRps,
                    parseDurationField(node, "spikeDuration", () -> Duration.ofSeconds(10)),
                    maxConcurrency,
                    parseBooleanField(node, "synchronizedStart", () -> true)
            );

            case "stepup", "step-up", "step_up" -> new LoadProfile.StepUp(
                    parseLongField(node, "initialRps", () -> 100L),
                    parseLongField(node, "rpsIncrement", () -> 100L),
                    parseDurationField(node, "stepDuration", () -> Duration.ofSeconds(30)),
                    parseIntField(node, "totalSteps", () -> 5),
                    maxConcurrency
            );

            default -> throw new ParseException("Unknown load profile type: " + type);
        };
    }

    /**
     * Parse headers collection.
     */
    private List<io.loadtest.common.model.HttpScenario.Header> parseHeaders(JsonNode node) {
        JsonNode headersNode = node.get("headers");
        if (headersNode == null || !headersNode.isObject()) {
            return List.of();
        }

        List<io.loadtest.common.model.HttpScenario.Header> headers = new ArrayList<>();
        headersNode.fields().forEachRemaining(entry -> {
            headers.add(new io.loadtest.common.model.HttpScenario.Header(entry.getKey(), entry.getValue().asText()));
        });
        return Collections.unmodifiableList(headers);
    }

    /**
     * Parse tags collection.
     */
    private Map<String, String> parseTagsField(JsonNode root, String fieldName) {
        JsonNode tagsNode = root.get(fieldName);
        if (tagsNode == null || !tagsNode.isObject()) {
            return Map.of();
        }

        Map<String, String> tags = new HashMap<>();
        tagsNode.fields().forEachRemaining(entry -> {
            tags.put(entry.getKey(), entry.getValue().asText());
        });
        return Collections.unmodifiableMap(tags);
    }

    private JsonNode requiredField(JsonNode node, String fieldName) throws ParseException {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            throw new ParseException("Required field '" + fieldName + "' is missing");
        }
        return field;
    }

    private String parseStringField(JsonNode node, String fieldName) throws ParseException {
        JsonNode field = requiredField(node, fieldName);
        if (!field.isTextual()) {
            throw new ParseException("Field '" + fieldName + "' must be a string");
        }
        return field.asText();
    }

    private String parseStringField(JsonNode node, String fieldName, Supplier<String> defaultValue) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return defaultValue.get();
        }
        return field.asText();
    }

    private long parseLongField(JsonNode node, String fieldName, Supplier<Long> defaultValue) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isNumber()) {
            return defaultValue.get();
        }
        return field.asLong();
    }

    private int parseIntField(JsonNode node, String fieldName, Supplier<Integer> defaultValue) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isNumber()) {
            return defaultValue.get();
        }
        return field.asInt();
    }

    private boolean parseBooleanField(JsonNode node, String fieldName, Supplier<Boolean> defaultValue) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isBoolean()) {
            return defaultValue.get();
        }
        return field.asBoolean();
    }

    /**
     * Parse duration field with multiple formats.
     *
     * Supported formats:
     * - Number (milliseconds): 5000 → 5 seconds
     * - String (ISO-8601): "PT30S" → 30 seconds
     * - String (suffix): "30s" → 30 seconds, "5m" → 5 minutes
     */
    private Duration parseDurationField(JsonNode node, String fieldName, Supplier<Duration> defaultValue) throws ParseException {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return defaultValue.get();
        }

        try {
            return switch (field) {
                case com.fasterxml.jackson.databind.node.NumericNode n -> Duration.ofMillis(n.asLong());
                case com.fasterxml.jackson.databind.node.TextNode t -> parseDurationString(t.asText());
                default -> throw new ParseException("Field '" + fieldName + "' must be number or string");
            };
        } catch (DateTimeException e) {
            throw new ParseException("Invalid duration format for field '" + fieldName + "': " + field.asText(), e);
        }
    }

    /**
     * Parse duration string with suffix notation.
     */
    private Duration parseDurationString(String value) {
        String trimmed = value.trim();

        // ISO-8601 format
        if (trimmed.startsWith("PT")) {
            return Duration.parse(trimmed);
        }

        // Suffix notation: 30s, 5m, 2h
        if (trimmed.matches("^\\d+[smh]$")) {
            long amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
            return switch (trimmed.charAt(trimmed.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                default -> throw new IllegalArgumentException("Invalid duration suffix: " + trimmed);
            };
        }

        // Plain number (milliseconds)
        return Duration.ofMillis(Long.parseLong(trimmed));
    }

    /**
     * Parse exception with context.
     */
    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }

        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    
    // RESULT TYPE
    

    /**
     * Immutable test scenario definition.
     */
    public record TestScenarioDefinition(
            String scenarioId,
            String scenarioName,
            List<HttpScenario> httpScenarios,
            LoadProfile loadProfile,
            Map<String, String> tags,
            long createdTimestampMs
    ) {
        public TestScenarioDefinition {
            httpScenarios = List.copyOf(httpScenarios);
            tags = Map.copyOf(tags);
        }
    }
}
