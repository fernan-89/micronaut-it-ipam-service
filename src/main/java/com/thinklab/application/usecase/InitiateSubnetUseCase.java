package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateSubnetRequest;
import com.thinklab.application.dto.response.SubnetResponse;
import com.thinklab.application.mapper.IpamMapper;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.SubnetRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for registering a subnet (BIAN Behavior Qualifier: {@code initiate}). Staff only. A name or a range already taken is a 409. */
@Singleton
public class InitiateSubnetUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateSubnetUseCase.class);

    private final HashServicePort hashServicePort;
    private final SubnetRepository repository;

    public InitiateSubnetUseCase(HashServicePort hashServicePort, SubnetRepository repository) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
    }

    public Mono<SubnetResponse> execute(UUID organisationId, InitiateSubnetRequest request, String executor, String role) {
        log.info("[USE CASE] Creating the Subnet [{}] for organisation: {}", request.name(), organisationId);
        return Mono.fromRunnable(() -> IpamAccess.requireStaff(role, "register a subnet"))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("subnet-creation")))
                .map(id -> Subnet.createNew(id, organisationId, request.name(), request.cidr(), request.vlan(), request.siteId(), request.warnPercent(), executor))
                .flatMap(repository::create)
                .map(subnet -> IpamMapper.toResponse(subnet, subnet.assess(0)));
    }
}
