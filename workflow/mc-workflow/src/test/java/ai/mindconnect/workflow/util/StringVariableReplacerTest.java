package ai.mindconnect.workflow.util;

import ai.mindconnect.pathaccessor.PathAccessor;
import ai.mindconnect.workflow.execution.VariableScope;
import ai.mindconnect.workflow.util.testmodel.*;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StringVariableReplacer}.
 */
public class StringVariableReplacerTest {

    private StringVariableReplacer replacer;

    @BeforeEach
    void setUp() {
        replacer = new StringVariableReplacer();
    }

    @Test
    public void simpleVariableReplacement() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("question", "warum", null);

        String result = replacer.replaceVars("${question}", scope::getVariableValue);

        Assertions.assertThat(result).isEqualTo("warum");
    }

    @Test
    public void multipleVariablesInOneString() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("first", "Hello", null);
        scope.assignValue("second", "World", null);

        String result = replacer.replaceVars("${first} ${second}!", scope::getVariableValue);

        Assertions.assertThat(result).isEqualTo("Hello World!");
    }

    @Test
    public void unknownVariableIsLeftAsIs() {
        VariableScope scope = new VariableScope(null, "test");

        String result = replacer.replaceVars("${unknown}", scope::getVariableValue);

        Assertions.assertThat(result).isEqualTo("${unknown}");
    }

    @Test
    public void dotPathResolutionOnMap() {
        VariableScope scope = new VariableScope(null, "test");
        Map<String, Object> user = Map.of("name", "Alice", "age", "30");
        scope.assignValue("user", user, null);

        String name = replacer.replaceVars("${user.name}", scope::getVariableValue);
        String age = replacer.replaceVars("${user.age}", scope::getVariableValue);

        Assertions.assertThat(name).isEqualTo("Alice");
        Assertions.assertThat(age).isEqualTo("30");
    }

    @Test
    public void dotPathOnNestedMap() {
        VariableScope scope = new VariableScope(null, "test");
        Map<String, Object> address = Map.of("city", "Berlin");
        Map<String, Object> user = Map.of("address", address);
        scope.assignValue("user", user, null);

        String result = replacer.replaceVars("${user.address.city}", scope::getVariableValue);

        Assertions.assertThat(result).isEqualTo("Berlin");
    }

    @Test
    public void resolveVarsReturnsMap() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("a", "1", null);
        scope.assignValue("b", "2", null);

        Map<String, String> resolved = replacer.resolveVars(
                "sum of ${a} and ${b}", scope::getVariableValue);

        Assertions.assertThat(resolved).containsEntry("a", "1").containsEntry("b", "2");
    }

    // -----------------------------------------------------------------------
    // BeanPathAccessor
    // -----------------------------------------------------------------------

    @Test
    public void beanPathAccessorReadsTopLevelProperty() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("person", new Person("Alice", 30, new Address("Berlin", "10115")), null);

        assertThat(replacer.replaceVars("${person.name}", scope::getVariableValue)).isEqualTo("Alice");
        assertThat(replacer.replaceVars("${person.age}", scope::getVariableValue)).isEqualTo("30");
    }

    @Test
    public void beanPathAccessorReadsNestedProperty() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("person", new Person("Alice", 30, new Address("Berlin", "10115")), null);

        assertThat(replacer.replaceVars("${person.address.city}", scope::getVariableValue)).isEqualTo("Berlin");
        assertThat(replacer.replaceVars("${person.address.zip}", scope::getVariableValue)).isEqualTo("10115");
    }

    @Test
    public void beanPathAccessorHandlesBooleanGetter() {

        class Flag {
            public boolean isActive() {
                return true;
            }
        }

        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("flag", new Flag(), null);

        assertThat(replacer.replaceVars("${flag.active}", scope::getVariableValue)).isEqualTo("true");
    }

    // -----------------------------------------------------------------------
    // RecordPathAccessor
    // -----------------------------------------------------------------------

    @Test
    public void recordPathAccessorReadsTopLevelComponent() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("loc", new Location("HQ", new Coords(52.5, 13.4)), null);

        assertThat(replacer.replaceVars("${loc.name}", scope::getVariableValue)).isEqualTo("HQ");
    }

    @Test
    public void recordPathAccessorReadsNestedComponent() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("loc", new Location("HQ", new Coords(52.5, 13.4)), null);

        assertThat(replacer.replaceVars("${loc.coords.lat}", scope::getVariableValue)).isEqualTo("52.5");
        assertThat(replacer.replaceVars("${loc.coords.lon}", scope::getVariableValue)).isEqualTo("13.4");
    }

    @Test
    public void recordPathAccessorUnknownComponentReturnsPlaceholder() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("loc", new Location("HQ", new Coords(52.5, 13.4)), null);

        // Unknown component — placeholder preserved
        assertThat(replacer.replaceVars("${loc.missing}", scope::getVariableValue)).isEqualTo("${loc.missing}");
    }

    // -----------------------------------------------------------------------
    // Indexed access — Map
    // -----------------------------------------------------------------------

    @Test
    public void mapIndexOnTopLevelList() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("tags", List.of("alpha", "beta", "gamma"), null);

        assertThat(replacer.replaceVars("${tags[1]}", scope::getVariableValue)).isEqualTo("beta");
    }

    @Test
    public void mapIndexOnNestedList() {
        VariableScope scope = new VariableScope(null, "test");
        Map<String, Object> order = Map.of("items", List.of(
                Map.of("name", "Widget", "qty", 2),
                Map.of("name", "Gadget", "qty", 5)
        ));
        scope.assignValue("order", order, null);

        assertThat(replacer.replaceVars("${order.items[0].name}", scope::getVariableValue)).isEqualTo("Widget");
        assertThat(replacer.replaceVars("${order.items[1].qty}", scope::getVariableValue)).isEqualTo("5");
    }

    @Test
    public void mapIndexOutOfBoundsReturnsPlaceholder() {
        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("list", List.of("only"), null);

        assertThat(replacer.replaceVars("${list[9]}", scope::getVariableValue)).isEqualTo("${list[9]}");
    }

    // -----------------------------------------------------------------------
    // Indexed access — Bean
    // -----------------------------------------------------------------------

    @Test
    public void beanIndexOnListProperty() {
        VariableScope scope = new VariableScope(null, "test");
        Person person = new Person("Alice", 30,
                new Address("Berlin", "10115"),
                List.of(new Address("Berlin", "10115"), new Address("Munich", "80331")));
        scope.assignValue("person", person, null);

        assertThat(replacer.replaceVars("${person.addresses[0].city}", scope::getVariableValue)).isEqualTo("Berlin");
        assertThat(replacer.replaceVars("${person.addresses[1].city}", scope::getVariableValue)).isEqualTo("Munich");
    }

    @Test
    public void beanIndexOnArray() {
        class Holder {
            public String[] getTags() {
                return new String[]{"x", "y", "z"};
            }
        }

        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("h", new Holder(), null);

        assertThat(replacer.replaceVars("${h.tags[2]}", scope::getVariableValue)).isEqualTo("z");
    }

    // -----------------------------------------------------------------------
    // Indexed access — Record
    // -----------------------------------------------------------------------

    @Test
    public void recordIndexOnListComponent() {
        VariableScope scope = new VariableScope(null, "test");
        Cart cart = new Cart("C1", List.of(new Item("AAA", 3), new Item("BBB", 7)));
        scope.assignValue("cart", cart, null);

        assertThat(replacer.replaceVars("${cart.items[0].sku}", scope::getVariableValue)).isEqualTo("AAA");
        assertThat(replacer.replaceVars("${cart.items[1].qty}", scope::getVariableValue)).isEqualTo("7");
    }

    // -----------------------------------------------------------------------
    // Custom PathAccessor
    // -----------------------------------------------------------------------

    @Test
    public void customPathAccessorIsUsed() {
        // Register an accessor that handles String values by reversing them
        replacer.addPathAccessor(new PathAccessor() {
            @Override
            public boolean supports(Object root) {
                return root instanceof String;
            }

            @Override
            public Object read(Object root, String path) {
                return new StringBuilder((String) root).reverse().toString();
            }
        });

        VariableScope scope = new VariableScope(null, "test");
        scope.assignValue("word", "hello", null);

        // "word.anything" — root is "hello" (a String), custom accessor reverses it
        String result = replacer.replaceVars("${word.reversed}", scope::getVariableValue);

        Assertions.assertThat(result).isEqualTo("olleh");
    }
}
