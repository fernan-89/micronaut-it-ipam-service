package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AllocationResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import com.thinklab.domain.repository.AllocationRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the tenant's allocations, newest first, by subnet, status or asset, at most {@value #MAX_LIMIT} (BIAN Behavior Qualifier: {@code allocation/retrieve}). */
@Singleton
public class RetrieveAllocationsUseCase {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;
    private static final Logger log = LoggerFactory.getLogger(RetrieveAllocationsUseCase.class);

    private final AllocationRepository repository;

    public RetrieveAllocationsUseCase(AllocationRepository repository) {
        this.repository = repository;
    }

    public Flux<AllocationResponse> execute(UUID organisationId, UUID subnetId, AllocationStatus status, UUID assetId, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the Allocations of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "list allocations"))
                .thenMany(Flux.defer(() -> repository.findAll(organisationId, subnetId, status, assetId, limitOf(limit)).map(IpamMapper::toResponse)));
    }

    private static int limitOf(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("The limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }
}
