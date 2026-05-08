package com.stockmaster.inventory.repository;

import com.stockmaster.inventory.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Integer> {
    @Query("SELECT p FROM Product p WHERE p.accountId = :accountId OR (p.accountId IS NULL AND :accountId = 1)")
    List<Product> findAllByAccountId(@Param("accountId") Integer accountId);

    @Query("SELECT p FROM Product p WHERE (p.accountId = :accountId OR (p.accountId IS NULL AND :accountId = 1)) AND p.quantity <= p.threshold")
    List<Product> findLowStockProducts(@Param("accountId") Integer accountId);

    @Query("SELECT p FROM Product p WHERE p.id = :id AND (p.accountId = :accountId OR (p.accountId IS NULL AND :accountId = 1))")
    java.util.Optional<Product> findByIdAndAccountId(@Param("id") Integer id, @Param("accountId") Integer accountId);
}
