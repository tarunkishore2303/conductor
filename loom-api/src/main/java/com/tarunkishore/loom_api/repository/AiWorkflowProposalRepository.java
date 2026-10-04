package com.tarunkishore.loom_api.repository;

import com.tarunkishore.loom_api.ai.AiWorkflowProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AiWorkflowProposalRepository extends JpaRepository<AiWorkflowProposal, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AiWorkflowProposal p where p.id = :id")
    Optional<AiWorkflowProposal> findForApproval(@Param("id") UUID id);
}
