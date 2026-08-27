package ai.mindconnect.pathaccessor;

import ai.mindconnect.pathaccessor.testmodel.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordPathAccessorTest {

    private final RecordPathAccessor accessor = new RecordPathAccessor();

    // -----------------------------------------------------------------------
    // supports()
    // -----------------------------------------------------------------------

    @Test
    void supportsRecord() {
        assertThat(accessor.supports(new Coords(1.0, 2.0))).isTrue();
    }

    @Test
    void doesNotSupportNonRecord() {
        assertThat(accessor.supports("hello")).isFalse();
        assertThat(accessor.supports(42)).isFalse();
        assertThat(accessor.supports(Map.of("a", 1))).isFalse();
        assertThat(accessor.supports(null)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Flat component read
    // -----------------------------------------------------------------------

    @Test
    void readsStringComponent() {
        RecordCustomer c = new RecordCustomer("Alice", "Wonder", null, List.of());
        assertThat(accessor.read(c, "firstName")).isEqualTo("Alice");
        assertThat(accessor.read(c, "lastName")).isEqualTo("Wonder");
    }

    @Test
    void readsBooleanComponent() {
        RecordProduct p = new RecordProduct("P1", "Widget", new Money(999, "EUR"), true);
        assertThat(accessor.read(p, "available")).isEqualTo(true);
    }

    @Test
    void returnsNullForUnknownComponent() {
        Coords c = new Coords(52.5, 13.4);
        assertThat(accessor.read(c, "altitude")).isNull();
    }

    // -----------------------------------------------------------------------
    // Nested records
    // -----------------------------------------------------------------------

    @Test
    void readsNestedRecordComponent() {
        Money price = new Money(1500, "USD");
        RecordProduct p = new RecordProduct("P1", "Gadget", price, true);

        assertThat(accessor.read(p, "price.amount")).isEqualTo(1500L);
        assertThat(accessor.read(p, "price.currency")).isEqualTo("USD");
    }

    @Test
    void readsDeeplyNestedRecord() {
        Money total = new Money(4999, "EUR");
        RecordAddress addr = new RecordAddress("Main St 1", "Berlin", "10115");
        RecordProduct prod = new RecordProduct("P-A", "Alpha", new Money(4999, "EUR"), true);
        RecordOrderItem item = new RecordOrderItem("SKU-A", 1, prod);
        RecordOrder order = new RecordOrder("O1", total, addr, List.of(item));
        RecordCustomer c = new RecordCustomer("Bob", "Smith", null, List.of(order));

        assertThat(accessor.read(c, "orders[0].shippingAddress.city")).isEqualTo("Berlin");
        assertThat(accessor.read(c, "orders[0].items[0].product.name")).isEqualTo("Alpha");
        assertThat(accessor.read(c, "orders[0].items[0].product.price.currency")).isEqualTo("EUR");
        assertThat(accessor.read(c, "orders[0].total.amount")).isEqualTo(4999L);
    }

    @Test
    void returnsNullWhenIntermediateIsNull() {
        RecordCustomer c = new RecordCustomer("Alice", "Wonder", null, List.of());
        assertThat(accessor.read(c, "homeAddress.city")).isNull();
    }

    // -----------------------------------------------------------------------
    // Indexed access on List
    // -----------------------------------------------------------------------

    @Test
    void readsIndexedListComponent() {
        RecordOrder o1 = new RecordOrder("O1", new Money(100, "EUR"), null, List.of());
        RecordOrder o2 = new RecordOrder("O2", new Money(200, "EUR"), null, List.of());
        RecordCustomer c = new RecordCustomer("Eve", "Adams", null, List.of(o1, o2));

        assertThat(accessor.read(c, "orders[0].id")).isEqualTo("O1");
        assertThat(accessor.read(c, "orders[1].id")).isEqualTo("O2");
        assertThat(accessor.read(c, "orders[1].total.amount")).isEqualTo(200L);
    }

    @Test
    void returnsNullForOutOfBoundsIndex() {
        RecordCustomer c = new RecordCustomer("Eve", "Adams", null,
                List.of(new RecordOrder("O1", new Money(100, "EUR"), null, List.of())));
        assertThat(accessor.read(c, "orders[9].id")).isNull();
    }

    // -----------------------------------------------------------------------
    // Mixed record + map
    // -----------------------------------------------------------------------

    @Test
    void traversesRecordThenMap() {
        RecordWrapper w = new RecordWrapper(Map.of("color", "red", "size", "L"));

        assertThat(accessor.read(w, "attributes.color")).isEqualTo("red");
        assertThat(accessor.read(w, "attributes.size")).isEqualTo("L");
    }

    @Test
    void traversesRecordThenMapThenList() {
        RecordConfig cfg = new RecordConfig(Map.of(
                "servers", List.of(
                        Map.of("host", "alpha.example.com", "port", 8080),
                        Map.of("host", "beta.example.com",  "port", 9090)
                )
        ));

        assertThat(accessor.read(cfg, "settings.servers[0].host")).isEqualTo("alpha.example.com");
        assertThat(accessor.read(cfg, "settings.servers[1].port")).isEqualTo(9090);
    }

    // -----------------------------------------------------------------------
    // Full e-commerce scenario
    // -----------------------------------------------------------------------

    @Test
    void fullEcommerceScenario() {
        Money widgetPrice = new Money(999, "EUR");
        Money gadgetPrice = new Money(2499, "EUR");
        RecordProduct widget = new RecordProduct("P-W", "Widget", widgetPrice, true);
        RecordProduct gadget = new RecordProduct("P-G", "Gadget", gadgetPrice, false);

        RecordOrderItem item1 = new RecordOrderItem("W-001", 3, widget);
        RecordOrderItem item2 = new RecordOrderItem("G-002", 1, gadget);

        RecordAddress shipping = new RecordAddress("Baker St 221B", "London", "NW1 6XE");
        Money total            = new Money(5496, "EUR");
        RecordOrder order      = new RecordOrder("ORD-99", total, shipping, List.of(item1, item2));

        RecordCustomer customer = new RecordCustomer("Sherlock", "Holmes",
                new RecordAddress("Baker St 221B", "London", "NW1 6XE"),
                List.of(order));

        assertThat(accessor.read(customer, "firstName")).isEqualTo("Sherlock");
        assertThat(accessor.read(customer, "homeAddress.zipCode")).isEqualTo("NW1 6XE");
        assertThat(accessor.read(customer, "orders[0].id")).isEqualTo("ORD-99");
        assertThat(accessor.read(customer, "orders[0].total.currency")).isEqualTo("EUR");
        assertThat(accessor.read(customer, "orders[0].shippingAddress.city")).isEqualTo("London");
        assertThat(accessor.read(customer, "orders[0].items[0].sku")).isEqualTo("W-001");
        assertThat(accessor.read(customer, "orders[0].items[0].quantity")).isEqualTo(3);
        assertThat(accessor.read(customer, "orders[0].items[0].product.name")).isEqualTo("Widget");
        assertThat(accessor.read(customer, "orders[0].items[0].product.available")).isEqualTo(true);
        assertThat(accessor.read(customer, "orders[0].items[0].product.price.amount")).isEqualTo(999L);
        assertThat(accessor.read(customer, "orders[0].items[1].product.name")).isEqualTo("Gadget");
        assertThat(accessor.read(customer, "orders[0].items[1].product.available")).isEqualTo(false);
    }
}
