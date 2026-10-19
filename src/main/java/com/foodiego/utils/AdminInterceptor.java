package com.foodiego.utils;

import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Management writes are denied unless the user is explicitly configured. */
public class AdminInterceptor implements HandlerInterceptor {
    private final Set<Long> adminUserIds;
    private final boolean allowPublicReads;

    public AdminInterceptor(String configuredIds) {
        this(configuredIds, true);
    }

    public AdminInterceptor(String configuredIds, boolean allowPublicReads) {
        this.allowPublicReads = allowPublicReads;
        adminUserIds = Arrays.stream(configuredIds.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(Long::valueOf).collect(Collectors.toSet());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (allowPublicReads && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        if (!adminUserIds.contains(UserHolder.getUser().getId())) {
            response.setStatus(403);
            return false;
        }
        return true;
    }
}
