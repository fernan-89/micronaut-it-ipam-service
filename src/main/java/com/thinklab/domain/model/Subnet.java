package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidSubnetStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root (BIAN Control Record) of the IT IP Address Management Service Domain: an IPv4 range the organisation hands addresses out of. A
 * subnet has a name, its CIDR (kept as the network address and the prefix, never changed: a different range is a different subnet), an optional
 * VLAN and site, and the use at which it is nearly full. No two ACTIVE subnets of a tenant overlap (ADR-033). It is judged when read (ADR-031):
 * how many addresses are used, free, and whether it is nearly full or full. A subnet is never deleted; one that is no longer used is retired,
 * and no new address can be allocated in it.
 */
public class Subnet {

    public static final int MAX_NAME_LENGTH = 80;
    public static final int MIN_PREFIX = 8;
    public static final int MAX_PREFIX = 30;

    private final UUID id;
    private final UUID organisationId;
    private String name;
    private final long network;
    private final int prefix;
    private Integer vlan;
    private UUID siteId;
    private int warnPercent;
    private SubnetStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<SubnetAuditEntry> auditTrail;

    private Subnet(UUID id, UUID organisationId, String name, long network, int prefix, Integer vlan, UUID siteId, int warnPercent, SubnetStatus status, Instant createdAt,
                   Instant updatedAt, List<SubnetAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.network = network;
        this.prefix = prefix;
        this.vlan = vlan;
        this.siteId = siteId;
        this.warnPercent = warnPercent;
        this.status = status != null ? status : SubnetStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /** A new subnet from its CIDR ({@code 10.20.0.0/24}); host bits that are set are refused rather than silently cleared. */
    public static Subnet createNew(UUID id, UUID organisationId, String name, String cidr, Integer vlan, UUID siteId, int warnPercent, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for Subnet creation.");
        }
        if (cidr == null || cidr.chars().filter(c -> c == '/').count() != 1) {
            throw new IllegalArgumentException("The CIDR is an IPv4 address, a slash and a prefix length (10.20.0.0/24).");
        }
        String[] parts = cidr.split("/");
        long address = Ipv4.parse(parts[0]);
        int prefix = parsePrefix(parts.length > 1 ? parts[1] : "");
        if ((address & ~mask(prefix) & 0xFFFFFFFFL) != 0) {
            throw new IllegalArgumentException("The CIDR must be the network address of its range (" + Ipv4.format(address & mask(prefix)) + "/" + prefix + ").");
        }
        validate(name, vlan, warnPercent);
        requireExecutor(executor);
        Subnet subnet = new Subnet(id, organisationId, name, address, prefix, vlan, siteId, warnPercent, SubnetStatus.ACTIVE, null, null, null);
        subnet.auditTrail.add(new SubnetAuditEntry(subnet.createdAt, "INITIATED", executor, null, SubnetStatus.ACTIVE, "Subnet " + subnet.getCidr() + "."));
        return subnet;
    }

    public static Subnet reconstitute(UUID id, UUID organisationId, String name, long network, int prefix, Integer vlan, UUID siteId, int warnPercent, SubnetStatus status,
                                      Instant createdAt, Instant updatedAt, List<SubnetAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null) {
            throw new IllegalArgumentException("ID, Organisation ID and Name are mandatory to reconstitute a Subnet.");
        }
        return new Subnet(id, organisationId, name, network, prefix, vlan, siteId, warnPercent, status, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code update}. The name, VLAN, site and warning, in any status; the range never changes. */
    public SubnetAuditEntry update(String newName, Integer newVlan, UUID newSiteId, int newWarnPercent, String executor) {
        validate(newName, newVlan, newWarnPercent);
        requireExecutor(executor);
        this.name = newName;
        this.vlan = newVlan;
        this.siteId = newSiteId;
        this.warnPercent = newWarnPercent;
        return record("UPDATED", executor, this.status, this.status, "Subnet updated.");
    }

    /** Behavior Qualifier: {@code control/retire}. ACTIVE -&gt; RETIRED: no new address can be allocated in it, and its range may be reused. */
    public SubnetAuditEntry retire(String executor) {
        requireStatus(SubnetStatus.ACTIVE);
        requireExecutor(executor);
        this.status = SubnetStatus.RETIRED;
        return record("RETIRED", executor, SubnetStatus.ACTIVE, SubnetStatus.RETIRED, "Retired: no new address can be allocated in it.");
    }

    /** Behavior Qualifier: {@code control/reactivate}. RETIRED -&gt; ACTIVE, if no active subnet has taken an overlapping range meanwhile. */
    public SubnetAuditEntry reactivate(String executor) {
        requireStatus(SubnetStatus.RETIRED);
        requireExecutor(executor);
        this.status = SubnetStatus.ACTIVE;
        return record("REACTIVATED", executor, SubnetStatus.RETIRED, SubnetStatus.ACTIVE, "Reactivated.");
    }

    /** Does the address belong to the range, network and broadcast addresses included? */
    public boolean contains(long address) {
        return address >= network && address <= getBroadcast();
    }

    /** Can the address be handed out: inside the range and neither the network nor the broadcast address? */
    public boolean isUsable(long address) {
        return address > network && address < getBroadcast();
    }

    /** Do the two ranges share at least one address? */
    public boolean overlaps(Subnet other) {
        return network <= other.getBroadcast() && other.network <= getBroadcast();
    }

    /**
     * How full the subnet is with {@code used} addresses allocated (ADR-031): FULL when nothing is free, NEAR_FULL from the warning percentage on,
     * OK below; a retired subnet is not judged. Nothing of it is stored.
     */
    public Assessment assess(long used) {
        long usable = getUsable();
        if (status == SubnetStatus.RETIRED) {
            return new Assessment(Level.RETIRED, used, usable, Math.max(usable - used, 0), null);
        }
        double percent = used * 100.0 / usable;
        Level level = used >= usable ? Level.FULL : percent >= warnPercent ? Level.NEAR_FULL : Level.OK;
        return new Assessment(level, used, usable, usable - used, percent);
    }

    // --- Internal helpers ---

    private static long mask(int prefix) {
        return (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    private static int parsePrefix(String text) {
        if (!text.matches("[0-9]{1,2}")) {
            throw new IllegalArgumentException("The prefix length must be a number from " + MIN_PREFIX + " to " + MAX_PREFIX + ".");
        }
        int prefix = Integer.parseInt(text);
        if (prefix < MIN_PREFIX || prefix > MAX_PREFIX) {
            throw new IllegalArgumentException("The prefix length must be a number from " + MIN_PREFIX + " to " + MAX_PREFIX + ".");
        }
        return prefix;
    }

    private SubnetAuditEntry record(String action, String executor, SubnetStatus from, SubnetStatus to, String detail) {
        this.updatedAt = Instant.now();
        SubnetAuditEntry entry = new SubnetAuditEntry(this.updatedAt, action, executor, from, to, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(SubnetStatus expected) {
        if (this.status != expected) {
            throw new InvalidSubnetStatusException(String.format("Illegal transition: Subnet is [%s], expected [%s].", this.status, expected));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Subnet mutations.");
        }
    }

    private static void validate(String name, Integer vlan, int warnPercent) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name is mandatory for a Subnet (up to " + MAX_NAME_LENGTH + " characters).");
        }
        if (vlan != null && (vlan < 1 || vlan > 4094)) {
            throw new IllegalArgumentException("The VLAN must be between 1 and 4094.");
        }
        if (warnPercent < 1 || warnPercent > 100) {
            throw new IllegalArgumentException("The warning use must be between 1 and 100 percent.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public long getNetwork() { return network; }
    public int getPrefix() { return prefix; }
    public long getBroadcast() { return network + (1L << (32 - prefix)) - 1; }
    /** The addresses that can be handed out: the range minus its network and broadcast addresses. */
    public long getUsable() { return (1L << (32 - prefix)) - 2; }
    public String getCidr() { return Ipv4.format(network) + "/" + prefix; }
    public Integer getVlan() { return vlan; }
    public UUID getSiteId() { return siteId; }
    public int getWarnPercent() { return warnPercent; }
    public SubnetStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<SubnetAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum SubnetStatus { ACTIVE, RETIRED }

    /** RETIRED is not judged; the rest compare the addresses allocated with the usable ones. */
    public enum Level { RETIRED, OK, NEAR_FULL, FULL }

    public record SubnetAuditEntry(Instant occurredAt, String action, String executor, SubnetStatus fromStatus, SubnetStatus toStatus, String detail) {}

    /** The judgment of one moment: nothing of it is stored. {@code usedPercent} is null for a retired subnet. */
    public record Assessment(Level level, long used, long usable, long free, Double usedPercent) {}
}
