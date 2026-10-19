package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateAllocationRequest;
import com.thinklab.application.dto.request.InitiateSubnetRequest;
import com.thinklab.application.dto.request.UpdateSubnetRequest;
import com.thinklab.domain.exception.AllocationNotFoundException;
import com.thinklab.domain.exception.DuplicateAllocationException;
import com.thinklab.domain.exception.InvalidAllocationStatusException;
import com.thinklab.domain.exception.InvalidSubnetStatusException;
import com.thinklab.domain.exception.IpamAccessDeniedException;
import com.thinklab.domain.exception.SubnetFullException;
import com.thinklab.domain.exception.SubnetNotFoundException;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.Level;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.AllocationRepository;
import com.thinklab.domain.repository.SubnetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IpamUseCasesTest {

    private static final String STAFF = "ENGINEER";
    private static final String REQUESTER = "REQUESTER";

    private final UUID org = UUID.randomUUID();
    private final UUID site = UUID.randomUUID();

    private HashServicePort hash;
    private SubnetRepository subnets;
    private AllocationRepository allocations;

    @BeforeEach
    void setUp() {
        hash = mock(HashServicePort.class);
        subnets = mock(SubnetRepository.class);
        allocations = mock(AllocationRepository.class);
        when(hash.generateSovereignId(any())).thenAnswer(call -> Mono.just(UUID.randomUUID()));
    }

    private Subnet subnet(String name, String cidr) {
        return Subnet.createNew(UUID.randomUUID(), org, name, cidr, null, site, 80, "op");
    }

    private Allocation allocation(Subnet subnet, String ip) {
        return Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse(ip), null, null, "op");
    }

    @Test
    @DisplayName("the access rule refuses a requester and lets staff and a missing role through; it is a utility class")
    void access() throws ReflectiveOperationException {
        IpamAccess.requireStaff(STAFF, "x");
        IpamAccess.requireStaff(null, "x");
        assertThrows(IpamAccessDeniedException.class, () -> IpamAccess.requireStaff(REQUESTER, "x"));
        Constructor<IpamAccess> constructor = IpamAccess.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("initiate a subnet spends a sovereign id, stores it and answers it empty; a requester spends nothing")
    void initiateSubnet() {
        when(subnets.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        InitiateSubnetUseCase useCase = new InitiateSubnetUseCase(hash, subnets);

        StepVerifier.create(useCase.execute(org, new InitiateSubnetRequest("Office", "10.20.0.0/24", 120, site, 80), "op", STAFF)).assertNext(response -> {
            assertEquals("10.20.0.0/24", response.cidr());
            assertEquals("10.20.0.0", response.network());
            assertEquals("10.20.0.255", response.broadcast());
            assertEquals(24, response.prefix());
            assertEquals(120, response.vlan());
            assertEquals("OK", response.level());
            assertEquals(0, response.used());
            assertEquals(254, response.free());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(org, new InitiateSubnetRequest("Office", "10.20.0.0/24", null, null, 80), "op", REQUESTER)).expectError(IpamAccessDeniedException.class).verify();
        verify(hash, times(1)).generateSovereignId("subnet-creation");
    }

    @Test
    @DisplayName("update, retire and reactivate go through the workflow with the status loaded; a requester and an unknown subnet are refused")
    void updateAndControlSubnet() {
        Subnet subnet = subnet("Office", "10.20.0.0/24");
        when(subnets.findById(subnet.getId(), org)).thenReturn(Mono.just(subnet));
        UUID elsewhere = UUID.randomUUID();
        when(subnets.findById(subnet.getId(), elsewhere)).thenReturn(Mono.empty());
        when(subnets.save(any(), any(), any())).thenReturn(Mono.empty());
        SubnetWorkflow workflow = new SubnetWorkflow(subnets);
        UpdateSubnetUseCase update = new UpdateSubnetUseCase(workflow);
        ControlSubnetUseCase control = new ControlSubnetUseCase(workflow);

        StepVerifier.create(update.execute(subnet.getId(), org, new UpdateSubnetRequest("Lab", 7, null, 90), "op", STAFF)).verifyComplete();
        assertEquals("Lab", subnet.getName());
        assertNull(subnet.getSiteId());
        StepVerifier.create(control.execute(subnet.getId(), org, ControlSubnetUseCase.Action.RETIRE, "op", null)).verifyComplete();
        assertEquals(SubnetStatus.RETIRED, subnet.getStatus());
        StepVerifier.create(control.execute(subnet.getId(), org, ControlSubnetUseCase.Action.REACTIVATE, "op", STAFF)).verifyComplete();
        assertEquals(SubnetStatus.ACTIVE, subnet.getStatus());
        verify(subnets).save(any(), eq(SubnetStatus.RETIRED), any());
        StepVerifier.create(control.execute(subnet.getId(), org, ControlSubnetUseCase.Action.REACTIVATE, "op", STAFF)).expectError(InvalidSubnetStatusException.class).verify();
        StepVerifier.create(control.execute(subnet.getId(), elsewhere, ControlSubnetUseCase.Action.RETIRE, "op", STAFF)).expectError(SubnetNotFoundException.class).verify();
        StepVerifier.create(update.execute(subnet.getId(), org, new UpdateSubnetRequest("Lab", 7, null, 90), "op", REQUESTER)).expectError(IpamAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("retrieve and the list judge each subnet on its allocations counted now, filter by status, site and level, and refuse a requester")
    void retrieveSubnets() {
        Subnet office = subnet("Office", "10.0.0.0/28");
        Subnet lab = Subnet.createNew(UUID.randomUUID(), org, "Lab", "10.0.1.0/28", null, null, 80, "op");
        Subnet old = subnet("Old", "10.0.2.0/28");
        old.retire("op");
        when(subnets.findById(office.getId(), org)).thenReturn(Mono.just(office));
        UUID elsewhere = UUID.randomUUID();
        when(subnets.findById(office.getId(), elsewhere)).thenReturn(Mono.empty());
        when(subnets.findAll(org)).thenReturn(Flux.just(office, lab, old));
        when(allocations.countAllocated(org, office.getId())).thenReturn(Mono.just(14L));
        when(allocations.countAllocated(org, lab.getId())).thenReturn(Mono.just(2L));
        when(allocations.countAllocated(org, old.getId())).thenReturn(Mono.just(0L));

        RetrieveSubnetUseCase one = new RetrieveSubnetUseCase(subnets, allocations);
        StepVerifier.create(one.execute(office.getId(), org, STAFF)).assertNext(response -> {
            assertEquals("FULL", response.level());
            assertEquals(0, response.free());
            assertEquals(100.0, response.usedPercent());
        }).verifyComplete();
        StepVerifier.create(one.execute(office.getId(), elsewhere, STAFF)).expectError(SubnetNotFoundException.class).verify();
        StepVerifier.create(one.execute(office.getId(), org, REQUESTER)).expectError(IpamAccessDeniedException.class).verify();

        RetrieveSubnetsUseCase list = new RetrieveSubnetsUseCase(subnets, allocations);
        StepVerifier.create(list.execute(org, null, null, null, STAFF).map(r -> r.name())).expectNext("Office", "Lab", "Old").verifyComplete();
        StepVerifier.create(list.execute(org, SubnetStatus.RETIRED, null, null, null).map(r -> r.name())).expectNext("Old").verifyComplete();
        StepVerifier.create(list.execute(org, null, site, null, STAFF).map(r -> r.name())).expectNext("Office", "Old").verifyComplete();
        StepVerifier.create(list.execute(org, null, null, Level.FULL, STAFF).map(r -> r.name())).expectNext("Office").verifyComplete();
        StepVerifier.create(list.execute(org, null, null, Level.NEAR_FULL, STAFF)).verifyComplete();
        StepVerifier.create(list.execute(org, null, null, null, REQUESTER)).expectError(IpamAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the subnet audit log is its trail, a 404 elsewhere, staff only")
    void subnetAuditLog() {
        Subnet subnet = subnet("Office", "10.20.0.0/24");
        subnet.retire("op");
        when(subnets.findById(subnet.getId(), org)).thenReturn(Mono.just(subnet));
        UUID elsewhere = UUID.randomUUID();
        when(subnets.findById(subnet.getId(), elsewhere)).thenReturn(Mono.empty());
        RetrieveSubnetAuditLogUseCase useCase = new RetrieveSubnetAuditLogUseCase(subnets);

        StepVerifier.create(useCase.execute(subnet.getId(), org, STAFF)).assertNext(trail -> assertEquals(List.of("INITIATED", "RETIRED"), trail.stream().map(e -> e.action()).toList())).verifyComplete();
        StepVerifier.create(useCase.execute(subnet.getId(), elsewhere, STAFF)).expectError(SubnetNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(subnet.getId(), org, REQUESTER)).expectError(IpamAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("an address asked for is checked before an id is spent and stored; a subnet that is not the tenant's is a 404, a requester is refused")
    void allocateAsked() {
        Subnet subnet = subnet("Office", "10.20.0.0/24");
        when(subnets.findById(subnet.getId(), org)).thenReturn(Mono.just(subnet));
        UUID unknown = UUID.randomUUID();
        when(subnets.findById(unknown, org)).thenReturn(Mono.empty());
        when(allocations.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        InitiateAllocationUseCase useCase = new InitiateAllocationUseCase(hash, subnets, allocations);
        UUID asset = UUID.randomUUID();

        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(subnet.getId(), "10.20.0.50", asset, "printer"), "op", STAFF)).assertNext(response -> {
            assertEquals("10.20.0.50", response.ip());
            assertEquals(asset, response.assetId());
            assertEquals("printer", response.hostname());
            assertEquals("ALLOCATED", response.status());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(subnet.getId(), "10.20.0.255", null, null), "op", STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(subnet.getId(), "not-an-ip", null, null), "op", STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(unknown, "10.20.0.5", null, null), "op", STAFF)).expectError(SubnetNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(subnet.getId(), "10.20.0.5", null, null), "op", REQUESTER)).expectError(IpamAccessDeniedException.class).verify();
        verify(hash, times(1)).generateSovereignId("allocation-creation");
    }

    @Test
    @DisplayName("an address already held is a 409 for the one who asked for it")
    void allocateAskedTaken() {
        Subnet subnet = subnet("Office", "10.20.0.0/24");
        when(subnets.findById(subnet.getId(), org)).thenReturn(Mono.just(subnet));
        when(allocations.create(any())).thenReturn(Mono.error(new DuplicateAllocationException("taken")));
        InitiateAllocationUseCase useCase = new InitiateAllocationUseCase(hash, subnets, allocations);

        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(subnet.getId(), "10.20.0.50", null, null), "op", STAFF)).expectError(DuplicateAllocationException.class).verify();
        verify(allocations, never()).allocatedAddresses(any(), any());
    }

    @Test
    @DisplayName("without an address the lowest free one is taken; if somebody takes it first the next free one is tried")
    void allocateNextFree() {
        Subnet subnet = subnet("Office", "10.20.0.0/24");
        when(subnets.findById(subnet.getId(), org)).thenReturn(Mono.just(subnet));
        when(allocations.allocatedAddresses(org, subnet.getId()))
                .thenReturn(Mono.just(List.of(Ipv4.parse("10.20.0.1"), Ipv4.parse("10.20.0.2"), Ipv4.parse("10.20.0.4"))))
                .thenReturn(Mono.just(List.of(Ipv4.parse("10.20.0.1"), Ipv4.parse("10.20.0.2"), Ipv4.parse("10.20.0.3"), Ipv4.parse("10.20.0.4"))));
        when(allocations.create(any())).thenReturn(Mono.error(new DuplicateAllocationException("taken"))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        InitiateAllocationUseCase useCase = new InitiateAllocationUseCase(hash, subnets, allocations);

        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(subnet.getId(), null, null, null), "op", STAFF))
                .assertNext(response -> assertEquals("10.20.0.5", response.ip())).verifyComplete();
        ArgumentCaptor<Allocation> tried = ArgumentCaptor.forClass(Allocation.class);
        verify(allocations, times(2)).create(tried.capture());
        assertEquals("10.20.0.3", tried.getAllValues().get(0).getIp());
    }

    @Test
    @DisplayName("a full subnet is a 409, and after five lost races the last one is answered")
    void allocateFullOrContended() {
        Subnet tiny = subnet("Tiny", "10.30.0.0/30");
        when(subnets.findById(tiny.getId(), org)).thenReturn(Mono.just(tiny));
        when(allocations.allocatedAddresses(org, tiny.getId())).thenReturn(Mono.just(List.of(Ipv4.parse("10.30.0.1"), Ipv4.parse("10.30.0.2"))));
        InitiateAllocationUseCase useCase = new InitiateAllocationUseCase(hash, subnets, allocations);
        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(tiny.getId(), null, null, null), "op", STAFF)).expectError(SubnetFullException.class).verify();

        Subnet busy = subnet("Busy", "10.40.0.0/24");
        when(subnets.findById(busy.getId(), org)).thenReturn(Mono.just(busy));
        when(allocations.allocatedAddresses(org, busy.getId())).thenReturn(Mono.just(List.of()));
        when(allocations.create(any())).thenReturn(Mono.error(new DuplicateAllocationException("taken")));
        StepVerifier.create(useCase.execute(org, new InitiateAllocationRequest(busy.getId(), null, null, null), "op", STAFF)).expectError(DuplicateAllocationException.class).verify();
        verify(allocations, times(InitiateAllocationUseCase.ATTEMPTS)).create(any());
    }

    @Test
    @DisplayName("the lowest free address skips the held ones, ignores held ones above it, and is null when the subnet is full")
    void lowestFree() {
        Subnet subnet = subnet("Office", "10.20.0.0/29");
        assertEquals(Ipv4.parse("10.20.0.1"), InitiateAllocationUseCase.lowestFree(subnet, List.of()));
        assertEquals(Ipv4.parse("10.20.0.2"), InitiateAllocationUseCase.lowestFree(subnet, List.of(Ipv4.parse("10.20.0.1"), Ipv4.parse("10.20.0.5"))));
        assertEquals(Ipv4.parse("10.20.0.1"), InitiateAllocationUseCase.lowestFree(subnet, List.of(Ipv4.parse("10.20.0.3"))));
        List<Long> all = List.of(1L, 2L, 3L, 4L, 5L, 6L).stream().map(n -> Ipv4.parse("10.20.0.0") + n).toList();
        assertNull(InitiateAllocationUseCase.lowestFree(subnet, all));
    }

    @Test
    @DisplayName("an allocation is read, listed with filters and a limit of 1 to 1000 (100 unless asked), released once, and its trail read")
    void allocations() {
        Subnet subnet = subnet("Office", "10.20.0.0/24");
        Allocation held = allocation(subnet, "10.20.0.9");
        when(allocations.findById(held.getId(), org)).thenReturn(Mono.just(held));
        UUID elsewhere = UUID.randomUUID();
        when(allocations.findById(held.getId(), elsewhere)).thenReturn(Mono.empty());
        when(allocations.findAll(eq(org), any(), any(), any(), anyInt())).thenReturn(Flux.just(held));
        when(allocations.save(any(), any(), any())).thenReturn(Mono.empty());

        RetrieveAllocationUseCase one = new RetrieveAllocationUseCase(allocations);
        StepVerifier.create(one.execute(held.getId(), org, STAFF)).assertNext(response -> assertEquals("10.20.0.9", response.ip())).verifyComplete();
        StepVerifier.create(one.execute(held.getId(), elsewhere, STAFF)).expectError(AllocationNotFoundException.class).verify();
        StepVerifier.create(one.execute(held.getId(), org, REQUESTER)).expectError(IpamAccessDeniedException.class).verify();

        RetrieveAllocationsUseCase list = new RetrieveAllocationsUseCase(allocations);
        StepVerifier.create(list.execute(org, subnet.getId(), AllocationStatus.ALLOCATED, null, null, STAFF)).expectNextCount(1).verifyComplete();
        verify(allocations).findAll(org, subnet.getId(), AllocationStatus.ALLOCATED, null, 100);
        StepVerifier.create(list.execute(org, null, null, null, 1000, STAFF)).expectNextCount(1).verifyComplete();
        StepVerifier.create(list.execute(org, null, null, null, 0, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(list.execute(org, null, null, null, 1001, STAFF)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(list.execute(org, null, null, null, null, REQUESTER)).expectError(IpamAccessDeniedException.class).verify();

        ControlAllocationUseCase control = new ControlAllocationUseCase(new AllocationWorkflow(allocations));
        StepVerifier.create(control.execute(held.getId(), org, ControlAllocationUseCase.Action.RELEASE, "op", STAFF)).verifyComplete();
        assertEquals(AllocationStatus.RELEASED, held.getStatus());
        verify(allocations).save(any(), eq(AllocationStatus.ALLOCATED), any());
        StepVerifier.create(control.execute(held.getId(), org, ControlAllocationUseCase.Action.RELEASE, "op", STAFF)).expectError(InvalidAllocationStatusException.class).verify();
        StepVerifier.create(control.execute(held.getId(), elsewhere, ControlAllocationUseCase.Action.RELEASE, "op", STAFF)).expectError(AllocationNotFoundException.class).verify();

        RetrieveAllocationAuditLogUseCase audit = new RetrieveAllocationAuditLogUseCase(allocations);
        StepVerifier.create(audit.execute(held.getId(), org, STAFF)).assertNext(trail -> assertTrue(trail.size() >= 2)).verifyComplete();
        StepVerifier.create(audit.execute(held.getId(), elsewhere, STAFF)).expectError(AllocationNotFoundException.class).verify();
        StepVerifier.create(audit.execute(held.getId(), org, REQUESTER)).expectError(IpamAccessDeniedException.class).verify();
    }
}
