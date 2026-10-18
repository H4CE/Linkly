package io.linkly.url_shortener.repository;

import io.linkly.url_shortener.entity.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    List<UrlMapping> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    // Increment inside the database to prevent lost updates from concurrent requests.
    // Checking expiry here also catches changes made after a destination was cached.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update UrlMapping u set u.clickCount = u.clickCount + 1
            where u.id = :id and u.shortCode = :shortCode
              and (u.expiresAt is null or u.expiresAt > :now)
            """)
    int incrementClickCount(Long id, String shortCode, LocalDateTime now);

}
