package com.stockmaster.inventory.controller;

import com.stockmaster.inventory.entity.Product;
import com.stockmaster.inventory.repository.ProductRepository;
import com.stockmaster.inventory.repository.SettingsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/inventory")
@CrossOrigin(origins = "*")
public class InventoryController {
    
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SettingsRepository settingsRepository;
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final List<String> recentAuditLogs = Collections.synchronizedList(new ArrayList<>());

    @GetMapping("/low-stock")
    public ResponseEntity<List<Product>> getLowStockProducts(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId) {
        List<Product> lowStock = productRepository.findLowStockProducts(resolveAccountId(accountId));
        return ResponseEntity.ok(lowStock);
    }
    
    @GetMapping("/products")
    public ResponseEntity<List<Product>> getAllProducts(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId) {
        return ResponseEntity.ok(productRepository.findAllByAccountId(resolveAccountId(accountId)));
    }
    
    @GetMapping("/products/{id}")
    public ResponseEntity<Product> getProductById(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId, @PathVariable("id") Integer id) {
        return productRepository.findByIdAndAccountId(id, resolveAccountId(accountId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PostMapping("/products")
    public ResponseEntity<Product> createProduct(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId, @RequestBody Product product) {
        product.setAccountId(resolveAccountId(accountId));
        return ResponseEntity.ok(productRepository.save(product));
    }
    
    @PutMapping("/products/{id}")
    public ResponseEntity<Product> updateProduct(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId, @PathVariable("id") Integer id, @RequestBody Product product) {
        Integer resolvedAccountId = resolveAccountId(accountId);
        return productRepository.findByIdAndAccountId(id, resolvedAccountId)
                .map(existing -> {
                    product.setId(id);
                    product.setAccountId(resolvedAccountId);
                    return ResponseEntity.ok(productRepository.save(product));
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteProduct(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId, @PathVariable("id") Integer id) {
        return productRepository.findByIdAndAccountId(id, resolveAccountId(accountId))
                .map(product -> {
                    productRepository.delete(product);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
    
    @PostMapping("/restock")
    public ResponseEntity<Product> restockProduct(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId, @RequestBody Map<String, Object> request) {
        Integer productId = (Integer) request.get("productId");
        Integer quantity = (Integer) request.get("quantity");
        String commandText = Optional.ofNullable(request.get("commandText")).map(Object::toString).orElse("Restock command received");
        
        return productRepository.findByIdAndAccountId(productId, resolveAccountId(accountId))
                .map(product -> {
                    product.setQuantity(product.getQuantity() + quantity);
                    Product updated = productRepository.save(product);
                    
                    logAudit(productId, "Restock", quantity);
                    appendReasoningFeed("USER COMMAND: " + commandText);
                    
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/add")
    public ResponseEntity<Product> addProduct(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId, @RequestBody Product product) {
        product.setAccountId(resolveAccountId(accountId));
        Product saved = productRepository.save(product);
        appendReasoningFeed("USER COMMAND: Add new item " + product.getName());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/simulate-shortage")
    public ResponseEntity<Product> simulateShortage(@RequestHeader(value = "X-Account-Id", required = false) Integer accountId) {
        List<Product> products = productRepository.findAllByAccountId(resolveAccountId(accountId));
        if (products.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Random random = new Random();
        Product chosen = products.get(random.nextInt(products.size()));
        int newQuantity = Math.max(1, chosen.getThreshold() - 2);
        chosen.setQuantity(newQuantity);
        Product updated = productRepository.save(chosen);

        appendReasoningFeed("USER COMMAND: Simulate shortage for " + chosen.getName());
        appendReasoningFeed("SYSTEM: Forced shortage detected for " + chosen.getName() + " (qty=" + newQuantity + ")");
        logAudit(chosen.getId(), "Missing Shipment - System Simulation", chosen.getThreshold() - newQuantity);

        return ResponseEntity.ok(updated);
    }

    @PostMapping("/run-bi")
    public ResponseEntity<Map<String, Object>> runBusinessIntelligence() {
        try {
            Path scriptPath = Paths.get(System.getProperty("user.dir")).resolve("../openclaw-agent/skills/analyze_performance.py").normalize();
            ProcessBuilder builder = new ProcessBuilder("python", scriptPath.toString());
            builder.directory(Paths.get(System.getProperty("user.dir")).toFile());
            Process process = builder.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return ResponseEntity.status(500).body(Map.of("error", "BI analysis failed"));
            }

            Map<String, Object> result = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {});
            appendReasoningFeed("USER COMMAND: Run BI Analysis");
            appendReasoningFeed("SYSTEM: BI Analysis executed on demand");
            return ResponseEntity.ok(result);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        appendReasoningFeed("USER COMMAND: System Health Check");
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "timestamp", LocalDateTime.now().format(TIMESTAMP_FORMAT)
        ));
    }

    @GetMapping(value = "/reasoning", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getReasoningFeed() {
        try {
            Path feedFile = Paths.get(System.getProperty("user.dir")).resolve("../openclaw-agent/reasoning_feed.txt").normalize();
            if (!Files.exists(feedFile)) {
                return ResponseEntity.ok("");
            }
            String content = Files.readString(feedFile, StandardCharsets.UTF_8);
            return ResponseEntity.ok(content);
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Unable to read reasoning feed");
        }
    }

    @GetMapping("/agent/context")
    public ResponseEntity<Map<String, Object>> getAgentContext() {
        Integer accountId = resolveAccountId(null);
        List<Product> lowStock = productRepository.findLowStockProducts(accountId);
        return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "lowStock", lowStock,
                "auditLogs", new ArrayList<>(recentAuditLogs)
        ));
    }

    private Integer resolveAccountId(Integer accountId) {
        if (accountId != null) {
            return accountId;
        }
        return settingsRepository.findTopByOrderByCreatedAtDesc()
                .map(settings -> settings.getId())
                .orElse(1);
    }
    
    private void logAudit(Integer productId, String action, Integer quantity) {
        String logEntry = "AUDIT: Product " + productId + " - " + action + " - Quantity/Diff: " + quantity + " @ " + LocalDateTime.now().format(TIMESTAMP_FORMAT);
        System.out.println(logEntry);
        recentAuditLogs.add(logEntry);
        if (recentAuditLogs.size() > 100) {
            recentAuditLogs.remove(0);
        }
    }
    
    private void appendReasoningFeed(String entry) {
        try {
            Path feedFile = Paths.get(System.getProperty("user.dir")).resolve("../openclaw-agent/reasoning_feed.txt").normalize();
            Files.createDirectories(feedFile.getParent());
            String timestamped = "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] " + entry + System.lineSeparator();
            Files.writeString(feedFile, timestamped, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Unable to write reasoning feed: " + e.getMessage());
        }
    }
}
