package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAllocationStatusException;
import com.thinklab.domain.exception.InvalidSubnetStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Secondary aggregate of the IT IP Address Management Service Domain: one IPv4 address handed out of a subnet. It may name the asset that holds it
 * and a hostname (a DNS name, never a person's name). An address is held by at most one ALLOCATED allocation at a time (ADR-033); releasing it
 * makes it free again, and the released allocation stays as history. Allocations are never deleted.
 */
public class Allocation {

    /** A DNS host name: labels of letters, digits and dashes, separated by dots, at most 253 characters in all. */
    public static final Pattern HOSTNAME = Pattern.compile("(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    private final UUID id;
    private final UUID organisationId;
    private final UUID subnetId;
    private final long address;
    private final UUID assetId;
    private final String hostname;
    private AllocationStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<AllocationAuditEntry> auditTrail;

    private Allocation(UUID id, UUID organisationId, UUID subnetId, long address, UUID assetId, String hostname, AllocationStatus status, Instant createdAt, Instant updatedAt,
                       List<AllocationAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.subnetId = subnetId;
        this.address = address;
        this.assetId = assetId;
        this.hostname = hostname;
        this.status = status != null ? status : AllocationStatus.ALLOCATED;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /** A new allocation of {@code address} in {@code subnet}: the subnet must be ACTIVE and the address usable (not its network or broadcast address). */
    public static Allocation createNew(UUID id, Subnet subnet, long address, UUID assetId, String hostname, String executor) {
        if (id == null || subnet == null) {
            throw new IllegalArgumentException("ID and Subnet are mandatory for Allocation creation.");
        }
        requireAllocatable(subnet, address, hostname);
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Allocation mutations.");
        }
        Allocation allocation = new Allocation(id, subnet.getOrganisationId(), subnet.getId(), address, assetId, hostname, AllocationStatus.ALLOCATED, null, null, null);
        allocation.auditTrail.add(new AllocationAuditEntry(allocation.createdAt, "ALLOCATED", executor, null, AllocationStatus.ALLOCATED,
                Ipv4.format(address) + " in " + subnet.getCidr() + "."));
        return allocation;
    }

    /**
     * Whether {@code address} can be handed out of {@code subnet} with that hostname: the subnet ACTIVE, the address usable, the hostname a DNS name.
     * Checked before an id is spent, and again when the allocation is made.
     */
    public static void requireAllocatable(Subnet subnet, long address, String hostname) {
        if (subnet.getStatus() != Subnet.SubnetStatus.ACTIVE) {
            throw new InvalidSubnetStatusException("Subnet " + subnet.getCidr() + " is retired: no address can be allocated in it.");
        }
        if (!subnet.isUsable(address)) {
            throw new IllegalArgumentException("The address " + Ipv4.format(address) + " cannot be handed out of " + subnet.getCidr()
                    + " (outside it, or its network or broadcast address).");
        }
        if (hostname != null && !HOSTNAME.matcher(hostname).matches()) {
            throw new IllegalArgumentException("The hostname must be a DNS name: letters, digits and dashes, in labels separated by dots.");
        }
    }

    public static Allocation reconstitute(UUID id, UUID organisationId, UUID subnetId, long address, UUID assetId, String hostname, AllocationStatus status, Instant createdAt,
                                          Instant updatedAt, List<AllocationAuditEntry> auditTrail) {
        if (id == null || organisationId == null || subnetId == null) {
            throw new IllegalArgumentException("ID, Organisation ID and Subnet ID are mandatory to reconstitute an Allocation.");
        }
        return new Allocation(id, organisationId, subnetId, address, assetId, hostname, status, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code allocation/control/release}. ALLOCATED -&gt; RELEASED: the address is free again. One way only. */
    public AllocationAuditEntry release(String executor) {
        if (this.status != AllocationStatus.ALLOCATED) {
            throw new InvalidAllocationStatusException(String.format("Illegal transition: Allocation is [%s], expected [%s].", this.status, AllocationStatus.ALLOCATED));
        }
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Allocation mutations.");
        }
        this.status = AllocationStatus.RELEASED;
        this.updatedAt = Instant.now();
        AllocationAuditEntry entry = new AllocationAuditEntry(this.updatedAt, "RELEASED", executor, AllocationStatus.ALLOCATED, AllocationStatus.RELEASED,
                "Released: " + Ipv4.format(address) + " is free again.");
        this.auditTrail.add(entry);
        return entry;
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getSubnetId() { return subnetId; }
    public long getAddress() { return address; }
    public String getIp() { return Ipv4.format(address); }
    public UUID getAssetId() { return assetId; }
    public String getHostname() { return hostname; }
    public AllocationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AllocationAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum AllocationStatus { ALLOCATED, RELEASED }

    public record AllocationAuditEntry(Instant occurredAt, String action, String executor, AllocationStatus fromStatus, AllocationStatus toStatus, String detail) {}
}
