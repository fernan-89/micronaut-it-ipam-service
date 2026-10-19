package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class IpamIndexInitializerTest {

    private final MongoClient client = mock(MongoClient.class);

    @Test
    @DisplayName("startup creates the unique subnet name, the range index, the partial unique held-address index and the allocation list index")
    void indexes() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> subnets = mock(MongoCollection.class);
        MongoCollection<Document> allocations = mock(MongoCollection.class);
        when(client.getDatabase("tenant_ipam")).thenReturn(database);
        when(database.getCollection("subnets")).thenReturn(subnets);
        when(database.getCollection("allocations")).thenReturn(allocations);
        when(subnets.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        when(allocations.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new IpamIndexInitializer(client, "mongodb://mongo:27017/tenant_ipam").onApplicationEvent(mock(StartupEvent.class));

        ArgumentCaptor<IndexOptions> subnetOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(subnets, times(2)).createIndex(any(), subnetOptions.capture());
        assertTrue(subnetOptions.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_name_1", subnetOptions.getAllValues().get(0).getName());
        assertFalse(subnetOptions.getAllValues().get(1).isUnique());
        assertEquals("organisationId_1_status_1_network_1", subnetOptions.getAllValues().get(1).getName());
        ArgumentCaptor<IndexOptions> allocationOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(allocations, times(2)).createIndex(any(), allocationOptions.capture());
        assertTrue(allocationOptions.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_subnetId_1_address_1_allocated", allocationOptions.getAllValues().get(0).getName());
        assertEquals("ALLOCATED", ((Document) allocationOptions.getAllValues().get(0).getPartialFilterExpression()).getString("status"));
        assertFalse(allocationOptions.getAllValues().get(1).isUnique());
        assertEquals("organisationId_1_createdAt_-1", allocationOptions.getAllValues().get(1).getName());
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated; the URI may omit the database; arguments are null-checked")
    void failOpen() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("thinklab_it_ipam_db")).thenReturn(database);
        when(database.getCollection(any(String.class))).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("rejected"))).thenReturn(Mono.just("ok"));
        var initializer = new IpamIndexInitializer(client, "mongodb://mongo:27017");

        initializer.onApplicationEvent(mock(StartupEvent.class));

        verify(collection, times(4)).createIndex(any(), any(IndexOptions.class));
        assertThrows(NullPointerException.class, () -> initializer.onApplicationEvent(null));
        assertThrows(NullPointerException.class, () -> new IpamIndexInitializer(null, "mongodb://mongo:27017"));
        assertThrows(NullPointerException.class, () -> new IpamIndexInitializer(client, null));
    }
}
