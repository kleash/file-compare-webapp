package com.github.kleash.repository;

import com.github.kleash.model.ComparisonLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ComparisonLogRepository extends JpaRepository<ComparisonLog, Long> {
    List<ComparisonLog> findAllByOrderByComparisonTimestampDesc();

    // Example custom queries for metrics
    Long countByComparisonTimestampAfter(LocalDateTime timestamp);

    @Query("SELECT SUM(c.source1FileCount + c.source2FileCount) FROM ComparisonLog c")
    Long getTotalFilesCompared();

    @Query("SELECT SUM(c.fullyMatchedPairs) FROM ComparisonLog c")
    Long getTotalFullyMatchedPairsOverall();

    @Query("SELECT SUM(c.mismatchedPairs) FROM ComparisonLog c")
    Long getTotalMismatchedPairsOverall();

    List<ComparisonLog> findBySessionId(String sessionId);
}