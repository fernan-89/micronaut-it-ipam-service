package com.thinklab.domain.repository;

import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.SubnetAuditEntry;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port for Subnet persistence. Changes are guarded writes on the status loaded (ADR-033); every lookup is tenant-scoped. */
public interface SubnetRepository {

    /**
     * Inserts the subnet. A name already used in the organisation is {@link com.thinklab.domain.exception.DuplicateSubnetException} (unique index).
     * A range that overlaps an ACTIVE subnet of the tenant is a duplicate too: the subnet is inserted, then the overlapping ones are looked for, and
     * of two that overlap the later one (by creation time, then id) removes itself, so exactly one of any number of simultaneous creations survives.
     */
    Mono<Subnet> create(Subnet subnet);

    Mono<Subnet> findById(UUID id, UUID organisationId);

    /** The tenant's subnets, oldest first. */
    Flux<Subnet> findAll(UUID organisationId);

    /**
     * Saves a change guarded by the status loaded. A reactivation is checked the same way as a creation: if an ACTIVE subnet overlapping it exists,
     * the subnet goes back to RETIRED and the answer is a duplicate.
     */
    Mono<Void> save(Subnet subnet, SubnetStatus expectedStatus, SubnetAuditEntry auditEntry);
}
