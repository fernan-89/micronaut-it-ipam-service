package com.thinklab.application.usecase;

import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for releasing an address (BIAN Behavior Qualifier: {@code allocation/control/*}). */
@Singleton
public class ControlAllocationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlAllocationUseCase.class);

    /** What a person can ask for; each constant says how the aggregate performs it. */
    public enum Action {
        RELEASE {
            @Override AllocationAuditEntry apply(Allocation allocation, String executor) { return allocation.release(executor); }
        };

        abstract AllocationAuditEntry apply(Allocation allocation, String executor);
    }

    private final AllocationWorkflow workflow;

    public ControlAllocationUseCase(AllocationWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on Allocation ID: {}", action, id);
        return workflow.apply(id, organisationId, role, "release an address", allocation -> action.apply(allocation, executor));
    }
}
