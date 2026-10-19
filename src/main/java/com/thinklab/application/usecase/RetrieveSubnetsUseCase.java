package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.SubnetResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.model.Subnet.Level;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import com.thinklab.domain.repository.AllocationRepository;
import com.thinklab.domain.repository.SubnetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for listing the tenant's subnets, oldest first, judged now, filterable by status, site and level (BIAN Behavior Qualifier:
 * {@code retrieve}, collection). The level is judged when read from the allocations counted, so it is filtered in memory (ADR-031).
 */
@Singleton
public class RetrieveSubnetsUseCase {

    static final int JUDGED_AT_ONCE = 4;
    private static final Logger log = LoggerFactory.getLogger(RetrieveSubnetsUseCase.class);

    private final SubnetRepository repository;
    private final AllocationRepository allocations;

    public RetrieveSubnetsUseCase(SubnetRepository repository, AllocationRepository allocations) {
        this.repository = repository;
        this.allocations = allocations;
    }

    public Flux<SubnetResponse> execute(UUID organisationId, SubnetStatus status, UUID siteId, Level level, String role) {
        log.info("[USE CASE] Retrieving the Subnets of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "list subnets"))
                .thenMany(Flux.defer(() -> repository.findAll(organisationId)
                        .filter(subnet -> status == null || subnet.getStatus() == status)
                        .filter(subnet -> siteId == null || siteId.equals(subnet.getSiteId()))
                        .flatMapSequential(subnet -> allocations.countAllocated(organisationId, subnet.getId()).map(used -> IpamMapper.toResponse(subnet, subnet.assess(used))),
                                JUDGED_AT_ONCE)
                        .filter(response -> level == null || response.level().equals(level.name()))));
    }
}
