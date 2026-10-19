package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AllocationResponse;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.SubnetResponse;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.model.Subnet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IpamMapperTest {

    private final UUID org = UUID.randomUUID();

    @Test
    @DisplayName("a subnet answers its range in every form and how full it was judged")
    void subnet() {
        UUID site = UUID.randomUUID();
        Subnet subnet = Subnet.createNew(UUID.randomUUID(), org, "Office", "192.168.10.0/26", 30, site, 75, "op");

        SubnetResponse response = IpamMapper.toResponse(subnet, subnet.assess(50));

        assertEquals("Office", response.name());
        assertEquals("192.168.10.0/26", response.cidr());
        assertEquals("192.168.10.0", response.network());
        assertEquals("192.168.10.63", response.broadcast());
        assertEquals(26, response.prefix());
        assertEquals(30, response.vlan());
        assertEquals(site, response.siteId());
        assertEquals(75, response.warnPercent());
        assertEquals("ACTIVE", response.status());
        assertEquals("NEAR_FULL", response.level());
        assertEquals(50, response.used());
        assertEquals(62, response.usable());
        assertEquals(12, response.free());
    }

    @Test
    @DisplayName("an allocation answers its address as text, and the audit entries keep the earlier status when there is one")
    void allocationAndAudit() {
        Subnet subnet = Subnet.createNew(UUID.randomUUID(), org, "Office", "10.20.0.0/24", null, null, 80, "op");
        Allocation allocation = Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse("10.20.0.7"), null, "db-1", "op");
        allocation.release("op-2");
        subnet.retire("op");

        AllocationResponse response = IpamMapper.toResponse(allocation);
        AuditEntryResponse allocated = IpamMapper.toResponse(allocation.getAuditTrail().get(0));
        AuditEntryResponse released = IpamMapper.toResponse(allocation.getAuditTrail().get(1));
        AuditEntryResponse retired = IpamMapper.toResponse(subnet.getAuditTrail().get(1));
        AuditEntryResponse initiated = IpamMapper.toResponse(subnet.getAuditTrail().get(0));

        assertEquals("10.20.0.7", response.ip());
        assertEquals("db-1", response.hostname());
        assertEquals("RELEASED", response.status());
        assertEquals(subnet.getId(), response.subnetId());
        assertNull(allocated.fromStatus());
        assertEquals("ALLOCATED", released.fromStatus());
        assertEquals("op-2", released.executor());
        assertEquals("RETIRED", retired.toStatus());
        assertNull(initiated.fromStatus());
    }

    @Test
    @DisplayName("the mapper is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<IpamMapper> constructor = IpamMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertEquals(UnsupportedOperationException.class, failure.getCause().getClass());
    }
}
