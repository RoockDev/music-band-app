package com.banda.groups.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateGroupRequest(
        @NotBlank String name,
        String description
) {
}
