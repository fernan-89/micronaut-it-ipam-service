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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-ipam} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.Subnet} is the Control Record, at the root;
 * {@link com.thinklab.domain.model.Allocation} is the secondary aggregate, under {@code /allocation}: an address handed out of a subnet. There is
 * no {@code DELETE}: a subnet is retired and an allocation is released.
 *
 * <p><b>Tenant on every route, staff only (ADR-032):</b> {@code X-Tenant-Id} scopes every lookup (another tenant's subnet is a 404) and
 * {@code X-Role} makes every route refuse a {@code REQUESTER} with 403 {@code ERR-IPM-00403}.
 */
@Controller("/it-ipam/v1")
public class IpamController {

    private static final Logger log = LoggerFactory.getLogger(IpamController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final InitiateSubnetUseCase initiateSubnet;
    private final RetrieveSubnetUseCase retrieveSubnet;
    private final RetrieveSubnetsUseCase retrieveSubnets;
    private final UpdateSubnetUseCase updateSubnet;
    private final ControlSubnetUseCase controlSubnet;
    private final RetrieveSubnetAuditLogUseCase subnetAuditLog;
    private final InitiateAllocationUseCase initiateAllocation;
    private final RetrieveAllocationUseCase retrieveAllocation;
    private final RetrieveAllocationsUseCase retrieveAllocations;
    private final ControlAllocationUseCase controlAllocation;
    private final RetrieveAllocationAuditLogUseCase allocationAuditLog;

    public IpamController(InitiateSubnetUseCase initiateSubnet, RetrieveSubnetUseCase retrieveSubnet, RetrieveSubnetsUseCase retrieveSubnets, UpdateSubnetUseCase updateSubnet,
                          ControlSubnetUseCase controlSubnet, RetrieveSubnetAuditLogUseCase subnetAuditLog, InitiateAllocationUseCase initiateAllocation,
                          RetrieveAllocationUseCase retrieveAllocation, RetrieveAllocationsUseCase retrieveAllocations, ControlAllocationUseCase controlAllocation,
                          RetrieveAllocationAuditLogUseCase allocationAuditLog) {
        this.initiateSubnet = initiateSubnet;
        this.retrieveSubnet = retrieveSubnet;
        this.retrieveSubnets = retrieveSubnets;
        this.updateSubnet = updateSubnet;
        this.controlSubnet = controlSubnet;
        this.subnetAuditLog = subnetAuditLog;
        this.initiateAllocation = initiateAllocation;
        this.retrieveAllocation = retrieveAllocation;
        this.retrieveAllocations = retrieveAllocations;
        this.controlAllocation = controlAllocation;
        this.allocationAuditLog = allocationAuditLog;
    }

    // ------------------------------------------------------------------ Subnets

    /** Behavior Qualifier: {@code initiate}. Registers an IPv4 range. */
    @Post("/initiate")
    public Mono<HttpResponse<SubnetResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateSubnetRequest request
    ) {
        log.info("[ACTION: INITIATE_SUBNET] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);
        return Mono.defer(() -> initiateSubnet.execute(UUID.fromString(tenantId), request, executor, role)).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. One subnet of the tenant, judged now. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<SubnetResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveSubnet.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection), oldest first, filterable by status, site and level. */
    @Get("/retrieve")
    public Mono<List<SubnetResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                  @QueryValue @Nullable SubnetStatus status, @QueryValue @Nullable UUID siteId, @QueryValue @Nullable Level level) {
        return Mono.defer(() -> retrieveSubnets.execute(UUID.fromString(tenantId), status, siteId, level, role).collectList());
    }

    /** Behavior Qualifier: {@code update}. Everything but the range. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdateSubnetRequest request
    ) {
        return Mono.defer(() -> updateSubnet.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/retire}. ACTIVE -&gt; RETIRED: no new address can be allocated in it. */
    @Put("/{id}/control/retire")
    public Mono<HttpResponse<Void>> retire(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return controlSubnet(id, tenantId, ControlSubnetUseCase.Action.RETIRE, executor, role);
    }

    /** Behavior Qualifier: {@code control/reactivate}. RETIRED -&gt; ACTIVE, unless an active subnet now overlaps it (409). */
    @Put("/{id}/control/reactivate")
    public Mono<HttpResponse<Void>> reactivate(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                               @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return controlSubnet(id, tenantId, ControlSubnetUseCase.Action.REACTIVATE, executor, role);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> auditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> subnetAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    // ------------------------------------------------------------------ Allocations

    /** Behavior Qualifier: {@code allocation/initiate}. An address asked for, or the lowest free one. */
    @Post("/allocation/initiate")
    public Mono<HttpResponse<AllocationResponse>> initiateAllocation(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateAllocationRequest request
    ) {
        log.info("[ACTION: INITIATE_ALLOCATION] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);
        return Mono.defer(() -> initiateAllocation.execute(UUID.fromString(tenantId), request, executor, role)).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code allocation/retrieve}. One allocation of the tenant. */
    @Get("/allocation/{id}/retrieve")
    public Mono<HttpResponse<AllocationResponse>> retrieveAllocation(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveAllocation.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code allocation/retrieve} (collection), newest first, by subnet, status or asset, at most 1000 (100 unless asked). */
    @Get("/allocation/retrieve")
    public Mono<List<AllocationResponse>> retrieveAllocations(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                              @QueryValue @Nullable UUID subnetId, @QueryValue @Nullable AllocationStatus status,
                                                              @QueryValue @Nullable UUID assetId, @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrieveAllocations.execute(UUID.fromString(tenantId), subnetId, status, assetId, limit, role).collectList());
    }

    /** Behavior Qualifier: {@code allocation/control/release}. ALLOCATED -&gt; RELEASED: the address is free again. */
    @Put("/allocation/{id}/control/release")
    public Mono<HttpResponse<Void>> release(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                            @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        log.info("[ACTION: RELEASE_ALLOCATION] [EXECUTOR: {}] for ID: {}", executor, id);
        return Mono.defer(() -> controlAllocation.execute(id, UUID.fromString(tenantId), ControlAllocationUseCase.Action.RELEASE, executor, role))
                .thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code allocation/audit-log/retrieve}. */
    @Get("/allocation/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> allocationAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> allocationAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    private Mono<HttpResponse<Void>> controlSubnet(UUID id, String tenantId, ControlSubnetUseCase.Action action, String executor, String role) {
        log.info("[ACTION: {}_SUBNET] [EXECUTOR: {}] for ID: {}", action, executor, id);
        return Mono.defer(() -> controlSubnet.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
