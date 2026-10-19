package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateAllocationRequest;
import com.thinklab.application.dto.request.InitiateSubnetRequest;
import com.thinklab.application.dto.request.UpdateSubnetRequest;
import com.thinklab.application.dto.response.AllocationResponse;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.SubnetResponse;
import com.thinklab.application.usecase.ControlAllocationUseCase;
import com.thinklab.application.usecase.ControlSubnetUseCase;
import com.thinklab.application.usecase.InitiateAllocationUseCase;
import com.thinklab.application.usecase.InitiateSubnetUseCase;
import com.thinklab.application.usecase.RetrieveAllocationAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveAllocationUseCase;
import com.thinklab.application.usecase.RetrieveAllocationsUseCase;
import com.thinklab.application.usecase.RetrieveSubnetAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveSubnetUseCase;
import com.thinklab.application.usecase.RetrieveSubnetsUseCase;
import com.thinklab.application.usecase.UpdateSubnetUseCase;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import com.thinklab.domain.model.Subnet.Level;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class IpamControllerTest {

    private static final String EXECUTOR = "op-1";
    private static final String ROLE = "ENGINEER";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-19T12:00:00Z");

    @Mock private InitiateSubnetUseCase initiateSubnet;
    @Mock private RetrieveSubnetUseCase retrieveSubnet;
    @Mock private RetrieveSubnetsUseCase retrieveSubnets;
    @Mock private UpdateSubnetUseCase updateSubnet;
    @Mock private ControlSubnetUseCase controlSubnet;
    @Mock private RetrieveSubnetAuditLogUseCase subnetAuditLog;
    @Mock private InitiateAllocationUseCase initiateAllocation;
    @Mock private RetrieveAllocationUseCase retrieveAllocation;
    @Mock private RetrieveAllocationsUseCase retrieveAllocations;
    @Mock private ControlAllocationUseCase controlAllocation;
    @Mock private RetrieveAllocationAuditLogUseCase allocationAuditLog;

    private IpamController controller() {
        return new IpamController(initiateSubnet, retrieveSubnet, retrieveSubnets, updateSubnet, controlSubnet, subnetAuditLog, initiateAllocation, retrieveAllocation,
                retrieveAllocations, controlAllocation, allocationAuditLog);
    }

    private SubnetResponse subnetResponse() {
        return new SubnetResponse(id, tenant, "Office", "10.20.0.0/24", "10.20.0.0", "10.20.0.255", 24, null, null, 80, "ACTIVE", "OK", 0, 254, 254, 0.0, now, now);
    }

    private AllocationResponse allocationResponse() {
        return new AllocationResponse(id, tenant, UUID.randomUUID(), "10.20.0.5", null, null, "ALLOCATED", now, now);
    }

    @Test
    @DisplayName("the subnet routes answer 201, 200 and 204 and pass the filters and the right action")
    void subnets() {
        InitiateSubnetRequest initiate = new InitiateSubnetRequest("Office", "10.20.0.0/24", null, null, 80);
        UpdateSubnetRequest update = new UpdateSubnetRequest("Lab", 7, null, 90);
        UUID site = UUID.randomUUID();
        when(initiateSubnet.execute(tenant, initiate, EXECUTOR, ROLE)).thenReturn(Mono.just(subnetResponse()));
        when(retrieveSubnet.execute(id, tenant, ROLE)).thenReturn(Mono.just(subnetResponse()));
        when(retrieveSubnets.execute(tenant, SubnetStatus.ACTIVE, site, Level.FULL, ROLE)).thenReturn(Flux.just(subnetResponse()));
        when(updateSubnet.execute(id, tenant, update, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlSubnet.execute(id, tenant, ControlSubnetUseCase.Action.RETIRE, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(controlSubnet.execute(id, tenant, ControlSubnetUseCase.Action.REACTIVATE, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(subnetAuditLog.execute(id, tenant, ROLE)).thenReturn(Mono.just(List.of(new AuditEntryResponse(now, "INITIATED", EXECUTOR, null, "ACTIVE", null))));

        StepVerifier.create(controller().initiate(tenantHeader, EXECUTOR, ROLE, initiate)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieve(id, tenantHeader, ROLE)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAll(tenantHeader, ROLE, SubnetStatus.ACTIVE, site, Level.FULL)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
        StepVerifier.create(controller().update(id, tenantHeader, EXECUTOR, ROLE, update)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retire(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().reactivate(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().auditLog(id, tenantHeader, ROLE)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
    }

    @Test
    @DisplayName("the allocation routes answer 201, 200 and 204 and pass the filters, the limit and the release")
    void allocations() {
        InitiateAllocationRequest initiate = new InitiateAllocationRequest(UUID.randomUUID(), null, null, null);
        UUID subnetId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        when(initiateAllocation.execute(tenant, initiate, EXECUTOR, ROLE)).thenReturn(Mono.just(allocationResponse()));
        when(retrieveAllocation.execute(id, tenant, ROLE)).thenReturn(Mono.just(allocationResponse()));
        when(retrieveAllocations.execute(tenant, subnetId, AllocationStatus.ALLOCATED, assetId, 10, ROLE)).thenReturn(Flux.just(allocationResponse()));
        when(controlAllocation.execute(id, tenant, ControlAllocationUseCase.Action.RELEASE, EXECUTOR, ROLE)).thenReturn(Mono.empty());
        when(allocationAuditLog.execute(id, tenant, ROLE)).thenReturn(Mono.just(List.of(new AuditEntryResponse(now, "ALLOCATED", EXECUTOR, null, "ALLOCATED", null))));

        StepVerifier.create(controller().initiateAllocation(tenantHeader, EXECUTOR, ROLE, initiate)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAllocation(id, tenantHeader, ROLE)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAllocations(tenantHeader, ROLE, subnetId, AllocationStatus.ALLOCATED, assetId, 10)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
        StepVerifier.create(controller().release(id, tenantHeader, EXECUTOR, ROLE)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().allocationAuditLog(id, tenantHeader, ROLE)).assertNext(l -> assertEquals(1, l.size())).verifyComplete();
    }
}
