package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.SubnetAuditEntry;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the Subnet Aggregate for MongoDB. The range is kept as numbers (network and broadcast) so overlaps are one query; the CIDR text is for people. */
@Introspected
public class SubnetDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private String name;
    private String cidr;
    private long network;
    private long broadcast;
    private int prefix;
    private Integer vlan;
    private UUID siteId;
    private int warnPercent;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long sequence;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }
    public long getNetwork() { return network; }
    public void setNetwork(long network) { this.network = network; }
    public long getBroadcast() { return broadcast; }
    public void setBroadcast(long broadcast) { this.broadcast = broadcast; }
    public int getPrefix() { return prefix; }
    public void setPrefix(int prefix) { this.prefix = prefix; }
    public Integer getVlan() { return vlan; }
    public void setVlan(Integer vlan) { this.vlan = vlan; }
    public UUID getSiteId() { return siteId; }
    public void setSiteId(UUID siteId) { this.siteId = siteId; }
    public int getWarnPercent() { return warnPercent; }
    public void setWarnPercent(int warnPercent) { this.warnPercent = warnPercent; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getSequence() { return sequence; }
    public void setSequence(Long sequence) { this.sequence = sequence; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(SubnetAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        SubnetAuditEntry toDomain() {
            return new SubnetAuditEntry(occurredAt, action, executor, fromStatus != null ? SubnetStatus.valueOf(fromStatus) : null, SubnetStatus.valueOf(toStatus), detail);
        }
    }

    public static final class SubnetPersistenceMapper {

        private SubnetPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static SubnetDocument toDocument(Subnet subnet) {
            SubnetDocument doc = new SubnetDocument();
            doc.setId(subnet.getId());
            doc.setOrganisationId(subnet.getOrganisationId());
            doc.setName(subnet.getName());
            doc.setCidr(subnet.getCidr());
            doc.setNetwork(subnet.getNetwork());
            doc.setBroadcast(subnet.getBroadcast());
            doc.setPrefix(subnet.getPrefix());
            doc.setVlan(subnet.getVlan());
            doc.setSiteId(subnet.getSiteId());
            doc.setWarnPercent(subnet.getWarnPercent());
            doc.setStatus(subnet.getStatus().name());
            doc.setCreatedAt(subnet.getCreatedAt());
            doc.setUpdatedAt(subnet.getUpdatedAt());
            doc.setAuditTrail(subnet.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static Subnet toDomain(SubnetDocument doc) {
            return Subnet.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getName(), doc.getNetwork(), doc.getPrefix(), doc.getVlan(), doc.getSiteId(), doc.getWarnPercent(),
                    SubnetStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(), doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
