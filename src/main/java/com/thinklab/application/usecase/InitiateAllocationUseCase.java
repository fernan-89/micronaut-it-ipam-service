package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateAllocationRequest;
import com.thinklab.application.dto.response.AllocationResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.exception.DuplicateAllocationException;
import com.thinklab.domain.exception.SubnetFullException;
import com.thinklab.domain.exception.SubnetNotFoundException;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.AllocationRepository;
import com.thinklab.domain.repository.SubnetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Use Case for handing out an address (BIAN Behavior Qualifier: {@code allocation/initiate}). Staff only. The subnet must be the tenant's (404)
 * and ACTIVE (409). An address asked for that is already held is a 409 (the partial unique index decides, ADR-033). Without an address the
 * lowest free usable one is taken; if somebody takes it first the next free one is tried, up to {@value #ATTEMPTS} times, and a subnet with
 * nothing free is a 409.
 */
@Singleton
public class InitiateAllocationUseCase {

    static final int ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(InitiateAllocationUseCase.class);

    private final HashServicePort hashServicePort;
    private final SubnetRepository subnets;
    private final AllocationRepository repository;

    public InitiateAllocationUseCase(HashServicePort hashServicePort, SubnetRepository subnets, AllocationRepository repository) {
        this.hashServicePort = hashServicePort;
        this.subnets = subnets;
        this.repository = repository;
    }

    public Mono<AllocationResponse> execute(UUID organisationId, InitiateAllocationRequest request, String executor, String role) {
        log.info("[USE CASE] Allocating an address in Subnet {} for organisation: {}", request.subnetId(), organisationId);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "allocate an address"))
                .then(Mono.defer(() -> subnets.findById(request.subnetId(), organisationId)))
                .switchIfEmpty(Mono.error(new SubnetNotFoundException("Subnet " + request.subnetId() + " not found.")))
                .flatMap(subnet -> request.ip() != null
                        ? allocate(subnet, Ipv4.parse(request.ip()), request, executor)
                        : nextFree(subnet, request, executor, ATTEMPTS))
                .map(IpamMapper::toResponse);
    }

    private Mono<Allocation> allocate(Subnet subnet, long address, InitiateAllocationRequest request, String executor) {
        return Mono.fromRunnable(() -> Allocation.requireAllocatable(subnet, address, request.hostname()))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("allocation-creation")))
                .map(id -> Allocation.createNew(id, subnet, address, request.assetId(), request.hostname(), executor))
                .flatMap(repository::create);
    }

    private Mono<Allocation> nextFree(Subnet subnet, InitiateAllocationRequest request, String executor, int attemptsLeft) {
        return repository.allocatedAddresses(subnet.getOrganisationId(), subnet.getId())
                .flatMap(held -> {
                    Long free = lowestFree(subnet, held);
                    if (free == null) {
                        return Mono.error(new SubnetFullException("Subnet " + subnet.getCidr() + " has no free address."));
                    }
                    return allocate(subnet, free, request, executor)
                            .onErrorResume(DuplicateAllocationException.class, taken -> attemptsLeft > 1 ? nextFree(subnet, request, executor, attemptsLeft - 1) : Mono.error(taken));
                });
    }

    /** The lowest usable address of the subnet that is not held, or null when every one is. {@code held} is ascending and every one of it usable. */
    static Long lowestFree(Subnet subnet, List<Long> held) {
        long candidate = subnet.getNetwork() + 1;
        for (long address : held) {
            if (address != candidate) {
                break;
            }
            candidate++;
        }
        return candidate < subnet.getBroadcast() ? candidate : null;
    }
}
