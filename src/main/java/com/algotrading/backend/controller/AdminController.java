package com.algotrading.backend.controller;

import com.algotrading.backend.dto.CreateUserRequest;
import com.algotrading.backend.dto.UpdateUserRequest;
import com.algotrading.backend.model.PlatformUser;
import com.algotrading.backend.service.UserRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin API for managing platform subscribers.
 * All endpoints require ROLE_ADMIN.
 *
 * Workflow for onboarding a new paying customer:
 *   POST /api/admin/users  → creates account
 *   PUT  /api/admin/users/{username} → extend plan / change lot size
 *   DELETE /api/admin/users/{username} → revoke access
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final UserRegistryService userRegistry;

    /** List all users (passwords masked) */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> result = userRegistry.findAll().stream()
                .map(this::toSafeMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /** Get a single user */
    @GetMapping("/users/{username}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String username) {
        return userRegistry.findByUsername(username)
                .map(u -> ResponseEntity.ok(toSafeMap(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new subscriber.
     * Example body:
     * {
     *   "username": "rahul",
     *   "password": "pass123",
     *   "maxLotSize": 2,
     *   "planExpiryDate": "2025-09-30",
     *   "notes": "Paid ₹3000 for 3 months",
     *   "kiteApiKey": "abcd1234",
     *   "kiteApiSecret": "xyz9876"
     * }
     */
    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody CreateUserRequest req) {
        PlatformUser proto = PlatformUser.builder()
                .username(req.getUsername())
                .role(req.getRole())
                .maxLotSize(req.getMaxLotSize())
                .planExpiryDate(req.getPlanExpiryDate())
                .notes(req.getNotes())
                .kiteApiKey(req.getKiteApiKey())
                .kiteApiSecret(req.getKiteApiSecret())
                .kiteRedirectUrl(req.getKiteRedirectUrl())
                .build();
        PlatformUser created = userRegistry.create(proto, req.getPassword());
        log.info("Admin created user: {}", created.getUsername());
        return ResponseEntity.ok(toSafeMap(created));
    }

    /**
     * Update subscriber — extend plan, change lot size, reset password, etc.
     * Only non-null / non-zero fields are applied.
     */
    @PutMapping("/users/{username}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable String username,
                                                           @RequestBody UpdateUserRequest req) {
        PlatformUser patch = PlatformUser.builder()
                .role(req.getRole())
                .maxLotSize(req.getMaxLotSize())
                .planExpiryDate(req.getPlanExpiryDate())
                .enabled(req.isEnabled())
                .notes(req.getNotes())
                .kiteApiKey(req.getKiteApiKey())
                .kiteApiSecret(req.getKiteApiSecret())
                .kiteAccessToken(req.getKiteAccessToken())
                .kiteRedirectUrl(req.getKiteRedirectUrl())
                .build();
        PlatformUser updated = userRegistry.update(username, patch, req.getNewPassword());
        return ResponseEntity.ok(toSafeMap(updated));
    }

    /** Revoke a subscriber's access */
    @DeleteMapping("/users/{username}")
    public ResponseEntity<String> deleteUser(@PathVariable String username) {
        userRegistry.delete(username);
        return ResponseEntity.ok("Deleted: " + username);
    }

    /** Quick plan extension helper */
    @PostMapping("/users/{username}/extend-plan")
    public ResponseEntity<Map<String, Object>> extendPlan(
            @PathVariable String username,
            @RequestBody Map<String, String> body) {
        String expiryStr = body.get("planExpiryDate");
        if (expiryStr == null) return ResponseEntity.badRequest().build();
        PlatformUser patch = PlatformUser.builder()
                .planExpiryDate(java.time.LocalDate.parse(expiryStr))
                .enabled(true)
                .build();
        PlatformUser updated = userRegistry.update(username, patch, null);
        return ResponseEntity.ok(toSafeMap(updated));
    }

    /** Returns a sanitised view — no passwordHash, no secret keys in list response */
    private Map<String, Object> toSafeMap(PlatformUser u) {
        return Map.of(
            "id",              u.getId() != null ? u.getId() : "",
            "username",        u.getUsername(),
            "role",            u.getRole() != null ? u.getRole() : "",
            "enabled",         u.isEnabled(),
            "maxLotSize",      u.getMaxLotSize(),
            "planExpiryDate",  u.getPlanExpiryDate() != null ? u.getPlanExpiryDate().toString() : "",
            "planActive",      u.isPlanActive(),
            "notes",           u.getNotes() != null ? u.getNotes() : "",
            "kiteApiKey",      u.getKiteApiKey() != null ? u.getKiteApiKey() : "",
            "hasKiteToken",    u.getKiteAccessToken() != null && !u.getKiteAccessToken().isBlank()
        );
    }
}
