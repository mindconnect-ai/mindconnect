package ai.mindconnect.pathaccessor;

import ai.mindconnect.pathaccessor.testmodel.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BeanPathAccessorTest {

    private final BeanPathAccessor accessor = new BeanPathAccessor();

    // -----------------------------------------------------------------------
    // supports()
    // -----------------------------------------------------------------------

    @Test
    void supportsPlainBean() {
        assertThat(accessor.supports(BeanProduct.builder().name("Widget").build())).isTrue();
    }

    @Test
    void doesNotSupportMap() {
        assertThat(accessor.supports(Map.of("a", 1))).isFalse();
    }

    @Test
    void doesNotSupportString() {
        assertThat(accessor.supports("hello")).isFalse();
    }

    @Test
    void doesNotSupportNumber() {
        assertThat(accessor.supports(42)).isFalse();
    }

    @Test
    void doesNotSupportNull() {
        assertThat(accessor.supports(null)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Flat property read
    // -----------------------------------------------------------------------

    @Test
    void readsStringProperty() {
        BeanCustomer c = BeanCustomer.builder().firstName("Alice").lastName("Smith").build();
        assertThat(accessor.read(c, "firstName")).isEqualTo("Alice");
        assertThat(accessor.read(c, "lastName")).isEqualTo("Smith");
    }

    @Test
    void readsBigDecimalProperty() {
        BeanProduct p = BeanProduct.builder().price(BigDecimal.valueOf(19.99)).build();
        assertThat(accessor.read(p, "price")).isEqualTo(BigDecimal.valueOf(19.99));
    }

    @Test
    void readsBooleanViaIsGetter() {
        BeanProduct p = BeanProduct.builder().active(true).build();
        assertThat(accessor.read(p, "active")).isEqualTo(true);
    }

    @Test
    void returnsNullForUnknownProperty() {
        BeanCustomer c = BeanCustomer.builder().firstName("Alice").build();
        assertThat(accessor.read(c, "nonexistent")).isNull();
    }

    // -----------------------------------------------------------------------
    // Nested beans
    // -----------------------------------------------------------------------

    @Test
    void readsNestedBeanProperty() {
        BeanAddress addr = BeanAddress.builder().city("Berlin").zipCode("10115").build();
        BeanCustomer c = BeanCustomer.builder().invoiceAddress(addr).build();

        assertThat(accessor.read(c, "invoiceAddress.city")).isEqualTo("Berlin");
        assertThat(accessor.read(c, "invoiceAddress.zipCode")).isEqualTo("10115");
    }

    @Test
    void readsDeeplyNestedBean() {
        BeanProduct product = BeanProduct.builder().id("P1").name("Widget").price(BigDecimal.TEN).build();
        BeanOrderItem item   = BeanOrderItem.builder().sku("W-001").quantity(3).product(product).build();
        BeanOrder order      = BeanOrder.builder().id("O1").totalAmount(BigDecimal.valueOf(30)).items(List.of(item)).build();
        BeanCustomer c       = BeanCustomer.builder().orders(List.of(order)).build();

        assertThat(accessor.read(c, "orders[0].items[0].product.name")).isEqualTo("Widget");
        assertThat(accessor.read(c, "orders[0].items[0].product.price")).isEqualTo(BigDecimal.TEN);
    }

    @Test
    void returnsNullWhenIntermediateIsNull() {
        BeanCustomer c = BeanCustomer.builder().build(); // shippingAddress is null
        assertThat(accessor.read(c, "shippingAddress.city")).isNull();
    }

    // -----------------------------------------------------------------------
    // Indexed access on List
    // -----------------------------------------------------------------------

    @Test
    void readsIndexedListProperty() {
        BeanOrder o1 = BeanOrder.builder().id("O1").build();
        BeanOrder o2 = BeanOrder.builder().id("O2").build();
        BeanCustomer c = BeanCustomer.builder().orders(List.of(o1, o2)).build();

        assertThat(accessor.read(c, "orders[0].id")).isEqualTo("O1");
        assertThat(accessor.read(c, "orders[1].id")).isEqualTo("O2");
    }

    @Test
    void returnsNullForOutOfBoundsIndex() {
        BeanCustomer c = BeanCustomer.builder()
                .orders(List.of(BeanOrder.builder().id("O1").build()))
                .build();
        assertThat(accessor.read(c, "orders[9].id")).isNull();
    }

    // -----------------------------------------------------------------------
    // Indexed access on array
    // -----------------------------------------------------------------------

    @Test
    void beanIndexOnArray() {
        BeanHolder holder = new BeanHolder();
        assertThat(accessor.read(holder, "tags[2]")).isEqualTo("z");
    }

    // -----------------------------------------------------------------------
    // Mixed bean + map
    // -----------------------------------------------------------------------

    @Test
    void traversesBeanThenMap() {
        BeanContainer container = new BeanContainer();
        assertThat(accessor.read(container, "meta.region")).isEqualTo("EU");
        assertThat(accessor.read(container, "meta.tier")).isEqualTo("premium");
    }

    // -----------------------------------------------------------------------
    // Full order scenario
    // -----------------------------------------------------------------------

    @Test
    void fullOrderScenario() {
        BeanProduct widgetProduct = BeanProduct.builder()
                .id("P-W").name("Widget").price(BigDecimal.valueOf(9.99)).active(true).build();
        BeanProduct gadgetProduct = BeanProduct.builder()
                .id("P-G").name("Gadget").price(BigDecimal.valueOf(24.95)).active(false).build();

        BeanOrder order = BeanOrder.builder()
                .id("O-42")
                .totalAmount(BigDecimal.valueOf(44.93))
                .shippingAddress(BeanAddress.builder().city("Hamburg").zipCode("20095").build())
                .items(List.of(
                        BeanOrderItem.builder().sku("SKU-1").quantity(2).product(widgetProduct).build(),
                        BeanOrderItem.builder().sku("SKU-2").quantity(1).product(gadgetProduct).build()
                ))
                .build();

        BeanCustomer customer = BeanCustomer.builder()
                .firstName("Bob").lastName("Builder").orders(List.of(order)).build();

        assertThat(accessor.read(customer, "firstName")).isEqualTo("Bob");
        assertThat(accessor.read(customer, "orders[0].id")).isEqualTo("O-42");
        assertThat(accessor.read(customer, "orders[0].shippingAddress.city")).isEqualTo("Hamburg");
        assertThat(accessor.read(customer, "orders[0].items[0].sku")).isEqualTo("SKU-1");
        assertThat(accessor.read(customer, "orders[0].items[0].quantity")).isEqualTo(2);
        assertThat(accessor.read(customer, "orders[0].items[0].product.name")).isEqualTo("Widget");
        assertThat(accessor.read(customer, "orders[0].items[0].product.active")).isEqualTo(true);
        assertThat(accessor.read(customer, "orders[0].items[1].product.name")).isEqualTo("Gadget");
        assertThat(accessor.read(customer, "orders[0].items[1].product.active")).isEqualTo(false);
        assertThat(accessor.read(customer, "orders[0].totalAmount")).isEqualTo(BigDecimal.valueOf(44.93));
    }
}
