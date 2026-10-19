package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateSubnetException;
import com.thinklab.domain.exception.InvalidSubnetStatusException;
import com.thinklab.domain.model.Subnet;
import com.thinklab.domain.model.Subnet.SubnetAuditEntry;
import com.thinklab.domain.model.Subnet.SubnetStatus;
import com.thinklab.domain.repository.SubnetRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubnetDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubnetDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.SubnetDocument.SubnetPersistenceMapper;
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
 * MongoDB Reactive Repository Adapter for the Subnet aggregate, raw reactive-streams driver. Every change is one atomic update guarded by the
 * status loaded (ADR-033) that also appends the audit entry; a name unique per organisation is a unique index. A range cannot be a unique index,
 * so overlaps are settled after the write: a new subnet that overlaps an ACTIVE one is removed again unless it is the earlier of the two (by
 * creation time, then id), which leaves exactly one of any number of simultaneous creations; a reactivated subnet that overlaps an ACTIVE one goes
 * back to RETIRED.
 */
@Singleton
public class SubnetMongoRepositoryAdapter implements SubnetRepository {

    private static final Logger log = LoggerFactory.getLogger(SubnetMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_ipam_db";
    static final String COLLECTION_NAME = "subnets";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public SubnetMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<SubnetDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, SubnetDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Subnet> create(Subnet subnet) {
        log.debug("[PERSISTENCE] Monolithic create for Subnet Aggregate: {}", subnet.getId());
        return Mono.from(getCollection().insertOne(SubnetPersistenceMapper.toDocument(subnet)))
                .onErrorMap(MongoWriteException.class, error -> duplicate(error, subnet))
                .then(overlapping(subnet).collectList())
                .flatMap(others -> {
                    List<Subnet> earlier = others.stream().filter(other -> isEarlier(other, subnet)).toList();
                    if (earlier.isEmpty()) {
                        return Mono.just(subnet);
                    }
                    return Mono.from(getCollection().deleteOne(Filters.eq(FIELD_ID, subnet.getId())))
                            .then(Mono.error(overlap(subnet, earlier.get(0))));
                });
    }

    @Override
    public Mono<Subnet> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(SubnetPersistenceMapper::toDomain);
    }

    @Override
    public Flux<Subnet> findAll(UUID organisationId) {
        return Flux.from(getCollection().find(Filters.eq(FIELD_ORGANISATION, organisationId)).sort(Sorts.ascending("createdAt"))).map(SubnetPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(Subnet subnet, SubnetStatus expectedStatus, SubnetAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, subnet.getId()), Filters.eq(FIELD_ORGANISATION, subnet.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set("name", subnet.getName()),
                Updates.set("vlan", subnet.getVlan()),
                Updates.set("siteId", subnet.getSiteId()),
                Updates.set("warnPercent", subnet.getWarnPercent()),
                Updates.set(FIELD_STATUS, subnet.getStatus().name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        Mono<Void> written = Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidSubnetStatusException("Subnet was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty())
                .onErrorMap(MongoWriteException.class, error -> duplicate(error, subnet));
        if (expectedStatus != SubnetStatus.RETIRED || subnet.getStatus() != SubnetStatus.ACTIVE) {
            return written;
        }
        return written.then(overlapping(subnet).next())
                .flatMap(other -> undoReactivation(subnet, auditEntry).then(Mono.<Void>error(overlap(subnet, other))));
    }

    /** The ACTIVE subnets of the tenant, other than this one, that share at least one address with it. */
    private Flux<Subnet> overlapping(Subnet subnet) {
        Bson filter = Filters.and(Filters.eq(FIELD_ORGANISATION, subnet.getOrganisationId()), Filters.eq(FIELD_STATUS, SubnetStatus.ACTIVE.name()),
                Filters.ne(FIELD_ID, subnet.getId()), Filters.lte("network", subnet.getBroadcast()), Filters.gte("broadcast", subnet.getNetwork()));
        return Flux.from(getCollection().find(filter)).map(SubnetPersistenceMapper::toDomain);
    }

    private Mono<UpdateResult> undoReactivation(Subnet subnet, SubnetAuditEntry reactivation) {
        Bson update = Updates.combine(Updates.set(FIELD_STATUS, SubnetStatus.RETIRED.name()), Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", new AuditEntryDocument(Instant.now(), "REACTIVATION_UNDONE", reactivation.executor(), SubnetStatus.ACTIVE.name(),
                        SubnetStatus.RETIRED.name(), "Reactivation undone: the range overlaps an active subnet.")));
        return Mono.from(getCollection().updateOne(Filters.and(Filters.eq(FIELD_ID, subnet.getId()), Filters.eq(FIELD_STATUS, SubnetStatus.ACTIVE.name())), update));
    }

    /** The order that decides which of two overlapping new subnets stays: the one created first, and on a tie the smaller id. */
    static boolean isEarlier(Subnet other, Subnet subnet) {
        int byTime = other.getCreatedAt().compareTo(subnet.getCreatedAt());
        return byTime < 0 || (byTime == 0 && other.getId().compareTo(subnet.getId()) < 0);
    }

    private static DuplicateSubnetException overlap(Subnet subnet, Subnet other) {
        return new DuplicateSubnetException("The range " + subnet.getCidr() + " overlaps the active subnet " + other.getCidr() + ".");
    }

    private static Throwable duplicate(MongoWriteException error, Subnet subnet) {
        return error.getError().getCode() == DUPLICATE_KEY
                ? new DuplicateSubnetException("A subnet named '" + subnet.getName() + "' already exists in this organisation.") : error;
    }
}
