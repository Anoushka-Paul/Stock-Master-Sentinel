package com.stockmaster.inventory.controller;

import com.stockmaster.inventory.entity.Settings;
import com.stockmaster.inventory.repository.SettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SettingsController {

    @Autowired
    private SettingsRepository settingsRepository;

    @PostMapping("/auth/signup")
    public ResponseEntity<?> signup(@RequestBody Map<String, String> request) {
        String username = clean(request.get("username"));
        String password = Optional.ofNullable(request.get("password")).orElse("");

        if (username.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password are required"));
        }
        if (settingsRepository.existsByUsername(username)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username already exists"));
        }

        Settings settings = settingsRepository.findTopByOrderByIdAsc()
                .filter(existing -> existing.getUsername() == null || existing.getUsername().isBlank())
                .orElseGet(Settings::new);
        applyProfile(settings, request);
        settings.setUsername(username);
        settings.setPasswordHash(hashPassword(password));
        settings.setCreatedAt(LocalDateTime.now());

        return ResponseEntity.ok(settingsRepository.save(settings));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String username = clean(request.get("username"));
        String password = Optional.ofNullable(request.get("password")).orElse("");

        return settingsRepository.findByUsername(username)
                .filter(settings -> hashPassword(password).equals(settings.getPasswordHash()))
                .<ResponseEntity<?>>map(settings -> {
                    settings.setCreatedAt(LocalDateTime.now());
                    return ResponseEntity.ok(settingsRepository.save(settings));
                })
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "Invalid username or password")));
    }

    @GetMapping("/settings")
    public ResponseEntity<Settings> getSettings(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId) {
        Optional<Settings> settings = accountId == null
                ? settingsRepository.findTopByOrderByCreatedAtDesc()
                : settingsRepository.findById(accountId);
        return ResponseEntity.ok(settings.orElseGet(Settings::new));
    }

    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(
            @RequestHeader(value = "X-Account-Id", required = false) Integer accountId,
            @RequestBody Map<String, String> request) {
        Optional<Settings> existing = accountId == null
                ? settingsRepository.findTopByOrderByCreatedAtDesc()
                : settingsRepository.findById(accountId);

        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Account not found"));
        }

        Settings settings = existing.get();
        applyProfile(settings, request);
        settings.setCreatedAt(LocalDateTime.now());
        return ResponseEntity.ok(settingsRepository.save(settings));
    }

    private void applyProfile(Settings settings, Map<String, String> request) {
        settings.setStoreName(cleanOrDefault(request.get("storeName"), "Store"));
        settings.setOwnerName(cleanOrDefault(request.get("ownerName"), "Owner"));
        settings.setChannel(cleanOrDefault(request.get("channel"), "Discord"));
        settings.setChannelId(cleanOrDefault(request.get("channelId"), ""));
    }

    private String clean(String value) {
        return Optional.ofNullable(value).orElse("").trim();
    }

    private String cleanOrDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 hashing is unavailable", e);
        }
    }
}
