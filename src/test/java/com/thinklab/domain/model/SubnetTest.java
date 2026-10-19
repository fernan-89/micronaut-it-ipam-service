package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidSubnetStatusException;
import com.thinklab.domain.model.Subnet.Assessment;
import com.thinklab.domain.model.Subnet.Level;
import com.thinklab.domain.model.Subnet.SubnetAuditEntry;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubnetTest {

    private final UUID org = UUID.randomUUID();
    private final UUID site = UUID.randomUUID();

    private Subnet subnet(String cidr) {
        return Subnet.createNew(UUID.randomUUID(), org, "Office " + cidr, cidr, 120, site, 80, "op");
    }

    private static void rejects(Executable action) {
        assertThrows(IllegalArgumentException.class, action);
    }

    @Test
    @DisplayName("a new subnet is ACTIVE, keeps its range as network, broadcast and prefix, and starts its trail")
    void createNew() {
        Subnet subnet = subnet("10.20.0.0/24");

        assertEquals(SubnetStatus.ACTIVE, subnet.getStatus());
        assertEquals("10.20.0.0/24", subnet.getCidr());
        assertEquals(Ipv4.parse("10.20.0.0"), subnet.getNetwork());
        assertEquals(Ipv4.parse("10.20.0.255"), subnet.getBroadcast());
        assertEquals(24, subnet.getPrefix());
        assertEquals(254, subnet.getUsable());
        assertEquals(120, subnet.getVlan());
        assertEquals(site, subnet.getSiteId());
        assertEquals(80, subnet.getWarnPercent());
        assertEquals("Subnet 10.20.0.0/24.", subnet.getAuditTrail().get(0).detail());
        assertNull(subnet.getAuditTrail().get(0).fromStatus());
        assertEquals(subnet.getCreatedAt(), subnet.getUpdatedAt());
        assertEquals(2, subnet("10.0.0.0/30").getUsable());
        assertEquals(16777214, subnet("10.0.0.0/8").getUsable());
    }

    @Test
    @DisplayName("a CIDR is an address, a slash and a prefix from /8 to /30, written as its network address; the rest is checked too")
    void validation() {
        UUID id = UUID.randomUUID();
        for (String bad : new String[]{"10.20.0.0", "10.20.0.0/", "10.20.0.0/24/1", "10.20.0.0/x", "10.20.0.0/7", "10.20.0.0/31", "10.20.0.0/123", "10.20.0.1/24", "10.20.0/24"}) {
            rejects(() -> Subnet.createNew(id, org, "n", bad, null, null, 80, "op"));
        }
        IllegalArgumentException hostBits = assertThrows(IllegalArgumentException.class, () -> Subnet.createNew(id, org, "n", "10.20.0.7/24", null, null, 80, "op"));
        assertTrue(hostBits.getMessage().contains("10.20.0.0/24"));
        rejects(() -> Subnet.createNew(id, org, "n", null, null, null, 80, "op"));
        rejects(() -> Subnet.createNew(null, org, "n", "10.0.0.0/24", null, null, 80, "op"));
        rejects(() -> Subnet.createNew(id, null, "n", "10.0.0.0/24", null, null, 80, "op"));
        rejects(() -> Subnet.createNew(id, org, null, "10.0.0.0/24", null, null, 80, "op"));
        rejects(() -> Subnet.createNew(id, org, " ", "10.0.0.0/24", null, null, 80, "op"));
        rejects(() -> Subnet.createNew(id, org, "x".repeat(81), "10.0.0.0/24", null, null, 80, "op"));
        rejects(() -> Subnet.createNew(id, org, "n", "10.0.0.0/24", 0, null, 80, "op"));
        rejects(() -> Subnet.createNew(id, org, "n", "10.0.0.0/24", 4095, null, 80, "op"));
        rejects(() -> Subnet.createNew(id, org, "n", "10.0.0.0/24", null, null, 0, "op"));
        rejects(() -> Subnet.createNew(id, org, "n", "10.0.0.0/24", null, null, 101, "op"));
        rejects(() -> Subnet.createNew(id, org, "n", "10.0.0.0/24", null, null, 80, null));
        rejects(() -> Subnet.createNew(id, org, "n", "10.0.0.0/24", null, null, 80, " "));
        assertEquals(4094, Subnet.createNew(id, org, "x".repeat(80), "0.0.0.0/8", 4094, null, 100, "op").getVlan());
        assertEquals(1, Subnet.createNew(id, org, "n", "255.255.255.252/30", 1, null, 1, "op").getVlan());
    }

    @Test
    @DisplayName("contains counts the network and broadcast addresses; usable does not; overlaps compares the two ranges")
    void ranges() {
        Subnet subnet = subnet("10.20.0.0/24");

        assertTrue(subnet.contains(Ipv4.parse("10.20.0.0")));
        assertTrue(subnet.contains(Ipv4.parse("10.20.0.255")));
        assertFalse(subnet.contains(Ipv4.parse("10.20.1.0")));
        assertFalse(subnet.contains(Ipv4.parse("10.19.255.255")));
        assertTrue(subnet.isUsable(Ipv4.parse("10.20.0.1")));
        assertTrue(subnet.isUsable(Ipv4.parse("10.20.0.254")));
        assertFalse(subnet.isUsable(Ipv4.parse("10.20.0.0")));
        assertFalse(subnet.isUsable(Ipv4.parse("10.20.0.255")));
        assertTrue(subnet.overlaps(subnet("10.20.0.0/16")));
        assertTrue(subnet.overlaps(subnet("10.20.0.128/25")));
        assertFalse(subnet.overlaps(subnet("10.20.1.0/24")));
        assertFalse(subnet.overlaps(subnet("10.19.255.0/24")));
    }

    @Test
    @DisplayName("a subnet is judged on the addresses used: OK below the warning, NEAR_FULL from it, FULL when nothing is free; a retired one is not judged")
    void assess() {
        Subnet subnet = subnet("10.0.0.0/28");

        assertEquals(new Assessment(Level.OK, 0, 14, 14, 0.0), subnet.assess(0));
        assertEquals(Level.OK, subnet.assess(11).level());
        assertEquals(Level.NEAR_FULL, subnet.assess(12).level());
        assertEquals(new Assessment(Level.FULL, 14, 14, 0, 100.0), subnet.assess(14));
        subnet.retire("op");
        assertEquals(new Assessment(Level.RETIRED, 3, 14, 11, null), subnet.assess(3));
        assertEquals(0, subnet.assess(20).free());
    }

    @Test
    @DisplayName("update changes everything but the range, in any status; retire and reactivate go one way each")
    void lifecycle() {
        Subnet subnet = subnet("10.20.0.0/24");

        SubnetAuditEntry updated = subnet.update("Lab", null, null, 90, "op-2");
        assertEquals("Lab", subnet.getName());
        assertNull(subnet.getVlan());
        assertNull(subnet.getSiteId());
        assertEquals(90, subnet.getWarnPercent());
        assertEquals("UPDATED", updated.action());
        assertEquals("10.20.0.0/24", subnet.getCidr());
        rejects(() -> subnet.update(" ", null, null, 90, "op"));
        rejects(() -> subnet.update("n", null, null, 90, null));

        assertThrows(InvalidSubnetStatusException.class, () -> subnet.reactivate("op"));
        assertEquals("RETIRED", subnet.retire("op").action());
        assertThrows(InvalidSubnetStatusException.class, () -> subnet.retire("op"));
        subnet.update("Old lab", 7, null, 90, "op");
        assertEquals(SubnetStatus.RETIRED, subnet.reactivate("op").fromStatus());
        assertEquals(SubnetStatus.ACTIVE, subnet.getStatus());
        rejects(() -> subnet.retire(null));
        subnet.retire("op");
        rejects(() -> subnet.reactivate(" "));
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-19T10:00:00Z");
        Subnet full = Subnet.reconstitute(id, org, "n", Ipv4.parse("10.0.0.0"), 24, 5, site, 70, SubnetStatus.RETIRED, at, at.plusSeconds(5),
                List.of(new SubnetAuditEntry(at, "INITIATED", "op", null, SubnetStatus.ACTIVE, "d")));
        assertEquals(SubnetStatus.RETIRED, full.getStatus());
        assertEquals("10.0.0.0/24", full.getCidr());
        assertEquals(at.plusSeconds(5), full.getUpdatedAt());
        assertEquals(1, full.getAuditTrail().size());

        Subnet bare = Subnet.reconstitute(id, org, "n", 0, 8, null, null, 80, null, null, null, null);
        assertEquals(SubnetStatus.ACTIVE, bare.getStatus());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());
        rejects(() -> Subnet.reconstitute(null, org, "n", 0, 8, null, null, 80, null, null, null, null));
        rejects(() -> Subnet.reconstitute(id, null, "n", 0, 8, null, null, 80, null, null, null, null));
        rejects(() -> Subnet.reconstitute(id, org, null, 0, 8, null, null, 80, null, null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> full.getAuditTrail().clear());
    }
}
