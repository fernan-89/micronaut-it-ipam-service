package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.exception.AllocationNotFoundException;
import com.thinklab.domain.repository.AllocationRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of an allocation (BIAN Behavior Qualifier: {@code allocation/audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveAllocationAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAllocationAuditLogUseCase.class);

    private final AllocationRepository repository;

    public RetrieveAllocationAuditLogUseCase(AllocationRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of Allocation ID: {}", id);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "read the audit trail of an allocation"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AllocationNotFoundException("Allocation " + id + " not found.")))
                .map(allocation -> allocation.getAuditTrail().stream().map(IpamMapper::toResponse).collect(Collectors.toList()));
    }
}
