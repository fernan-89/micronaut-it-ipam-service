package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoServerException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
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
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the Subnet aggregate, raw reactive-streams driver. Every change is one atomic update guarded by the
 * status loaded (ADR-033) that also appends the audit entry; a name unique per organisation is a unique index. A range cannot be a unique index,
 * so overlaps are settled after the write, by an order the STORE gives (ADR-033): once a new subnet is inserted it takes a ticket from an atomic
 * counter of the organisation and keeps it; of two overlapping subnets the one with the lower ticket stays, so exactly one of any number of
 * simultaneous creations survives. The order is not the creation time in the caller's memory: that is taken before the write, and a subnet created
 * earlier that reaches the store later was not seen by one created later that had already looked (found by the live smoke). A reactivated subnet that
 * overlaps an ACTIVE one goes back to RETIRED.
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
    private static final String FIELD_SEQUENCE = "sequence";
    static final String SEQUENCES_COLLECTION = "subnet_sequences";
    /** A subnet that has no ticket yet and was created within this long is being created right now: its place in the order is still to be decided. */
    static final Duration IN_FLIGHT = Duration.ofSeconds(5);
    static final Duration SETTLE_POLL = Duration.ofMillis(20);
    static final int SETTLE_ATTEMPTS = 250;

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    private final Duration settlePoll;
    private final int settleAttempts;

    public SubnetMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, SETTLE_POLL, SETTLE_ATTEMPTS);
    }

    SubnetMongoRepositoryAdapter(MongoClient mongoClient, String mongoUri, Duration settlePoll, int settleAttempts) {
        this.settlePoll = settlePoll;
        this.settleAttempts = settleAttempts;
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<Document> getSequences() {
        return mongoClient.getDatabase(database).getCollection(SEQUENCES_COLLECTION);
    }

    private MongoCollection<SubnetDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, SubnetDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Subnet> create(Subnet subnet) {
        log.debug("[PERSISTENCE] Monolithic create for Subnet Aggregate: {}", subnet.getId());
        return Mono.from(getCollection().insertOne(SubnetPersistenceMapper.toDocument(subnet)))
                .onErrorMap(MongoWriteException.class, error -> duplicate(error, subnet))
                .then(Mono.defer(() -> takeTicket(subnet.getOrganisationId())))
                .flatMap(ticket -> Mono.from(getCollection().updateOne(Filters.eq(FIELD_ID, subnet.getId()), Updates.set(FIELD_SEQUENCE, ticket))).thenReturn(ticket))
                .flatMap(ticket -> settle(subnet, ticket, settleAttempts));
    }

    /** The next ticket of the organisation: one atomic increment, the same order for every instance. The counter is created by the first one; if two create it at once, the loser asks again. */
    private Mono<Long> takeTicket(UUID organisationId) {
        Mono<Long> next = Mono.defer(() -> Mono.from(getSequences().findOneAndUpdate(Filters.eq(FIELD_ID, organisationId), Updates.inc("value", 1L),
                        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER))).map(counter -> counter.getLong("value")));
        return next.onErrorResume(error -> error instanceof MongoServerException server && server.getCode() == DUPLICATE_KEY, error -> next);
    }

    /**
     * Decides, after the write and the ticket, whether the new subnet stays. The ACTIVE subnets that touch it are read: a subnet created in the last few
     * seconds that has no ticket yet is still being created, so the read is repeated until it has one (it needs nothing from this subnet to get it, so
     * nobody waits for each other); then the subnet leaves, removing itself and answering a duplicate, if any of them has a lower ticket. A subnet without a
     * ticket that is not being created is older than every ticket (created before tickets existed, or abandoned) and also comes first.
     */
    private Mono<Subnet> settle(Subnet subnet, long ticket, int attemptsLeft) {
        return overlappingDocuments(subnet).collectList().flatMap(others -> {
            if (attemptsLeft > 0 && others.stream().anyMatch(other -> other.getSequence() == null && inFlight(other))) {
                return Mono.delay(settlePoll).then(Mono.defer(() -> settle(subnet, ticket, attemptsLeft - 1)));
            }
            return others.stream().filter(other -> other.getSequence() == null || other.getSequence() < ticket).findFirst()
                    .map(first -> Mono.from(getCollection().deleteOne(Filters.eq(FIELD_ID, subnet.getId()))).<Subnet>then(Mono.error(overlap(subnet, SubnetPersistenceMapper.toDomain(first)))))
                    .orElseGet(() -> Mono.just(subnet));
        });
    }

    private static boolean inFlight(SubnetDocument document) {
        return document.getCreatedAt().isAfter(Instant.now().minus(IN_FLIGHT));
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

    /** The ACTIVE subnets of the tenant, other than this one, that share at least one address with it, as stored. */
    private Flux<SubnetDocument> overlappingDocuments(Subnet subnet) {
        Bson filter = Filters.and(Filters.eq(FIELD_ORGANISATION, subnet.getOrganisationId()), Filters.eq(FIELD_STATUS, SubnetStatus.ACTIVE.name()),
                Filters.ne(FIELD_ID, subnet.getId()), Filters.lte("network", subnet.getBroadcast()), Filters.gte("broadcast", subnet.getNetwork()));
        return Flux.from(getCollection().find(filter));
    }

    private Flux<Subnet> overlapping(Subnet subnet) {
        return overlappingDocuments(subnet).map(SubnetPersistenceMapper::toDomain);
    }

    private Mono<UpdateResult> undoReactivation(Subnet subnet, SubnetAuditEntry reactivation) {
        Bson update = Updates.combine(Updates.set(FIELD_STATUS, SubnetStatus.RETIRED.name()), Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", new AuditEntryDocument(Instant.now(), "REACTIVATION_UNDONE", reactivation.executor(), SubnetStatus.ACTIVE.name(),
                        SubnetStatus.RETIRED.name(), "Reactivation undone: the range overlaps an active subnet.")));
        return Mono.from(getCollection().updateOne(Filters.and(Filters.eq(FIELD_ID, subnet.getId()), Filters.eq(FIELD_STATUS, SubnetStatus.ACTIVE.name())), update));
    }

    private static DuplicateSubnetException overlap(Subnet subnet, Subnet other) {
        return new DuplicateSubnetException("The range " + subnet.getCidr() + " overlaps the active subnet " + other.getCidr() + ".");
    }

    private static Throwable duplicate(MongoWriteException error, Subnet subnet) {
        return error.getError().getCode() == DUPLICATE_KEY
                ? new DuplicateSubnetException("A subnet named '" + subnet.getName() + "' already exists in this organisation.") : error;
    }
}
