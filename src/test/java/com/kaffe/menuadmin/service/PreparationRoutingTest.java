package com.kaffe.menuadmin.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class PreparationRoutingTest {
    @Test void routingIdsAndVersionsMustBeExactNumbers() {
        assertThat(MenuAdminService.preparationRouteNumber(12L,false)).isEqualTo(12);
        assertThat(MenuAdminService.preparationRouteNumber(0,true)).isZero();
        for(Object invalid : new Object[]{null,"1",-1,0,1.5,Double.NaN,new BigDecimal("9223372036854775808")})
            assertThatThrownBy(() -> MenuAdminService.preparationRouteNumber(invalid,false)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> MenuAdminService.preparationRouteNumber(-1,true)).isInstanceOf(RuntimeException.class);
    }
    @Test void routesAreScopedVersionedAndNeverUseLocationWritePrivileges() throws Exception {
        String source=Files.readString(Path.of("src/main/java/com/kaffe/menuadmin/service/MenuAdminService.java"));
        String routes=source.substring(source.indexOf("private void savePreparationRoutes"),source.indexOf("static long preparationRouteNumber"));
        assertThat(routes).contains("requireLocationAccess(tenant,location,true)","pg_advisory_xact_lock", "expectedVersion!=version",
                "tenant_id=? and location_id=? and station_id=?", "orders.preparation_audit_events")
                .doesNotContain("from core.locations");
    }
}
