package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AllocationResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.exception.AllocationNotFoundException;
import com.thinklab.domain.repository.AllocationRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one allocation of the tenant (BIAN Behavior Qualifier: {@code allocation/retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrieveAllocationUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAllocationUseCase.class);

    private final AllocationRepository repository;

    public RetrieveAllocationUseCase(AllocationRepository repository) {
        this.repository = repository;
    }

    public Mono<AllocationResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving Allocation ID: {}", id);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "read an allocation"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AllocationNotFoundException("Allocation " + id + " not found.")))
                .map(IpamMapper::toResponse);
    }
}
