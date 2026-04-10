package com.algotrading.backend.service;

import com.algotrading.backend.model.PlatformUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Loads and persists all platform users from/to a JSON file.
 *
 * File path priority:
 *   1. USERS_FILE env var
 *   2. app.users-file in application.yml
 *   3. ./data/users.json (default)
 *
 * On Render: mount a persistent disk at /data and set USERS_FILE=/data/users.json
 */
@Service
@Slf4j
public class UserRegistryService {

    @Value("${app.users-file:./data/users.json}")
    private String usersFilePath;

    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper mapper;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // In-memory copy — load once, write-through on mutations
    private List<PlatformUser> users = new ArrayList<>();

    public UserRegistryService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.findAndRegisterModules();
    }

    @PostConstruct
    public void init() {
        File file = new File(usersFilePath);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            createDefaultAdmin(file);
        } else {
            load(file);
        }
        log.info("UserRegistry loaded {} user(s) from {}", users.size(), usersFilePath);
    }

    // ── Read operations (shared lock) ─────────────────────────────────────────

    public Optional<PlatformUser> findByUsername(String username) {
        lock.readLock().lock();
        try {
            return users.stream()
                    .filter(u -> u.getUsername().equalsIgnoreCase(username))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PlatformUser> findAll() {
        lock.readLock().lock();
        try { return Collections.unmodifiableList(new ArrayList<>(users)); }
        finally { lock.readLock().unlock(); }
    }

    // ── Write operations (exclusive lock) ─────────────────────────────────────

    /** Create a new user (password is plain-text here; it gets hashed before storage). */
    public PlatformUser create(PlatformUser proto, String rawPassword) {
        lock.writeLock().lock();
        try {
            if (users.stream().anyMatch(u -> u.getUsername().equalsIgnoreCase(proto.getUsername()))) {
                throw new IllegalArgumentException("Username already exists: " + proto.getUsername());
            }
            PlatformUser user = PlatformUser.builder()
                    .id(UUID.randomUUID().toString())
                    .username(proto.getUsername())
                    .passwordHash(passwordEncoder.encode(rawPassword))
                    .role(proto.getRole() != null ? proto.getRole() : "TRADER")
                    .enabled(true)
                    .maxLotSize(proto.getMaxLotSize() > 0 ? proto.getMaxLotSize() : 1)
                    .planExpiryDate(proto.getPlanExpiryDate())
                    .notes(proto.getNotes())
                    .kiteApiKey(proto.getKiteApiKey())
                    .kiteApiSecret(proto.getKiteApiSecret())
                    .kiteAccessToken(proto.getKiteAccessToken())
                    .kiteRedirectUrl(proto.getKiteRedirectUrl())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            users.add(user);
            persist();
            log.info("Created user: {} (role={}, expiry={})", user.getUsername(), user.getRole(), user.getPlanExpiryDate());
            return user;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Update mutable fields (non-null fields in patch are applied). */
    public PlatformUser update(String username, PlatformUser patch, String newRawPassword) {
        lock.writeLock().lock();
        try {
            PlatformUser user = users.stream()
                    .filter(u -> u.getUsername().equalsIgnoreCase(username))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("User not found: " + username));

            if (newRawPassword != null && !newRawPassword.isBlank()) {
                user.setPasswordHash(passwordEncoder.encode(newRawPassword));
            }
            if (patch.getRole()            != null) user.setRole(patch.getRole());
            if (patch.getPlanExpiryDate()  != null) user.setPlanExpiryDate(patch.getPlanExpiryDate());
            if (patch.getNotes()           != null) user.setNotes(patch.getNotes());
            if (patch.getKiteApiKey()      != null) user.setKiteApiKey(patch.getKiteApiKey());
            if (patch.getKiteApiSecret()   != null) user.setKiteApiSecret(patch.getKiteApiSecret());
            if (patch.getKiteAccessToken() != null) user.setKiteAccessToken(patch.getKiteAccessToken());
            if (patch.getKiteRedirectUrl() != null) user.setKiteRedirectUrl(patch.getKiteRedirectUrl());
            if (patch.getMaxLotSize()      > 0)     user.setMaxLotSize(patch.getMaxLotSize());
            user.setEnabled(patch.isEnabled());
            user.setUpdatedAt(LocalDateTime.now());

            persist();
            log.info("Updated user: {}", username);
            return user;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Update ONLY the Kite access token for a user (called from OAuth callback). */
    public void updateAccessToken(String username, String accessToken) {
        lock.writeLock().lock();
        try {
            users.stream()
                    .filter(u -> u.getUsername().equalsIgnoreCase(username))
                    .findFirst()
                    .ifPresent(u -> {
                        u.setKiteAccessToken(accessToken);
                        u.setUpdatedAt(LocalDateTime.now());
                    });
            persist();
            log.info("Access token updated for user: {}", username);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(String username) {
        lock.writeLock().lock();
        try {
            boolean removed = users.removeIf(u -> u.getUsername().equalsIgnoreCase(username));
            if (!removed) throw new NoSuchElementException("User not found: " + username);
            persist();
            log.info("Deleted user: {}", username);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void load(File file) {
        try {
            users = mapper.readValue(file, new TypeReference<List<PlatformUser>>() {});
        } catch (Exception e) {
            log.error("Failed to load users.json: {}", e.getMessage(), e);
            users = new ArrayList<>();
        }
    }

    private void persist() {
        try {
            File file = new File(usersFilePath);
            file.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, users);
        } catch (Exception e) {
            log.error("Failed to persist users.json: {}", e.getMessage(), e);
        }
    }

    private void createDefaultAdmin(File file) {
        log.info("users.json not found — creating default admin at {}", file.getAbsolutePath());
        PlatformUser admin = PlatformUser.builder()
                .id(UUID.randomUUID().toString())
                .username("admin")
                .passwordHash(passwordEncoder.encode("admin123"))
                .role("ADMIN")
                .enabled(true)
                .maxLotSize(10)
                .planExpiryDate(LocalDate.of(2099, 12, 31))
                .notes("Default admin — change password immediately!")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        users.add(admin);
        persist();
    }
}
