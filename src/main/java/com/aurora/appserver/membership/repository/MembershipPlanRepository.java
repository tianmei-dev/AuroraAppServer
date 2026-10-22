package com.aurora.appserver.membership.repository;

import com.aurora.appserver.membership.domain.MembershipPlanEntity;
import com.aurora.appserver.membership.domain.PlanCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipPlanRepository extends JpaRepository<MembershipPlanEntity, Long> {

    Optional<MembershipPlanEntity> findByAppIdAndPlanCode(Long appId, PlanCode planCode);
}
