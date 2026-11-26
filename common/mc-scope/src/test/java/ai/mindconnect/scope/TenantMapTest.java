package ai.mindconnect.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TenantMapTest {

    @Test
    void each_namespace_has_a_value_of_its_own_and_keeps_it() {
        TenantMap<List<String>> map = new TenantMap<>(ArrayList::new);

        map.of(TenantScope.fixed("a")).add("one");
        map.of(TenantScope.fixed(new Tenant("a", "ada"))).add("two");

        assertThat(map.of(TenantScope.fixed("a"))).containsExactly("one", "two");
        assertThat(map.of(TenantScope.fixed("b"))).isEmpty();
    }

    @Test
    void a_tenant_names_its_namespace() {
        assertThatThrownBy(() -> Tenant.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantScope.local().tenant()).isEqualTo(new Tenant(Tenant.LOCAL, null));
    }
}
