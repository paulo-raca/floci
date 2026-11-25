package io.floci.aws.services.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.marketplace.model.MarketplaceEntitlementRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class MarketplaceEntitlementService implements Resettable {
    // The regions AWS publishes an Entitlement endpoint in, across partitions (botocore endpoints.json).
    private static final Set<String> REGIONS = Set.of(
            "us-east-1", "cn-northwest-1", "eusc-de-east-1"); // partition-literal: AWS's endpoint regions
    private static final Set<String> FILTER_KEYS = Set.of(
            "CUSTOMER_IDENTIFIER", "DIMENSION", "CUSTOMER_AWS_ACCOUNT_ID", "LICENSE_ARN");

    private final ObjectMapper mapper;
    private final AccountAwareStorageBackend<JsonNode> entitlements;

    @Inject
    public MarketplaceEntitlementService(StorageFactory factory, ObjectMapper mapper) {
        this(mapper, factory.create("marketplace", "marketplace-entitlements.json",
                new TypeReference<Map<String, JsonNode>>() {}));
    }

    MarketplaceEntitlementService(ObjectMapper mapper, AccountAwareStorageBackend<JsonNode> entitlements) {
        this.mapper = mapper;
        this.entitlements = entitlements;
    }

    ObjectNode getEntitlements(JsonNode request, String region) {
        if (region != null && !region.isBlank() && !REGIONS.contains(region)) {
            throw new AwsException("InvalidParameterException",
                    "AWS Marketplace Entitlement Service is available only in "
                            + "us-east-1, cn-northwest-1, " // partition-literal: the served regions
                            + "and eusc-de-east-1.", 400); // partition-literal: the served regions
        }
        String productCode = requireText(request, "ProductCode", 1, 255);
        JsonNode filter = request == null ? null : request.get("Filter");
        validateFilter(filter);

        int maxResults = 25;
        if (request != null && request.has("MaxResults")) {
            if (!request.get("MaxResults").isIntegralNumber()
                    || !request.get("MaxResults").canConvertToInt()) {
                throw invalid("MaxResults must be an integer between 1 and 25.");
            }
            maxResults = request.get("MaxResults").asInt();
            if (maxResults < 1 || maxResults > 25) {
                throw invalid("MaxResults must be between 1 and 25.");
            }
        }
        int offset = parseToken(request == null ? null : request.path("NextToken").asText(null));

        List<StoredEntitlement> matches = new ArrayList<>();
        for (JsonNode value : entitlements.scan(key -> key.startsWith(productCode + "/"))) {
            matches.add(new StoredEntitlement(value, MarketplaceEntitlementRecord.fromJson(value)));
        }
        matches.removeIf(value -> !matchesFilter(value.record(), filter));
        matches.sort(Comparator.comparing(value -> sortKey(value.record())));
        if (offset > matches.size()) {
            throw invalid("NextToken is invalid.");
        }
        int end = Math.min(matches.size(), offset + maxResults);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode out = response.putArray("Entitlements");
        matches.subList(offset, end).forEach(value -> out.add(value.raw().deepCopy()));
        if (end < matches.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return response;
    }

    /**
     * Stores AWS-shaped entitlement records for the calling account. AWS has no write API for
     * entitlements (the buyer's purchase creates them), so this is a Floci extension that lets a
     * test stand in for that purchase. Every record is validated before any is stored.
     */
    synchronized List<JsonNode> addEntitlements(JsonNode body) {
        List<JsonNode> records = new ArrayList<>();
        if (body != null && body.isArray()) {
            body.forEach(records::add);
        } else if (body != null && !body.isNull()) {
            records.add(body);
        }
        if (records.isEmpty()) {
            throw invalid("At least one entitlement is required.");
        }
        for (JsonNode record : records) {
            validateEntitlement(record);
        }
        List<JsonNode> stored = new ArrayList<>();
        for (JsonNode record : records) {
            String key = record.path("ProductCode").asText() + "/" + UUID.randomUUID();
            entitlements.put(key, record.deepCopy());
            stored.add(record.deepCopy());
        }
        return stored;
    }

    /** The calling account's entitlements, for one product or (null) for all of them. */
    List<JsonNode> listEntitlements(String productCode) {
        return entitlements.scan(key -> productCode == null || key.startsWith(productCode + "/"));
    }

    /** Removes the calling account's entitlements, for one product or (null) for all of them. */
    synchronized void deleteEntitlements(String productCode) {
        for (String key : entitlements.keys()) {
            if (productCode == null || key.startsWith(productCode + "/")) {
                entitlements.delete(key);
            }
        }
    }

    private void validateEntitlement(JsonNode record) {
        if (record == null || !record.isObject()) {
            throw invalid("An entitlement must be an object.");
        }
        requireText(record, "ProductCode", 1, 255);
        if (!record.hasNonNull("CustomerIdentifier") && !record.hasNonNull("CustomerAWSAccountId")
                && !record.hasNonNull("LicenseArn")) {
            throw invalid("An entitlement needs a CustomerIdentifier, a CustomerAWSAccountId or a LicenseArn.");
        }
        for (String field : List.of("CustomerIdentifier", "CustomerAWSAccountId", "LicenseArn", "Dimension")) {
            if (record.has(field) && !record.get(field).isNull()
                    && (!record.get(field).isTextual() || record.get(field).asText().isBlank())) {
                throw invalid(field + " must be a non-empty string.");
            }
        }
        if (record.hasNonNull("ExpirationDate") && !record.get("ExpirationDate").isNumber()) {
            throw invalid("ExpirationDate must be a number of epoch seconds.");
        }
    }

    private void validateFilter(JsonNode filter) {
        if (filter == null || filter.isNull()) {
            return;
        }
        if (!filter.isObject()) {
            throw invalid("Filter must be an object.");
        }
        if (filter.has("CUSTOMER_IDENTIFIER") && filter.has("CUSTOMER_AWS_ACCOUNT_ID")) {
            throw invalid("CustomerIdentifier and CustomerAWSAccountId are mutually exclusive.");
        }
        filter.fields().forEachRemaining(entry -> {
            if (!FILTER_KEYS.contains(entry.getKey())) {
                throw invalid("Unsupported filter key: " + entry.getKey());
            }
            JsonNode values = entry.getValue();
            if (!values.isArray() || values.isEmpty()) {
                throw invalid("Filter values for " + entry.getKey() + " must be a non-empty list.");
            }
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw invalid("Filter values for " + entry.getKey() + " must be non-empty strings.");
                }
            }
        });
    }

    private boolean matchesFilter(MarketplaceEntitlementRecord entitlement, JsonNode filter) {
        if (filter == null || filter.isNull()) {
            return true;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = filter.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String actual = switch (entry.getKey()) {
                case "CUSTOMER_IDENTIFIER" -> entitlement.customerIdentifier();
                case "CUSTOMER_AWS_ACCOUNT_ID" -> entitlement.customerAwsAccountId();
                case "DIMENSION" -> entitlement.dimension();
                case "LICENSE_ARN" -> entitlement.licenseArn();
                default -> throw invalid("Unsupported filter key: " + entry.getKey());
            };
            boolean matched = false;
            for (JsonNode wanted : entry.getValue()) {
                if (wanted.asText().equals(actual)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private String sortKey(MarketplaceEntitlementRecord value) {
        return textOrEmpty(value.customerAwsAccountId()) + "|"
                + textOrEmpty(value.customerIdentifier()) + "|"
                + textOrEmpty(value.dimension()) + "|"
                + textOrEmpty(value.licenseArn());
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record StoredEntitlement(JsonNode raw, MarketplaceEntitlementRecord record) {
    }

    private static int parseToken(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(token);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalid("NextToken is invalid.");
        }
    }

    private static String requireText(JsonNode request, String field, int min, int max) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " is required.");
        }
        String text = value.asText();
        if (text.length() < min || text.length() > max) {
            throw invalid(field + " must be between " + min + " and " + max + " characters.");
        }
        return text;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    @Override
    public void clear() {
        entitlements.clear();
    }
}
