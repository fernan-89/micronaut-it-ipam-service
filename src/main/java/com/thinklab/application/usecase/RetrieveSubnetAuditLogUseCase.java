package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.exception.SubnetNotFoundException;
import com.thinklab.domain.repository.SubnetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a subnet (BIAN Behavior Qualifier: {@code audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveSubnetAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveSubnetAuditLogUseCase.class);

    private final SubnetRepository repository;

    public RetrieveSubnetAuditLogUseCase(SubnetRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of Subnet ID: {}", id);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "read the audit trail of a subnet"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new SubnetNotFoundException("Subnet " + id + " not found.")))
                .map(subnet -> subnet.getAuditTrail().stream().map(IpamMapper::toResponse).collect(Collectors.toList()));
    }
}
