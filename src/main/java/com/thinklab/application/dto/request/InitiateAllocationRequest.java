package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * DTO for {@code allocation/initiate}: an address of the subnet. With {@code ip} that address is asked for; without it the lowest free one is
 * handed out. The asset and the hostname (a DNS name) are optional.
 */
@Serdeable
public record InitiateAllocationRequest(
        @NotNull(message = "The subnet is required") UUID subnetId,
        @Nullable @Size(max = 15, message = "The address must not exceed 15 characters") String ip,
        @Nullable UUID assetId,
        @Nullable @Size(max = 253, message = "The hostname must not exceed 253 characters") String hostname
) {}
