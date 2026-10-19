package com.thinklab.domain.repository;

import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationAuditEntry;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Outbound Port for Allocation persistence. Every lookup is tenant-scoped. */
public interface AllocationRepository {

    /** Inserts the allocation. An address already ALLOCATED in that subnet is {@link com.thinklab.domain.exception.DuplicateAllocationException}: a partial unique index is the backstop. */
    Mono<Allocation> create(Allocation allocation);

    Mono<Allocation> findById(UUID id, UUID organisationId);

    /** The tenant's allocations, newest first, at most {@code limit}, optionally of one subnet, one status or one asset. */
    Flux<Allocation> findAll(UUID organisationId, UUID subnetId, AllocationStatus status, UUID assetId, int limit);

    /** How many addresses of the subnet are ALLOCATED now. */
    Mono<Long> countAllocated(UUID organisationId, UUID subnetId);

    /** The ALLOCATED addresses of the subnet, ascending: what the next free address is found from. */
    Mono<List<Long>> allocatedAddresses(UUID organisationId, UUID subnetId);

    Mono<Void> save(Allocation allocation, AllocationStatus expectedStatus, AllocationAuditEntry auditEntry);
}
