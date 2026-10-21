package com.aurora.appserver.app.repository;

import com.aurora.appserver.app.domain.AppEntity;
import com.aurora.appserver.app.domain.Platform;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppRepository extends JpaRepository<AppEntity, Long> {

    Optional<AppEntity> findByAppCode(String appCode);

    boolean existsByAppCode(String appCode);

    boolean existsByPlatformAndPackageName(Platform platform, String packageName);
}
