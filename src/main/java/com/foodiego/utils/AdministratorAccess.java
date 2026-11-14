package com.foodiego.utils;

import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class AdministratorAccess {
    private final Set<Long> userIds;

    public AdministratorAccess(@Value("${foodiego.security.admin-user-ids:}") String configuredIds) {
        userIds = Arrays.stream(configuredIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(Long userId) {
        return userId != null && userIds.contains(userId);
    }
}
