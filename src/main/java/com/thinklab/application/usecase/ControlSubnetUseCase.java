package com.thinklab.application.usecase;

import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.SubnetAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for retiring and reactivating a subnet (BIAN Behavior Qualifier: {@code control/*}). */
@Singleton
public class ControlSubnetUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlSubnetUseCase.class);

    /** What a person can ask for; each constant says how the aggregate performs it. */
    public enum Action {
        RETIRE {
            @Override SubnetAuditEntry apply(Subnet subnet, String executor) { return subnet.retire(executor); }
        },
        REACTIVATE {
            @Override SubnetAuditEntry apply(Subnet subnet, String executor) { return subnet.reactivate(executor); }
        };

        abstract SubnetAuditEntry apply(Subnet subnet, String executor);
    }

    private final SubnetWorkflow workflow;

    public ControlSubnetUseCase(SubnetWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on Subnet ID: {}", action, id);
        return workflow.apply(id, organisationId, role, "retire or reactivate a subnet", subnet -> action.apply(subnet, executor));
    }
}
