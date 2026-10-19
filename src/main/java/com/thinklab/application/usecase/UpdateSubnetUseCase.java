package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateSubnetRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for changing a subnet (BIAN Behavior Qualifier: {@code update}); the range never changes. */
@Singleton
public class UpdateSubnetUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateSubnetUseCase.class);

    private final SubnetWorkflow workflow;

    public UpdateSubnetUseCase(SubnetWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdateSubnetRequest request, String executor, String role) {
        log.info("[USE CASE] Updating Subnet ID: {}", id);
        return workflow.apply(id, organisationId, role, "change a subnet",
                subnet -> subnet.update(request.name(), request.vlan(), request.siteId(), request.warnPercent(), executor));
    }
}
