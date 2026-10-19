package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.SubnetResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.exception.SubnetNotFoundException;
import com.thinklab.domain.repository.AllocationRepository;
import com.thinklab.domain.repository.SubnetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one subnet of the tenant, judged now (BIAN Behavior Qualifier: {@code retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrieveSubnetUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveSubnetUseCase.class);

    private final SubnetRepository repository;
    private final AllocationRepository allocations;

    public RetrieveSubnetUseCase(SubnetRepository repository, AllocationRepository allocations) {
        this.repository = repository;
        this.allocations = allocations;
    }

    public Mono<SubnetResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving Subnet ID: {}", id);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "read a subnet"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new SubnetNotFoundException("Subnet " + id + " not found.")))
                .flatMap(subnet -> allocations.countAllocated(organisationId, subnet.getId()).map(used -> IpamMapper.toResponse(subnet, subnet.assess(used))));
    }
}
