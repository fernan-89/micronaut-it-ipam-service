package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTO for {@code initiate}: an IPv4 range ({@code 10.20.0.0/24}, the network address), its name, an optional VLAN and site, and when it is nearly full. */
@Serdeable
public record InitiateSubnetRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @NotBlank(message = "The CIDR is required") @Size(max = 18, message = "The CIDR must not exceed 18 characters") String cidr,
        @Nullable @Min(value = 1, message = "The VLAN must be at least 1") @Max(value = 4094, message = "The VLAN must not exceed 4094") Integer vlan,
        @Nullable UUID siteId,
        @NotNull(message = "The warning use is required") @Min(value = 1, message = "The warning use must be at least 1 percent") @Max(value = 100, message = "The warning use must not exceed 100 percent")
        Integer warnPercent
) {}
