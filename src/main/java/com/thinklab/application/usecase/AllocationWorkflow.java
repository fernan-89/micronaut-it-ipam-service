package com.thinklab.application.usecase;

import com.thinklab.domain.exception.AllocationNotFoundException;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationAuditEntry;
import com.thinklab.domain.repository.AllocationRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every staff change to an allocation shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded. */
@Singleton
public class AllocationWorkflow {

    private final AllocationRepository repository;

    public AllocationWorkflow(AllocationRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<Allocation, AllocationAuditEntry> action) {
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AllocationNotFoundException("Allocation " + id + " not found.")))
                .flatMap(allocation -> {
                    var statusBefore = allocation.getStatus();
                    AllocationAuditEntry entry = action.apply(allocation);
                    return repository.save(allocation, statusBefore, entry);
                });
    }
}
