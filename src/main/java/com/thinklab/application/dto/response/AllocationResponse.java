package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record AllocationResponse(UUID id, UUID organisationId, UUID subnetId, String ip, @Nullable UUID assetId, @Nullable String hostname, String status, Instant createdAt,
                                 Instant updatedAt) {}
