package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTO for {@code update}: everything but the range, which never changes (a VLAN or site left out is cleared). */
@Serdeable
public record UpdateSubnetRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @Nullable @Min(value = 1, message = "The VLAN must be at least 1") @Max(value = 4094, message = "The VLAN must not exceed 4094") Integer vlan,
        @Nullable UUID siteId,
        @NotNull(message = "The warning use is required") @Min(value = 1, message = "The warning use must be at least 1 percent") @Max(value = 100, message = "The warning use must not exceed 100 percent")
        Integer warnPercent
) {}
