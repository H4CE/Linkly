package io.linkly.url_shortener.repository;

import io.linkly.url_shortener.entity.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    List<UrlMapping> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

}
