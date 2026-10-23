package tech.jaylu.muspecced;

import io.muserver.RouteHandler;
import io.muserver.openapi.OperationObject;
import io.muserver.openapi.SchemaObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.muserver.Method.CONNECT;
import static io.muserver.Method.GET;
import static io.muserver.Method.POST;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the settings, and that documentation which does not match its route fails when the route is created
 */
class OpenApiRoutesTest {

    private static final RouteHandler noop = (req, resp, params) -> {};
    private final OpenApiRoutes routes = OpenApiRoutes.openApiRoutes();

    record Order(String id, Money total) {}

    record Money(BigDecimal amount, String currency) {}

    @Test
    void documentingAPathParamThatIsNotInTheTemplateFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            routes.route(GET, "/users/{id}", op -> op.pathParam("userId", long.class, "ID"), noop));
        assertTrue(e.getMessage().contains("'userId'"), e.getMessage());
        assertTrue(e.getMessage().contains("[id]"), e.getMessage());
    }

    @Test
    void undocumentedPathParamsAreAddedAsStrings() {
        OperationObject op = routes.route(GET, "/things/{name}", op2 -> {}, noop).apiOperations().get(0).operation();
        assertEquals("name", op.parameters().get(0).name());
        assertEquals("string", op.parameters().get(0).schema().type());
        assertNull(op.parameters().get(0).schema().pattern());
        assertEquals("OK", op.responses().httpStatusCodes().get("200").description(), "A default response is added");
    }

    @Test
    void parametersCannotBeDocumentedTwice() {
        assertThrows(IllegalArgumentException.class, () -> routes.route(GET, "/a",
            op -> op.queryParam("q", String.class, "one").requiredQueryParam("q", String.class, "two"), noop));
        assertThrows(IllegalArgumentException.class, () -> routes.route(GET, "/b",
            op -> op.queryParam("q", String.class, "one")
                .parameter(parameterObject().withName("q").withIn("query").withSchema(schemaObject().withType("string").build()).build()),
            noop));
    }

    @Test
    void aResponseHeaderNeedsADocumentedResponse() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> routes.route(POST, "/a",
            op -> op.responseHeader(201, "Location", String.class, "Where"), noop));
        assertTrue(e.getMessage().contains("response(201"), e.getMessage());
    }

    @Test
    void invalidInputFails() {
        assertThrows(IllegalArgumentException.class, () -> routes.route(GET, "/a", op -> op.response(42, "Nope"), noop));
        assertThrows(IllegalArgumentException.class, () -> routes.route(null, "/a", op -> {}, noop));
        assertThrows(IllegalArgumentException.class, () -> routes.route(CONNECT, "/a", op -> {}, noop));
        assertThrows(IllegalArgumentException.class, () -> routes.route(GET, "/a/{id", op -> {}, noop));
        assertThrows(IllegalArgumentException.class, () -> routes.route(GET, "/a/{id}/{id}", op -> {}, noop));
    }

    @Test
    void strictModeRequiresASummaryAndResponses() {
        OpenApiRoutes strict = routes.withStrictMode(true);
        IllegalArgumentException noSummary = assertThrows(IllegalArgumentException.class, () ->
            strict.route(GET, "/a", op -> op.response(200, "OK"), noop));
        assertTrue(noSummary.getMessage().contains("no summary"), noSummary.getMessage());
        IllegalArgumentException noResponses = assertThrows(IllegalArgumentException.class, () ->
            strict.operation(GET, "/a", op -> op.summary("A")));
        assertTrue(noResponses.getMessage().contains("no documented responses"), noResponses.getMessage());
        strict.route(GET, "/a", op -> op.summary("A").response(200, "OK"), noop);
        // The original settings are unchanged
        routes.route(GET, "/a", op -> {}, noop);
    }

    @Test
    void customSchemasApplyToTopLevelAndNestedTypesWithoutChangingTheOriginalSettings() {
        SchemaObject money = schemaObject().withType("string").withDescription("e.g. 12.50 GBP").build();
        OpenApiRoutes withMoney = routes.withSchema(Money.class, money);

        assertSame(money, withMoney.schemas().schemaFor(Money.class));
        assertEquals("string", withMoney.schemas().schemaFor(Order.class).properties().get("total").type());
        assertEquals("object", routes.schemas().schemaFor(Order.class).properties().get("total").type());

        OperationObject op = withMoney.operation(GET, "/total", o -> o.response(200, Money.class, "The total")).operation();
        assertSame(money, op.responses().httpStatusCodes().get("200").content().get("application/json").schema());
    }

    @Test
    void aCustomSchemaProviderCanBeUsed() {
        SchemaObject fixed = schemaObject().withType("string").withFormat("custom").build();
        OpenApiRoutes custom = routes.withSchemaProvider(type -> fixed)
            .withSchema(Money.class, schemaObject().withType("number").build());
        assertSame(fixed, custom.schemas().schemaFor(Order.class));
        assertEquals("number", custom.schemas().schemaFor(Money.class).type(), "Custom schemas still win at the top level");
    }

    @Test
    void customizersCanChangeTheOperation() {
        OperationObject op = routes.operation(GET, "/a", o -> o.summary("Before").customize(b -> b.withSummary("After"))).operation();
        assertEquals("After", op.summary());
    }

    @Test
    void explicitOperationIdsAreKept() {
        assertEquals("getA", routes.operation(GET, "/a", o -> o.operationId("getA")).operation().operationId());
    }
}
