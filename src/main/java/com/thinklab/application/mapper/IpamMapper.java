package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AllocationResponse;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.SubnetResponse;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationAuditEntry;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.Assessment;
import com.thinklab.domain.model.Subnet.SubnetAuditEntry;

public final class IpamMapper {

    private IpamMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The subnet as it stands, with how full it was judged. */
    public static SubnetResponse toResponse(Subnet subnet, Assessment judged) {
        return new SubnetResponse(subnet.getId(), subnet.getOrganisationId(), subnet.getName(), subnet.getCidr(), Ipv4.format(subnet.getNetwork()),
                Ipv4.format(subnet.getBroadcast()), subnet.getPrefix(), subnet.getVlan(), subnet.getSiteId(), subnet.getWarnPercent(), subnet.getStatus().name(),
                judged.level().name(), judged.used(), judged.usable(), judged.free(), judged.usedPercent(), subnet.getCreatedAt(), subnet.getUpdatedAt());
    }

    public static AllocationResponse toResponse(Allocation allocation) {
        return new AllocationResponse(allocation.getId(), allocation.getOrganisationId(), allocation.getSubnetId(), allocation.getIp(), allocation.getAssetId(),
                allocation.getHostname(), allocation.getStatus().name(), allocation.getCreatedAt(), allocation.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(SubnetAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }

    public static AuditEntryResponse toResponse(AllocationAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }
}
