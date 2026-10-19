package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateAllocationException;
import com.thinklab.domain.exception.InvalidAllocationStatusException;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.model.Subnet;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AllocationDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AllocationDocument.AllocationPersistenceMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AllocationMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/ipam_test";

    private final UUID org = UUID.randomUUID();
    private final Subnet subnet = Subnet.createNew(UUID.randomUUID(), org, "Office", "10.20.0.0/24", null, null, 80, "op");
    private MongoClient client;
    private MongoCollection<AllocationDocument> collection;
    private FindPublisher<AllocationDocument> publisher;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        publisher = mock(FindPublisher.class);
        when(client.getDatabase("ipam_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_ipam_db")).thenReturn(database);
        when(database.getCollection("allocations", AllocationDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        when(publisher.limit(anyInt())).thenReturn(publisher);
        when(publisher.projection(any(Bson.class))).thenReturn(publisher);
    }

    private Allocation allocation(String ip) {
        return Allocation.createNew(UUID.randomUUID(), subnet, Ipv4.parse(ip), UUID.randomUUID(), "host-1", "op");
    }

    private void answers(Allocation... found) {
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<AllocationDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(AllocationPersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    @Test
    @DisplayName("an allocation survives the round trip with its address as a number and as text; the document setters are plain and the mapper a utility class")
    void roundTrip() throws ReflectiveOperationException {
        Allocation allocation = allocation("10.20.0.7");
        allocation.release("op");

        AllocationDocument document = AllocationPersistenceMapper.toDocument(allocation);
        Allocation back = AllocationPersistenceMapper.toDomain(document);

        assertEquals(Ipv4.parse("10.20.0.7"), document.getAddress());
        assertEquals("10.20.0.7", document.getIp());
        assertEquals("host-1", document.getHostname());
        assertEquals(allocation.getAssetId(), document.getAssetId());
        assertEquals(subnet.getId(), document.getSubnetId());
        assertEquals("RELEASED", document.getStatus());
        assertEquals(org, document.getOrganisationId());
        assertEquals(allocation.getId(), document.getId());
        assertEquals(allocation.getCreatedAt(), document.getCreatedAt());
        assertEquals(allocation.getUpdatedAt(), document.getUpdatedAt());
        assertEquals(AllocationStatus.RELEASED, back.getStatus());
        assertEquals("10.20.0.7", back.getIp());
        assertEquals(2, back.getAuditTrail().size());
        assertEquals(AllocationStatus.ALLOCATED, back.getAuditTrail().get(1).fromStatus());
        document.setAuditTrail(new ArrayList<>());
        assertTrue(document.getAuditTrail().isEmpty());
        Constructor<AllocationPersistenceMapper> constructor = AllocationPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("an address already held is a duplicate that names it; any other write error passes through; the URI may omit the database")
    void create() {
        Allocation allocation = allocation("10.20.0.7");
        when(collection.insertOne(any(AllocationDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))))
                .thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        AllocationMongoRepositoryAdapter adapter = new AllocationMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(allocation)).expectNext(allocation).verifyComplete();
        StepVerifier.create(adapter.create(allocation)).expectErrorSatisfies(error -> {
            assertTrue(error instanceof DuplicateAllocationException);
            assertTrue(error.getMessage().contains("10.20.0.7"));
        }).verify();
        StepVerifier.create(adapter.create(allocation)).expectError(MongoWriteException.class).verify();
        assertThrows(NullPointerException.class, () -> new AllocationMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("the lookups are scoped to the tenant; the list takes the filters it is given, newest first, limited")
    void lookups() {
        Allocation allocation = allocation("10.20.0.7");
        answers(allocation);
        AllocationMongoRepositoryAdapter adapter = new AllocationMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findById(allocation.getId(), org)).assertNext(found -> assertEquals(allocation.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org, null, null, null, 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(org, subnet.getId(), AllocationStatus.ALLOCATED, allocation.getAssetId(), 7)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(3)).find(filter.capture());
        assertTrue(!filter.getAllValues().get(1).toString().contains("subnetId"));
        String full = filter.getAllValues().get(2).toString();
        assertTrue(full.contains("subnetId") && full.contains("ALLOCATED") && full.contains("assetId"));
        verify(publisher).limit(5);
        verify(publisher).limit(7);
    }

    @Test
    @DisplayName("the held addresses of a subnet are counted and listed in ascending order, only the ALLOCATED ones")
    void held() {
        answers(allocation("10.20.0.2"), allocation("10.20.0.9"));
        when(collection.countDocuments(any(Bson.class))).thenReturn(Mono.just(2L));
        AllocationMongoRepositoryAdapter adapter = new AllocationMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.countAllocated(org, subnet.getId())).expectNext(2L).verifyComplete();
        StepVerifier.create(adapter.allocatedAddresses(org, subnet.getId())).expectNext(List.of(Ipv4.parse("10.20.0.2"), Ipv4.parse("10.20.0.9"))).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).countDocuments(filter.capture());
        assertTrue(filter.getValue().toString().contains("ALLOCATED"));
    }

    @Test
    @DisplayName("a release is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        Allocation allocation = allocation("10.20.0.7");
        var entry = allocation.release("op");
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        AllocationMongoRepositoryAdapter adapter = new AllocationMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(allocation, AllocationStatus.ALLOCATED, entry)).verifyComplete();
        StepVerifier.create(adapter.save(allocation, AllocationStatus.ALLOCATED, entry)).expectError(InvalidAllocationStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("ALLOCATED"));
        assertTrue(update.getAllValues().get(0).toString().contains("RELEASED") && update.getAllValues().get(0).toString().contains("auditTrail"));
    }
}
