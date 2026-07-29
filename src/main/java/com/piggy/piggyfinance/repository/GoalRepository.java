package com.piggy.piggyfinance.repository;

import com.piggy.piggyfinance.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<Goal> findByIdAndUserId(UUID id, UUID userId);
    long countByUserId(UUID userId);
}
