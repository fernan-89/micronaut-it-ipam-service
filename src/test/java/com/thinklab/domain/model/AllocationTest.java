package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAllocationStatusException;
import com.thinklab.domain.exception.InvalidSubnetStatusException;
import com.thinklab.domain.model.Allocation.AllocationAuditEntry;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationTest {

    private final UUID org = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Subnet subnet = Subnet.createNew(UUID.randomUUID(), org, "Office", "10.20.0.0/24", null, null, 80, "op");

    private static void rejects(Executable action) {
        assertThrows(IllegalArgumentException.class, action);
    }

    @Test
    @DisplayName("a new allocation holds a usable address of an ACTIVE subnet, with an optional asset and hostname, and starts its trail")
    void createNew() {
        Allocation allocation = Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse("10.20.0.10"), asset, "printer-01.office.example", "op");

        assertEquals(AllocationStatus.ALLOCATED, allocation.getStatus());
        assertEquals(org, allocation.getOrganisationId());
        assertEquals(subnet.getId(), allocation.getSubnetId());
        assertEquals("10.20.0.10", allocation.getIp());
        assertEquals(Ipv4.parse("10.20.0.10"), allocation.getAddress());
        assertEquals(asset, allocation.getAssetId());
        assertEquals("printer-01.office.example", allocation.getHostname());
        assertEquals("ALLOCATED", allocation.getAuditTrail().get(0).action());
        assertEquals("10.20.0.10 in 10.20.0.0/24.", allocation.getAuditTrail().get(0).detail());
        assertNull(Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse("10.20.0.1"), null, null, "op").getHostname());
    }

    @Test
    @DisplayName("the network and broadcast addresses, an address outside the subnet, a hostname that is not a DNS name and a retired subnet are refused")
    void refused() {
        UUID id = UUID.randomUUID();
        rejects(() -> Allocation.createNew(id, subnet, Ipv4.parse("10.20.0.0"), null, null, "op"));
        rejects(() -> Allocation.createNew(id, subnet, Ipv4.parse("10.20.0.255"), null, null, "op"));
        rejects(() -> Allocation.createNew(id, subnet, Ipv4.parse("10.20.1.1"), null, null, "op"));
        for (String bad : new String[]{"", "has space", "-start", "end-", "a..b", "under_score", "x".repeat(64), ("a.").repeat(127) + "ab"}) {
            rejects(() -> Allocation.createNew(id, subnet, Ipv4.parse("10.20.0.5"), null, bad, "op"));
        }
        assertEquals("a-b.c1", Allocation.createNew(id, subnet, Ipv4.parse("10.20.0.5"), null, "a-b.c1", "op").getHostname());
        rejects(() -> Allocation.createNew(null, subnet, Ipv4.parse("10.20.0.5"), null, null, "op"));
        rejects(() -> Allocation.createNew(id, null, Ipv4.parse("10.20.0.5"), null, null, "op"));
        rejects(() -> Allocation.createNew(id, subnet, Ipv4.parse("10.20.0.5"), null, null, null));
        rejects(() -> Allocation.createNew(id, subnet, Ipv4.parse("10.20.0.5"), null, null, " "));
        Subnet retired = Subnet.createNew(UUID.randomUUID(), org, "Old", "10.30.0.0/24", null, null, 80, "op");
        retired.retire("op");
        assertThrows(InvalidSubnetStatusException.class, () -> Allocation.createNew(id, retired, Ipv4.parse("10.30.0.5"), null, null, "op"));
    }

    @Test
    @DisplayName("release goes one way: ALLOCATED to RELEASED, and never back")
    void release() {
        Allocation allocation = Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse("10.20.0.10"), null, null, "op");

        AllocationAuditEntry entry = allocation.release("op-2");

        assertEquals(AllocationStatus.RELEASED, allocation.getStatus());
        assertEquals(AllocationStatus.ALLOCATED, entry.fromStatus());
        assertEquals("Released: 10.20.0.10 is free again.", entry.detail());
        assertThrows(InvalidAllocationStatusException.class, () -> allocation.release("op"));
        rejects(() -> Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse("10.20.0.11"), null, null, "op").release(null));
        rejects(() -> Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse("10.20.0.11"), null, null, "op").release(" "));
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-19T10:00:00Z");
        Allocation full = Allocation.reconstitute(id, org, subnet.getId(), 5, asset, "h", AllocationStatus.RELEASED, at, at.plusSeconds(1),
                List.of(new AllocationAuditEntry(at, "ALLOCATED", "op", null, AllocationStatus.ALLOCATED, "d")));
        assertEquals(AllocationStatus.RELEASED, full.getStatus());
        assertEquals("0.0.0.5", full.getIp());
        assertEquals(at.plusSeconds(1), full.getUpdatedAt());

        Allocation bare = Allocation.reconstitute(id, org, subnet.getId(), 5, null, null, null, null, null, null);
        assertEquals(AllocationStatus.ALLOCATED, bare.getStatus());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());
        rejects(() -> Allocation.reconstitute(null, org, subnet.getId(), 5, null, null, null, null, null, null));
        rejects(() -> Allocation.reconstitute(id, null, subnet.getId(), 5, null, null, null, null, null, null));
        rejects(() -> Allocation.reconstitute(id, org, null, 5, null, null, null, null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> full.getAuditTrail().clear());
    }
}
