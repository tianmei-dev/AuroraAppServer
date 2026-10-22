package com.aurora.appserver.membership.repository;

import com.aurora.appserver.membership.domain.GrantSourceType;
import com.aurora.appserver.membership.domain.MembershipGrantEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipGrantRepository extends JpaRepository<MembershipGrantEntity, Long> {

    Optional<MembershipGrantEntity> findByAppIdAndSourceTypeAndSourceRef(
            Long appId,
            GrantSourceType sourceType,
            String sourceRef
    );

    List<MembershipGrantEntity> findByAppIdAndUserIdOrderByGrantedAtAscIdAsc(Long appId, Long userId);

    boolean existsByAppIdAndUserId(Long appId, Long userId);
}
