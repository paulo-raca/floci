package io.floci.aws.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Floci-only endpoints that stand in for what AWS Marketplace does when a buyer subscribes, so a
 * test of a SaaS seller can drive {@code ResolveCustomer} and {@code GetEntitlements}. AWS has no
 * API for either. Records belong to the account of the request's credentials, like every other
 * Marketplace record.
 *
 * <p>POST   /_aws/marketplace/entitlements: store one entitlement or a list, AWS-shaped
 * <p>GET    /_aws/marketplace/entitlements[?ProductCode=X]: list the stored ones
 * <p>DELETE /_aws/marketplace/entitlements[?ProductCode=X]: remove them
 * <p>PUT    /_aws/marketplace/customers/&lt;registrationToken&gt;: the customer the token resolves to
 * <p>DELETE /_aws/marketplace/customers: forget the registered customers
 */
@Path("/_aws/marketplace")
@Produces(MediaType.APPLICATION_JSON)
public class MarketplaceSeedController {

    private final MarketplaceEntitlementService entitlements;
    private final MarketplaceMeteringService metering;
    private final ObjectMapper objectMapper;

    @Inject
    public MarketplaceSeedController(MarketplaceEntitlementService entitlements,
                                     MarketplaceMeteringService metering, ObjectMapper objectMapper) {
        this.entitlements = entitlements;
        this.metering = metering;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/entitlements")
    @Consumes(MediaType.WILDCARD)
    public Response addEntitlements(String body) {
        ObjectNode response = objectMapper.createObjectNode();
        entitlements.addEntitlements(parse(body)).forEach(response.putArray("Entitlements")::add);
        return Response.ok(response).build();
    }

    @GET
    @Path("/entitlements")
    public Response listEntitlements(@QueryParam("ProductCode") String productCode) {
        ObjectNode response = objectMapper.createObjectNode();
        var list = response.putArray("Entitlements");
        entitlements.listEntitlements(productCode).forEach(list::add);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/entitlements")
    public Response deleteEntitlements(@QueryParam("ProductCode") String productCode) {
        entitlements.deleteEntitlements(productCode);
        return Response.noContent().build();
    }

    @PUT
    @Path("/customers/{registrationToken: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response registerCustomer(@PathParam("registrationToken") String registrationToken, String body) {
        return Response.ok(metering.registerCustomer(registrationToken, parse(body))).build();
    }

    @DELETE
    @Path("/customers")
    public Response deleteCustomers() {
        metering.deleteRegisteredCustomers();
        return Response.noContent().build();
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
    }
}
