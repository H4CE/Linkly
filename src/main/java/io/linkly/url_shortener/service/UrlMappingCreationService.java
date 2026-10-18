package io.linkly.url_shortener.service;

import io.linkly.url_shortener.entity.UrlMapping;
import io.linkly.url_shortener.entity.User;
import io.linkly.url_shortener.repository.UrlMappingRepository;
import io.linkly.url_shortener.util.Base62Encoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class UrlMappingCreationService {

    private static final int MAX_CREATION_ATTEMPTS = 3;
    private static final int MAX_SHORT_CODE_LENGTH = 100;

    private final UrlMappingRepository urlMappingRepository;
    private final Base62Encoder base62Encoder;
    private final TransactionTemplate creationTransaction;

    public UrlMappingCreationService(UrlMappingRepository urlMappingRepository,
                                     Base62Encoder base62Encoder,
                                     PlatformTransactionManager transactionManager) {
        this.urlMappingRepository = urlMappingRepository;
        this.base62Encoder = base62Encoder;
        this.creationTransaction = new TransactionTemplate(transactionManager);

        // A uniqueness failure invalidates its transaction. Each attempt must roll
        // back completely before another attempt can safely allocate a new code.
        this.creationTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UrlMapping createMapping(String originalUrl, String requestedSlug,
                                    User owner, String tags, String category) {
        for (int attempt = 0; attempt < MAX_CREATION_ATTEMPTS; attempt++) {
            UrlMapping mapping = UrlMapping.builder()
                    .originalUrl(originalUrl)
                    .user(owner)
                    .tags(tags)
                    .category(category)
                    // Reserve one row to obtain its database-generated ID. UUIDs
                    // avoid the old millisecond-based placeholder collisions;
                    // '_' keeps this temporary name outside the allowed slug alphabet.
                    .shortCode("pending_" + UUID.randomUUID())
                    .build();

            try {
                return creationTransaction.execute(status -> {
                    urlMappingRepository.saveAndFlush(mapping);

                    String finalSlug = requestedSlug;
                    if (urlMappingRepository.existsByShortCode(requestedSlug)) {
                        String suffix = "-" + base62Encoder.encode(mapping.getId());
                        // Make room for the suffix when the requested slug already
                        // uses all 100 characters allowed by the database column.
                        int prefixLength = Math.min(requestedSlug.length(),
                                MAX_SHORT_CODE_LENGTH - suffix.length());
                        finalSlug = requestedSlug.substring(0, prefixLength) + suffix;
                    }

                    // Rename the SAME managed row. Dirty checking writes an UPDATE,
                    // not a second INSERT with the same short code. Flush before
                    // returning so any uniqueness conflict is handled by the retry.
                    mapping.setShortCode(finalSlug);
                    urlMappingRepository.flush();
                    return mapping;
                });
            } catch (DataIntegrityViolationException ex) {
                // A precheck cannot stop another request from taking the code first.
                // The failed transaction is now rolled back; check the committed
                // code and retry with a fresh row/ID only for a real code collision.
                // Other integrity errors (for example invalid metadata) must surface.
                if (attempt == MAX_CREATION_ATTEMPTS - 1
                        || !urlMappingRepository.existsByShortCode(mapping.getShortCode())) {
                    throw ex;
                }
            }
        }

        throw new IllegalStateException("Could not create a unique short URL");
    }
}
