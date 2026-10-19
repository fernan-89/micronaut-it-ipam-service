package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** A subnet and how full it is right now: {@code level}, {@code used}, {@code free} and {@code usedPercent} are judged when read, never stored. */
@Serdeable
public record SubnetResponse(UUID id, UUID organisationId, String name, String cidr, String network, String broadcast, int prefix, @Nullable Integer vlan, @Nullable UUID siteId,
                             int warnPercent, String status, String level, long used, long usable, long free, @Nullable Double usedPercent, Instant createdAt, Instant updatedAt) {}
