package io.floci.aws.services.marketplace;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class MarketplaceSeedIntegrationTest {
    private static final String CUSTOMER = """
            {"ProductCode":"prod-seed","CustomerAWSAccountId":"123456789012","CustomerIdentifier":"cust-seed"}
            """;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        given().delete("/_aws/marketplace/customers").then().statusCode(204);
        given().delete("/_aws/marketplace/entitlements").then().statusCode(204);
    }

    @Test
    void aRegisteredTokenResolvesToItsCustomerInAnyRegion() {
        given().contentType("application/json").body(CUSTOMER).put("/_aws/marketplace/customers/token-seed")
                .then().statusCode(200).body("CustomerIdentifier", equalTo("cust-seed"));

        for (String region : new String[] {"us-east-1", "eu-west-1"}) {
            resolve("token-seed", region).statusCode(200)
                    .body("CustomerAWSAccountId", equalTo("123456789012"))
                    .body("CustomerIdentifier", equalTo("cust-seed"))
                    .body("ProductCode", equalTo("prod-seed"));
        }
    }

    @Test
    void anUnregisteredTokenStillResolvesToAGeneratedCustomer() {
        resolve("token-unregistered", "us-east-1").statusCode(200)
                .body("ProductCode", equalTo("local-product"))
                .body("CustomerIdentifier", nullValue());
    }

    @Test
    void aCustomerWithoutAnAccountIsRejected() {
        given().contentType("application/json").body("{\"ProductCode\":\"prod-seed\"}")
                .put("/_aws/marketplace/customers/token-bad")
                .then().statusCode(400).body("__type", containsString("ValidationException"));
    }

    @Test
    void forgettingTheCustomersRestoresTheGeneratedOnes() {
        given().contentType("application/json").body(CUSTOMER).put("/_aws/marketplace/customers/token-seed")
                .then().statusCode(200);
        given().delete("/_aws/marketplace/customers").then().statusCode(204);

        resolve("token-seed", "us-east-1").statusCode(200).body("ProductCode", equalTo("local-product"));
    }

    @Test
    void aStoredEntitlementIsReturnedByGetEntitlements() {
        given().contentType("application/json").body("""
                {"ProductCode":"prod-seed","CustomerIdentifier":"cust-seed","Dimension":"users",
                 "ExpirationDate":1640995384,"Value":{"IntegerValue":10}}
                """).post("/_aws/marketplace/entitlements")
                .then().statusCode(200).body("Entitlements", hasSize(1));

        entitlements("prod-seed", "cust-seed").statusCode(200)
                .body("Entitlements", hasSize(1))
                .body("Entitlements[0].Dimension", equalTo("users"))
                .body("Entitlements[0].ExpirationDate", equalTo(1640995384));
        entitlements("prod-seed", "someone-else").statusCode(200).body("Entitlements", empty());
    }

    @Test
    void aListOfEntitlementsIsStoredAndCanBeListedAndRemovedByProduct() {
        given().contentType("application/json").body("""
                [{"ProductCode":"prod-a","CustomerAWSAccountId":"123456789012"},
                 {"ProductCode":"prod-b","CustomerAWSAccountId":"123456789012"}]
                """).post("/_aws/marketplace/entitlements").then().statusCode(200).body("Entitlements", hasSize(2));

        given().get("/_aws/marketplace/entitlements").then().statusCode(200).body("Entitlements", hasSize(2));
        given().queryParam("ProductCode", "prod-a").delete("/_aws/marketplace/entitlements")
                .then().statusCode(204);
        given().get("/_aws/marketplace/entitlements").then().statusCode(200)
                .body("Entitlements", hasSize(1))
                .body("Entitlements[0].ProductCode", not(equalTo("prod-a")));
    }

    @Test
    void anEntitlementNamingNoCustomerIsRejectedAndNothingIsStored() {
        given().contentType("application/json").body("""
                [{"ProductCode":"prod-a","CustomerAWSAccountId":"123456789012"},{"ProductCode":"prod-b"}]
                """).post("/_aws/marketplace/entitlements")
                .then().statusCode(400).body("__type", containsString("InvalidParameterException"));

        given().get("/_aws/marketplace/entitlements").then().statusCode(200).body("Entitlements", empty());
    }

    private static ValidatableResponse resolve(String token, String region) {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", auth(region))
                .header("X-Amz-Target", "AWSMPMeteringService.ResolveCustomer")
                .body("{\"RegistrationToken\":\"" + token + "\"}").post("/").then();
    }

    private static ValidatableResponse entitlements(String product, String customer) {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", auth("us-east-1"))
                .header("X-Amz-Target", "AWSMPEntitlementService.GetEntitlements")
                .body("{\"ProductCode\":\"" + product + "\",\"Filter\":{\"CUSTOMER_IDENTIFIER\":[\""
                        + customer + "\"]}}").post("/").then();
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/" + region + "/aws-marketplace/aws4_request";
    }
}
