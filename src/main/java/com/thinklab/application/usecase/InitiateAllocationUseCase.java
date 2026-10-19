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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

/**
 * Use Case for handing out an address (BIAN Behavior Qualifier: {@code allocation/initiate}). Staff only. The subnet must be the tenant's (404)
 * and ACTIVE (409). An address asked for that is already held is a 409 (the partial unique index decides, ADR-033). Without an address the
 * lowest free usable one is taken; if somebody takes it first, a free one picked at random among the {@value #SPREAD} lowest is tried, so that
 * many simultaneous requests do not all race for the same next address, up to {@value #ATTEMPTS} times; a subnet with nothing free is a 409.
 */
@Singleton
public class InitiateAllocationUseCase {

    static final int ATTEMPTS = 5;
    static final int SPREAD = 64;
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
                        : nextFree(subnet, request, executor, 1))
                .map(IpamMapper::toResponse);
    }

    private Mono<Allocation> allocate(Subnet subnet, long address, InitiateAllocationRequest request, String executor) {
        return Mono.fromRunnable(() -> Allocation.requireAllocatable(subnet, address, request.hostname()))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("allocation-creation")))
                .map(id -> Allocation.createNew(id, subnet, address, request.assetId(), request.hostname(), executor))
                .flatMap(repository::create);
    }

    private Mono<Allocation> nextFree(Subnet subnet, InitiateAllocationRequest request, String executor, int attempt) {
        return repository.allocatedAddresses(subnet.getOrganisationId(), subnet.getId())
                .flatMap(held -> {
                    List<Long> free = lowestFree(subnet, held, SPREAD);
                    if (free.isEmpty()) {
                        return Mono.error(new SubnetFullException("Subnet " + subnet.getCidr() + " has no free address."));
                    }
                    long address = attempt == 1 ? free.get(0) : free.get(ThreadLocalRandom.current().nextInt(free.size()));
                    return allocate(subnet, address, request, executor)
                            .onErrorResume(DuplicateAllocationException.class, taken -> attempt < ATTEMPTS ? nextFree(subnet, request, executor, attempt + 1) : Mono.error(taken));
                });
    }

    /** Up to {@code count} of the lowest usable addresses of the subnet that are not held, ascending. {@code held} is ascending and every one of it usable. */
    static List<Long> lowestFree(Subnet subnet, List<Long> held, int count) {
        List<Long> free = new ArrayList<>();
        Iterator<Long> taken = held.iterator();
        Long next = taken.hasNext() ? taken.next() : null;
        for (long candidate = subnet.getNetwork() + 1; candidate < subnet.getBroadcast() && free.size() < count; candidate++) {
            if (next != null && next == candidate) {
                next = taken.hasNext() ? taken.next() : null;
            } else {
                free.add(candidate);
            }
        }
        return free;
    }
}
