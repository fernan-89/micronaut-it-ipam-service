package com.thinklab.application.usecase;

import com.thinklab.domain.exception.SubnetNotFoundException;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.SubnetAuditEntry;
import com.thinklab.domain.repository.SubnetRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every staff change to a subnet shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded. */
@Singleton
public class SubnetWorkflow {

    private final SubnetRepository repository;

    public SubnetWorkflow(SubnetRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<Subnet, SubnetAuditEntry> action) {
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new SubnetNotFoundException("Subnet " + id + " not found.")))
                .flatMap(subnet -> {
                    var statusBefore = subnet.getStatus();
                    SubnetAuditEntry entry = action.apply(subnet);
                    return repository.save(subnet, statusBefore, entry);
                });
    }
}
