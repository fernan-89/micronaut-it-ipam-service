package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateSubnetException;
import com.thinklab.domain.exception.InvalidSubnetStatusException;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubnetDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubnetDocument.SubnetPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class SubnetMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/ipam_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<SubnetDocument> collection;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("ipam_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_ipam_db")).thenReturn(database);
        when(database.getCollection("subnets", SubnetDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        when(collection.insertOne(any(SubnetDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        when(collection.deleteOne(any(Bson.class))).thenReturn(Mono.just(DeleteResult.acknowledged(1)));
    }

    private Subnet subnet(String cidr, Instant createdAt, UUID id) {
        Subnet fresh = Subnet.createNew(id, org, "Net " + cidr, cidr, 10, UUID.randomUUID(), 80, "op");
        return Subnet.reconstitute(id, org, fresh.getName(), fresh.getNetwork(), fresh.getPrefix(), 10, fresh.getSiteId(), 80, SubnetStatus.ACTIVE, createdAt, createdAt,
                fresh.getAuditTrail());
    }

    private Subnet subnet(String cidr) {
        return Subnet.createNew(UUID.randomUUID(), org, "Net " + cidr, cidr, 10, null, 80, "op");
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    /** Each find answers the next list of subnets. */
    private void finds(List<Subnet>... answers) {
        List<FindPublisher<SubnetDocument>> publishers = new ArrayList<>();
        for (List<Subnet> answer : answers) {
            FindPublisher<SubnetDocument> publisher = mock(FindPublisher.class);
            when(publisher.first()).thenReturn(publisher);
            when(publisher.sort(any(Bson.class))).thenReturn(publisher);
            doAnswer(invocation -> {
                org.reactivestreams.Subscriber<SubnetDocument> subscriber = invocation.getArgument(0);
                Flux.fromIterable(answer).map(SubnetPersistenceMapper::toDocument).subscribe(subscriber);
                return null;
            }).when(publisher).subscribe(any());
            publishers.add(publisher);
        }
        var stub = when(collection.find(any(Bson.class)));
        for (FindPublisher<SubnetDocument> publisher : publishers) {
            stub = stub.thenReturn(publisher);
        }
    }

    @Test
    @DisplayName("a subnet survives the round trip with its range as numbers and text; the document setters are plain and the mapper a utility class")
    void roundTrip() throws ReflectiveOperationException {
        Subnet subnet = subnet("10.20.0.0/24");
        subnet.retire("op");

        SubnetDocument document = SubnetPersistenceMapper.toDocument(subnet);
        Subnet back = SubnetPersistenceMapper.toDomain(document);

        assertEquals("10.20.0.0/24", document.getCidr());
        assertEquals(Ipv4.parse("10.20.0.0"), document.getNetwork());
        assertEquals(Ipv4.parse("10.20.0.255"), document.getBroadcast());
        assertEquals(24, document.getPrefix());
        assertEquals(10, document.getVlan());
        assertEquals("RETIRED", document.getStatus());
        assertEquals(org, document.getOrganisationId());
        assertEquals(subnet.getId(), document.getId());
        assertEquals(subnet.getCreatedAt(), document.getCreatedAt());
        assertEquals(subnet.getUpdatedAt(), document.getUpdatedAt());
        assertEquals(80, document.getWarnPercent());
        assertEquals(SubnetStatus.RETIRED, back.getStatus());
        assertEquals("10.20.0.0/24", back.getCidr());
        assertEquals(2, back.getAuditTrail().size());
        assertEquals(SubnetStatus.ACTIVE, back.getAuditTrail().get(1).fromStatus());
        document.setSiteId(null);
        document.setAuditTrail(new ArrayList<>());
        assertTrue(document.getAuditTrail().isEmpty());
        Constructor<SubnetPersistenceMapper> constructor = SubnetPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("a subnet that overlaps nothing, or only later ones, stays; the overlap query is the tenant's active ranges that touch it")
    void createStays() {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        Subnet subnet = subnet("10.20.0.0/24", now, UUID.randomUUID());
        finds(List.of(), List.of(subnet("10.20.0.0/16", now.plusSeconds(1), UUID.randomUUID())));
        SubnetMongoRepositoryAdapter adapter = new SubnetMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(subnet)).expectNext(subnet).verifyComplete();
        StepVerifier.create(adapter.create(subnet)).expectNext(subnet).verifyComplete();

        verify(collection, never()).deleteOne(any(Bson.class));
        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).find(filter.capture());
        String text = filter.getAllValues().get(0).toString();
        assertTrue(text.contains("network") && text.contains("broadcast") && text.contains("ACTIVE") && text.contains("organisationId"));
    }

    @Test
    @DisplayName("a subnet that overlaps an earlier active one removes itself and answers a duplicate that names the other range; a tie goes to the smaller id")
    void createLoses() {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        UUID small = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID large = UUID.fromString("20000000-0000-0000-0000-000000000001");
        Subnet subnet = subnet("10.20.0.0/24", now, large);
        finds(List.of(subnet("10.20.0.0/16", now.minusSeconds(1), UUID.randomUUID())), List.of(subnet("10.20.0.0/25", now, small)));
        SubnetMongoRepositoryAdapter adapter = new SubnetMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(subnet)).expectErrorSatisfies(error -> {
            assertTrue(error instanceof DuplicateSubnetException);
            assertTrue(error.getMessage().contains("10.20.0.0/16"));
        }).verify();
        StepVerifier.create(adapter.create(subnet)).expectError(DuplicateSubnetException.class).verify();
        verify(collection, times(2)).deleteOne(any(Bson.class));
        assertTrue(SubnetMongoRepositoryAdapter.isEarlier(subnet("10.0.0.0/24", now, small), subnet("10.0.0.0/24", now, large)));
        assertFalse(SubnetMongoRepositoryAdapter.isEarlier(subnet("10.0.0.0/24", now, large), subnet("10.0.0.0/24", now, small)));
        assertFalse(SubnetMongoRepositoryAdapter.isEarlier(subnet("10.0.0.0/24", now.plusSeconds(1), small), subnet("10.0.0.0/24", now, large)));
    }

    @Test
    @DisplayName("a name already used is a duplicate; any other write error passes through; the lookups are scoped to the tenant; the URI may omit the database")
    void namesAndLookups() {
        Subnet subnet = subnet("10.20.0.0/24");
        when(collection.insertOne(any(SubnetDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        finds(List.of(subnet), List.of(subnet));
        SubnetMongoRepositoryAdapter adapter = new SubnetMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(subnet)).expectErrorSatisfies(error -> assertTrue(error.getMessage().contains("named"))).verify();
        StepVerifier.create(adapter.create(subnet)).expectError(MongoWriteException.class).verify();
        StepVerifier.create(adapter.findById(subnet.getId(), org)).assertNext(found -> assertEquals(subnet.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org)).expectNextCount(1).verifyComplete();
        assertThrows(NullPointerException.class, () -> new SubnetMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict and a duplicate name is a 409")
    void save() {
        Subnet subnet = subnet("10.20.0.0/24");
        var entry = subnet.update("Renamed", 20, null, 90, "op");
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)))
                .thenReturn(Mono.error(writeError(11000)))
                .thenReturn(Mono.error(writeError(2)));
        SubnetMongoRepositoryAdapter adapter = new SubnetMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(subnet, SubnetStatus.ACTIVE, entry)).verifyComplete();
        StepVerifier.create(adapter.save(subnet, SubnetStatus.ACTIVE, entry)).expectError(InvalidSubnetStatusException.class).verify();
        StepVerifier.create(adapter.save(subnet, SubnetStatus.ACTIVE, entry)).expectError(DuplicateSubnetException.class).verify();
        StepVerifier.create(adapter.save(subnet, SubnetStatus.ACTIVE, entry)).expectError(MongoWriteException.class).verify();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(4)).updateOne(any(Bson.class), update.capture());
        String text = update.getAllValues().get(0).toString();
        for (String field : new String[]{"name", "vlan", "siteId", "warnPercent", "status", "auditTrail"}) {
            assertTrue(text.contains(field), field);
        }
        verify(collection, never()).find(any(Bson.class));
    }

    @Test
    @DisplayName("a reactivation that overlaps an active subnet is undone (back to RETIRED, noted on the trail) and answered as a duplicate; without an overlap it stays")
    void reactivation() {
        Subnet subnet = subnet("10.20.0.0/24");
        subnet.retire("op");
        var entry = subnet.reactivate("op");
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        finds(List.of(subnet("10.20.0.0/23")), List.of());
        SubnetMongoRepositoryAdapter adapter = new SubnetMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(subnet, SubnetStatus.RETIRED, entry)).expectError(DuplicateSubnetException.class).verify();
        StepVerifier.create(adapter.save(subnet, SubnetStatus.RETIRED, entry)).verifyComplete();
        Subnet retired = subnet("10.30.0.0/24");
        retired.retire("op");
        StepVerifier.create(adapter.save(retired, SubnetStatus.RETIRED, retired.update("Still retired", null, null, 80, "op"))).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(4)).updateOne(any(Bson.class), update.capture());
        String undo = update.getAllValues().get(1).toString();
        assertTrue(undo.contains("REACTIVATION_UNDONE") && undo.contains("RETIRED"));
    }
}
