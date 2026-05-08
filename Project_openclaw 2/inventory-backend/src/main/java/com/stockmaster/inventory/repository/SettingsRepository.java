package com.stockmaster.inventory.repository;

import com.stockmaster.inventory.entity.Settings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettingsRepository extends JpaRepository<Settings, Integer> {
    Optional<Settings> findTopByOrderByIdAsc();
    Optional<Settings> findTopByOrderByCreatedAtDesc();
    Optional<Settings> findByUsername(String username);
    boolean existsByUsername(String username);
}
