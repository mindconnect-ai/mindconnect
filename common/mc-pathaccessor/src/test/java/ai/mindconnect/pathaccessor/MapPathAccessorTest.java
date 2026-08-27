package ai.mindconnect.pathaccessor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapPathAccessorTest {

    private final MapPathAccessor accessor = new MapPathAccessor();

    // -----------------------------------------------------------------------
    // supports()
    // -----------------------------------------------------------------------

    @Test
    void supportsMap() {
        assertThat(accessor.supports(Map.of("a", 1))).isTrue();
    }

    @Test
    void doesNotSupportNonMap() {
        assertThat(accessor.supports("string")).isFalse();
        assertThat(accessor.supports(42)).isFalse();
        assertThat(accessor.supports(null)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Flat key lookup
    // -----------------------------------------------------------------------

    @Test
    void readsTopLevelString() {
        Map<String, Object> map = Map.of("name", "Alice");
        assertThat(accessor.read(map, "name")).isEqualTo("Alice");
    }

    @Test
    void readsTopLevelInteger() {
        Map<String, Object> map = Map.of("age", 30);
        assertThat(accessor.read(map, "age")).isEqualTo(30);
    }

    @Test
    void returnsNullForMissingKey() {
        Map<String, Object> map = Map.of("x", 1);
        assertThat(accessor.read(map, "missing")).isNull();
    }

    // -----------------------------------------------------------------------
    // Nested maps
    // -----------------------------------------------------------------------

    @Test
    void readsNestedProperty() {
        Map<String, Object> address = Map.of("city", "Berlin", "zip", "10115");
        Map<String, Object> user = Map.of("address", address);

        assertThat(accessor.read(user, "address.city")).isEqualTo("Berlin");
        assertThat(accessor.read(user, "address.zip")).isEqualTo("10115");
    }

    @Test
    void readsDeeplyNestedProperty() {
        Map<String, Object> coords = Map.of("lat", 52.5, "lon", 13.4);
        Map<String, Object> location = Map.of("coords", coords);
        Map<String, Object> office = Map.of("location", location);

        assertThat(accessor.read(office, "location.coords.lat")).isEqualTo(52.5);
    }

    @Test
    void returnsNullWhenIntermediateKeyMissing() {
        Map<String, Object> map = Map.of("a", Map.of("b", "v"));
        assertThat(accessor.read(map, "a.missing.c")).isNull();
    }

    @Test
    void returnsNullWhenIntermediateValueNotAMap() {
        Map<String, Object> map = Map.of("a", "not-a-map");
        assertThat(accessor.read(map, "a.b")).isNull();
    }

    // -----------------------------------------------------------------------
    // Indexed access on List
    // -----------------------------------------------------------------------

    @Test
    void readsIndexedTopLevelList() {
        Map<String, Object> map = Map.of("tags", List.of("alpha", "beta", "gamma"));
        assertThat(accessor.read(map, "tags[0]")).isEqualTo("alpha");
        assertThat(accessor.read(map, "tags[2]")).isEqualTo("gamma");
    }

    @Test
    void readsIndexedNestedList() {
        Map<String, Object> map = Map.of("orders", List.of(
                Map.of("id", "O1", "total", 99),
                Map.of("id", "O2", "total", 42)
        ));

        assertThat(accessor.read(map, "orders[0].id")).isEqualTo("O1");
        assertThat(accessor.read(map, "orders[1].total")).isEqualTo(42);
    }

    @Test
    void returnsNullForOutOfBoundsIndex() {
        Map<String, Object> map = Map.of("items", List.of("only"));
        assertThat(accessor.read(map, "items[5]")).isNull();
    }

    // -----------------------------------------------------------------------
    // Indexed access on array
    // -----------------------------------------------------------------------

    @Test
    void readsIndexedArray() {
        Map<String, Object> map = Map.of("scores", new int[]{10, 20, 30});
        assertThat(accessor.read(map, "scores[1]")).isEqualTo(20);
    }

    // -----------------------------------------------------------------------
    // Mixed nesting (list of maps of lists)
    // -----------------------------------------------------------------------

    @Test
    void readsDeepMixedStructure() {
        Map<String, Object> map = Map.of(
                "departments", List.of(
                        Map.of("name", "Engineering", "employees", List.of(
                                Map.of("name", "Alice", "level", 3),
                                Map.of("name", "Bob",   "level", 5)
                        )),
                        Map.of("name", "Marketing", "employees", List.of(
                                Map.of("name", "Carol", "level", 2)
                        ))
                )
        );

        assertThat(accessor.read(map, "departments[0].name")).isEqualTo("Engineering");
        assertThat(accessor.read(map, "departments[0].employees[1].name")).isEqualTo("Bob");
        assertThat(accessor.read(map, "departments[1].employees[0].level")).isEqualTo(2);
    }
}
