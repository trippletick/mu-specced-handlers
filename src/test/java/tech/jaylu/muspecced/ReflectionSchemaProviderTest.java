package tech.jaylu.muspecced;

import io.muserver.openapi.SchemaObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionSchemaProviderTest {

    private final ReflectionSchemaProvider provider = new ReflectionSchemaProvider();

    record Money(BigDecimal amount, String currency) {}

    record Order(UUID id, LocalDate date, URI link, Set<String> labels, Map<String, Integer> quantities,
                 Optional<String> note, Money total, int[] scores) {}

    record TreeNode(String name, List<TreeNode> children) {}

    static class Base {
        public String publicField;
        private String hidden;
        private long version;

        public long getVersion() {
            return version;
        }
    }

    static class Child extends Base {
        private boolean active;
        private transient String ignored;

        public boolean isActive() {
            return active;
        }

        public String getDisplayName() {
            return "computed";
        }

        public static String getStatic() {
            return "no";
        }
    }

    @Test
    void simpleTypes() {
        assertType("string", null, String.class);
        assertType("integer", "int32", int.class);
        assertType("integer", "int64", Long.class);
        assertType("number", "double", double.class);
        assertType("number", null, BigDecimal.class);
        assertType("boolean", null, boolean.class);
        assertType("string", "uuid", UUID.class);
        assertType("string", "date", LocalDate.class);
        assertType("string", "uri", URI.class);
        assertType("string", "binary", byte[].class);
    }

    @Test
    void recordsAreDescribedByTheirComponents() {
        SchemaObject order = provider.schemaFor(Order.class);
        assertEquals("object", order.type());
        assertEquals("Order", order.title());
        assertEquals(Set.of("id", "date", "link", "labels", "quantities", "note", "total", "scores"), order.properties().keySet());

        SchemaObject labels = order.properties().get("labels");
        assertEquals("array", labels.type());
        assertEquals(true, labels.uniqueItems());
        assertEquals("string", labels.items().type());

        SchemaObject quantities = order.properties().get("quantities");
        assertEquals("object", quantities.type());
        assertEquals("integer", ((SchemaObject) quantities.additionalProperties()).type());

        assertEquals("string", order.properties().get("note").type());
        assertEquals(Set.of("amount", "currency"), order.properties().get("total").properties().keySet());
        assertEquals("integer", order.properties().get("scores").items().type());
    }

    @Test
    void classesAreDescribedByPublicFieldsAndGettersIncludingInheritedOnes() {
        SchemaObject child = provider.schemaFor(Child.class);
        assertEquals(Set.of("publicField", "version", "active", "displayName"), child.properties().keySet());
        assertEquals("boolean", child.properties().get("active").type());
        assertEquals("int64", child.properties().get("version").format());
    }

    @Test
    void genericTypesAreResolved() {
        SchemaObject page = provider.schemaFor(new TypeRef<SampleApi.Page<Money>>() {});
        SchemaObject items = page.properties().get("items");
        assertEquals("array", items.type());
        assertEquals("Money", items.items().title());

        SchemaObject list = provider.schemaFor(new TypeRef<List<Money>>() {});
        assertEquals("Money", list.items().title());
    }

    @Test
    void recursiveTypesStopAtTheRecursion() {
        SchemaObject tree = provider.schemaFor(TreeNode.class);
        SchemaObject child = tree.properties().get("children").items();
        assertEquals("object", child.type());
        assertNull(child.properties());
        assertTrue(child.description().contains("TreeNode"));
    }

    @Test
    void overridesReplaceGeneratedSchemasIncludingNestedOnes() {
        SchemaObject moneyAsString = schemaObject().withType("string").withDescription("e.g. 12.50 GBP").build();
        ReflectionSchemaProvider withOverride = new ReflectionSchemaProvider(Map.of(Money.class, moneyAsString));
        assertEquals("string", withOverride.schemaFor(Order.class).properties().get("total").type());
    }

    @Test
    void unknownJdkTypesArePlainObjects() {
        SchemaObject schema = provider.schemaFor(Thread.class);
        assertEquals("object", schema.type());
        assertNull(schema.properties());
    }

    private void assertType(String type, String format, Class<?> clazz) {
        SchemaObject schema = provider.schemaFor(clazz);
        assertEquals(type, schema.type(), clazz.getName());
        assertEquals(format, schema.format(), clazz.getName());
    }
}
