package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateAllocationException;
import com.thinklab.domain.exception.DuplicateSubnetException;
import com.thinklab.domain.exception.InvalidAllocationStatusException;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import com.thinklab.domain.repository.AllocationRepository;
import com.thinklab.domain.repository.SubnetRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP address management against a real MongoDB: the unique subnet name, overlapping ranges settled after the write (one survivor out of twenty
 * simultaneous creations), a reactivation undone when its range is taken, one holder per address (one winner out of twenty simultaneous requests),
 * a released address handed out again, the held addresses counted and listed, and the indexes created at startup.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IpamPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "ipam_it";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject SubnetRepository subnets;
    @Inject AllocationRepository allocations;
    @Inject MongoClient mongoClient;

    private Subnet newSubnet(UUID organisation, String name, String cidr) {
        return Subnet.createNew(UUID.randomUUID(), organisation, name, cidr, 10, null, 80, "op-1");
    }

    private Allocation newAllocation(Subnet subnet, String ip) {
        return Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse(ip), null, null, "op-1");
    }

    @Test
    @DisplayName("a subnet is read back whole, only for its own organisation, oldest first; the name is unique per organisation")
    void subnetRoundTrip() {
        UUID organisation = UUID.randomUUID();
        Subnet first = subnets.create(newSubnet(organisation, "First", "10.1.0.0/24")).block();
        subnets.create(newSubnet(organisation, "Second", "10.2.0.0/24")).block();

        Subnet found = subnets.findById(first.getId(), organisation).block();

        assertEquals("10.1.0.0/24", found.getCidr());
        assertEquals(10, found.getVlan());
        assertEquals(1, found.getAuditTrail().size());
        assertNull(subnets.findById(first.getId(), UUID.randomUUID()).block());
        assertEquals(List.of("First", "Second"), subnets.findAll(organisation).map(Subnet::getName).collectList().block());
        assertThrows(DuplicateSubnetException.class, () -> subnets.create(newSubnet(organisation, "First", "10.3.0.0/24")).block());
        assertEquals("First", subnets.create(newSubnet(UUID.randomUUID(), "First", "10.1.0.0/24")).block().getName());
    }

    @Test
    @DisplayName("an overlapping range is refused, a retired one does not count, and of twenty creations at once of ranges that all overlap each other exactly one survives")
    void overlaps() {
        UUID organisation = UUID.randomUUID();
        Subnet wide = subnets.create(newSubnet(organisation, "Wide", "10.20.0.0/16")).block();
        assertThrows(DuplicateSubnetException.class, () -> subnets.create(newSubnet(organisation, "Inside", "10.20.5.0/24")).block());
        assertEquals(1, subnets.findAll(organisation).count().block());

        var retire = wide.retire("op-1");
        subnets.save(wide, SubnetStatus.ACTIVE, retire).block();
        Subnet inside = subnets.create(newSubnet(organisation, "Inside", "10.20.5.0/24")).block();
        assertEquals("10.20.5.0/24", inside.getCidr());

        UUID racing = UUID.randomUUID();
        List<Boolean> outcomes = Flux.range(0, 20)
                .flatMap(i -> subnets.create(newSubnet(racing, "Race " + i, i % 2 == 0 ? "172.16.0.0/16" : "172.16.0.0/" + (16 + i % 8)))
                        .map(created -> true).onErrorResume(DuplicateSubnetException.class, lost -> Mono.just(false)).subscribeOn(Schedulers.parallel()), 20)
                .collectList().block();
        assertEquals(1, outcomes.stream().filter(won -> won).count());
        assertEquals(1, subnets.findAll(racing).count().block());
    }

    @Test
    @DisplayName("a reactivation whose range an active subnet now holds is undone and noted on the trail")
    void reactivationUndone() {
        UUID organisation = UUID.randomUUID();
        Subnet old = subnets.create(newSubnet(organisation, "Old", "192.168.0.0/24")).block();
        subnets.save(old, SubnetStatus.ACTIVE, old.retire("op-1")).block();
        subnets.create(newSubnet(organisation, "New", "192.168.0.0/25")).block();

        Subnet loaded = subnets.findById(old.getId(), organisation).block();
        var reactivate = loaded.reactivate("op-1");
        assertThrows(DuplicateSubnetException.class, () -> subnets.save(loaded, SubnetStatus.RETIRED, reactivate).block());

        Subnet stored = subnets.findById(old.getId(), organisation).block();
        assertEquals(SubnetStatus.RETIRED, stored.getStatus());
        assertEquals(List.of("INITIATED", "RETIRED", "REACTIVATED", "REACTIVATION_UNDONE"), stored.getAuditTrail().stream().map(e -> e.action()).toList());
    }

    @Test
    @DisplayName("an address is held once: twenty requests at once give one winner; released, it can be handed out again")
    void heldOnce() {
        UUID organisation = UUID.randomUUID();
        Subnet subnet = subnets.create(newSubnet(organisation, "Office", "10.50.0.0/24")).block();

        List<Boolean> outcomes = Flux.range(0, 20)
                .flatMap(i -> allocations.create(newAllocation(subnet, "10.50.0.10")).map(created -> true)
                        .onErrorResume(DuplicateAllocationException.class, lost -> Mono.just(false)).subscribeOn(Schedulers.parallel()), 20)
                .collectList().block();
        assertEquals(1, outcomes.stream().filter(won -> won).count());
        assertEquals(1L, allocations.countAllocated(organisation, subnet.getId()).block());

        Allocation held = allocations.findAll(organisation, subnet.getId(), AllocationStatus.ALLOCATED, null, 10).blockFirst();
        Allocation first = allocations.findById(held.getId(), organisation).block();
        Allocation second = allocations.findById(held.getId(), organisation).block();
        allocations.save(first, AllocationStatus.ALLOCATED, first.release("op-1")).block();
        var again = second.release("op-2");
        assertThrows(InvalidAllocationStatusException.class, () -> allocations.save(second, AllocationStatus.ALLOCATED, again).block());

        Allocation reused = allocations.create(newAllocation(subnet, "10.50.0.10")).block();
        assertEquals("10.50.0.10", reused.getIp());
        assertEquals(2, allocations.findAll(organisation, subnet.getId(), null, null, 10).count().block());
        assertEquals(1, allocations.findAll(organisation, subnet.getId(), AllocationStatus.RELEASED, null, 10).count().block());
        assertNull(allocations.findById(held.getId(), UUID.randomUUID()).block());
    }

    @Test
    @DisplayName("the held addresses of a subnet are counted and listed ascending, released ones and other subnets left out")
    void heldAddresses() {
        UUID organisation = UUID.randomUUID();
        Subnet subnet = subnets.create(newSubnet(organisation, "Lab", "10.60.0.0/24")).block();
        Subnet other = subnets.create(newSubnet(organisation, "Other", "10.61.0.0/24")).block();
        allocations.create(newAllocation(subnet, "10.60.0.9")).block();
        allocations.create(newAllocation(subnet, "10.60.0.2")).block();
        Allocation released = allocations.create(newAllocation(subnet, "10.60.0.5")).block();
        allocations.save(released, AllocationStatus.ALLOCATED, released.release("op-1")).block();
        allocations.create(newAllocation(other, "10.61.0.1")).block();

        assertEquals(2L, allocations.countAllocated(organisation, subnet.getId()).block());
        assertEquals(List.of(Ipv4.parse("10.60.0.2"), Ipv4.parse("10.60.0.9")), allocations.allocatedAddresses(organisation, subnet.getId()).block());
    }

    @Test
    @DisplayName("startup created the indexes, the held-address one unique and partial")
    void indexesExist() {
        List<Document> subnetIndexes = indexes("subnets");
        List<Document> allocationIndexes = indexes("allocations");
        assertTrue(subnetIndexes.stream().anyMatch(index -> "organisationId_1_name_1".equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(subnetIndexes.stream().anyMatch(index -> "organisationId_1_status_1_network_1".equals(index.getString("name"))));
        assertTrue(allocationIndexes.stream().anyMatch(index -> "organisationId_1_subnetId_1_address_1_allocated".equals(index.getString("name"))
                && Boolean.TRUE.equals(index.getBoolean("unique")) && index.get("partialFilterExpression") != null));
        assertTrue(allocationIndexes.stream().anyMatch(index -> "organisationId_1_createdAt_-1".equals(index.getString("name"))));
    }

    private List<Document> indexes(String collection) {
        return Flux.from(mongoClient.getDatabase(DATABASE).getCollection(collection).listIndexes()).map(index -> (Document) index).collectList().block();
    }
}
