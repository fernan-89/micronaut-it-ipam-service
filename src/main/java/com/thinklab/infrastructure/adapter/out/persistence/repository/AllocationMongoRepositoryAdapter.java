package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateAllocationException;
import com.thinklab.domain.model.Ipv4;
import com.thinklab.domain.exception.InvalidAllocationStatusException;
import com.thinklab.domain.model.Allocation;
import com.thinklab.domain.model.Allocation.AllocationAuditEntry;
import com.thinklab.domain.model.Allocation.AllocationStatus;
import com.thinklab.domain.repository.AllocationRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AllocationDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AllocationDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AllocationDocument.AllocationPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the Allocation aggregate, raw reactive-streams driver. An address is held once: a partial unique index on
 * {@code (organisation, subnet, address)} over the ALLOCATED ones is the atomic backstop (ADR-033), so two simultaneous requests for one address
 * get one winner, and a released address can be handed out again. A release is one atomic update guarded by the status loaded.
 */
@Singleton
public class AllocationMongoRepositoryAdapter implements AllocationRepository {

    private static final Logger log = LoggerFactory.getLogger(AllocationMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_ipam_db";
    static final String COLLECTION_NAME = "allocations";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_SUBNET = "subnetId";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_ADDRESS = "address";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public AllocationMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<AllocationDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, AllocationDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Allocation> create(Allocation allocation) {
        log.debug("[PERSISTENCE] Monolithic create for Allocation Aggregate: {}", allocation.getId());
        return Mono.from(getCollection().insertOne(AllocationPersistenceMapper.toDocument(allocation)))
                .map(result -> allocation)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == DUPLICATE_KEY
                        ? new DuplicateAllocationException("The address " + Ipv4.format(allocation.getAddress()) + " is already allocated in this subnet.") : error);
    }

    @Override
    public Mono<Allocation> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(AllocationPersistenceMapper::toDomain);
    }

    @Override
    public Flux<Allocation> findAll(UUID organisationId, UUID subnetId, AllocationStatus status, UUID assetId, int limit) {
        List<Bson> filters = new java.util.ArrayList<>(List.of(Filters.eq(FIELD_ORGANISATION, organisationId)));
        if (subnetId != null) {
            filters.add(Filters.eq(FIELD_SUBNET, subnetId));
        }
        if (status != null) {
            filters.add(Filters.eq(FIELD_STATUS, status.name()));
        }
        if (assetId != null) {
            filters.add(Filters.eq("assetId", assetId));
        }
        return Flux.from(getCollection().find(Filters.and(filters)).sort(Sorts.descending("createdAt")).limit(limit)).map(AllocationPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Long> countAllocated(UUID organisationId, UUID subnetId) {
        return Mono.from(getCollection().countDocuments(allocatedIn(organisationId, subnetId)));
    }

    @Override
    public Mono<List<Long>> allocatedAddresses(UUID organisationId, UUID subnetId) {
        return Flux.from(getCollection().find(allocatedIn(organisationId, subnetId)).projection(Projections.include(FIELD_ADDRESS)).sort(Sorts.ascending(FIELD_ADDRESS)))
                .map(AllocationDocument::getAddress)
                .collectList();
    }

    @Override
    public Mono<Void> save(Allocation allocation, AllocationStatus expectedStatus, AllocationAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, allocation.getId()), Filters.eq(FIELD_ORGANISATION, allocation.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set(FIELD_STATUS, allocation.getStatus().name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidAllocationStatusException("Allocation was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty());
    }

    private static Bson allocatedIn(UUID organisationId, UUID subnetId) {
        return Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_SUBNET, subnetId), Filters.eq(FIELD_STATUS, AllocationStatus.ALLOCATED.name()));
    }
}
