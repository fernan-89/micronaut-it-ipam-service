package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationAuditEntry;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the Allocation Aggregate for MongoDB. The address is kept as a number (for ordering and the next free one) and as text. */
@Introspected
public class AllocationDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private UUID subnetId;
    private long address;
    private String ip;
    private UUID assetId;
    private String hostname;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getSubnetId() { return subnetId; }
    public void setSubnetId(UUID subnetId) { this.subnetId = subnetId; }
    public long getAddress() { return address; }
    public void setAddress(long address) { this.address = address; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(AllocationAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        AllocationAuditEntry toDomain() {
            return new AllocationAuditEntry(occurredAt, action, executor, fromStatus != null ? AllocationStatus.valueOf(fromStatus) : null, AllocationStatus.valueOf(toStatus), detail);
        }
    }

    public static final class AllocationPersistenceMapper {

        private AllocationPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static AllocationDocument toDocument(Allocation allocation) {
            AllocationDocument doc = new AllocationDocument();
            doc.setId(allocation.getId());
            doc.setOrganisationId(allocation.getOrganisationId());
            doc.setSubnetId(allocation.getSubnetId());
            doc.setAddress(allocation.getAddress());
            doc.setIp(allocation.getIp());
            doc.setAssetId(allocation.getAssetId());
            doc.setHostname(allocation.getHostname());
            doc.setStatus(allocation.getStatus().name());
            doc.setCreatedAt(allocation.getCreatedAt());
            doc.setUpdatedAt(allocation.getUpdatedAt());
            doc.setAuditTrail(allocation.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static Allocation toDomain(AllocationDocument doc) {
            return Allocation.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getSubnetId(), doc.getAddress(), doc.getAssetId(), doc.getHostname(),
                    AllocationStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(), doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
