package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates the indexes at startup. The UNIQUE ones are the atomic backstops (ADR-033): a subnet name per organisation, and one ALLOCATED holder per
 * address of a subnet (a partial index, so released allocations stay as history and the address can be handed out again). The other two serve
 * the overlap check and the allocation list. Fail-open: {@code createIndex} is idempotent, a failure is logged and the application still starts.
 * Turn it off with {@code thinklab.mongo.create-indexes=false}.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class IpamIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String SUBNET_NAME_INDEX = "organisationId_1_name_1";
    static final String SUBNET_RANGE_INDEX = "organisationId_1_status_1_network_1";
    static final String ALLOCATION_HELD_INDEX = "organisationId_1_subnetId_1_address_1_allocated";
    static final String ALLOCATION_LIST_INDEX = "organisationId_1_createdAt_-1";

    private static final Logger log = LoggerFactory.getLogger(IpamIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public IpamIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    IpamIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : SubnetMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        String subnets = SubnetMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(subnets, SUBNET_NAME_INDEX, new Document("organisationId", 1).append("name", 1), new IndexOptions().unique(true));
        ensureIndex(subnets, SUBNET_RANGE_INDEX, new Document("organisationId", 1).append("status", 1).append("network", 1), new IndexOptions());
        String allocations = AllocationMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(allocations, ALLOCATION_HELD_INDEX, new Document("organisationId", 1).append("subnetId", 1).append("address", 1),
                new IndexOptions().unique(true).partialFilterExpression(new Document("status", "ALLOCATED")));
        ensureIndex(allocations, ALLOCATION_LIST_INDEX, new Document("organisationId", 1).append("createdAt", -1), new IndexOptions());
    }

    private void ensureIndex(String collection, String indexName, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options.name(indexName))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", indexName, database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", indexName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", indexName, database, collection, e.getMessage());
        }
    }
}
