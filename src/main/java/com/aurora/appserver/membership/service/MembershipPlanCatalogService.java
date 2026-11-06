package com.aurora.appserver.membership.service;

import com.aurora.appserver.app.context.AppContext;
import com.aurora.appserver.app.service.AppRegistryService;
import com.aurora.appserver.membership.domain.MembershipPlanEntity;
import com.aurora.appserver.membership.domain.PlanStatus;
import com.aurora.appserver.membership.repository.MembershipPlanRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipPlanCatalogService {

    private final AppRegistryService appRegistryService;
    private final MembershipPlanRepository planRepository;

    public MembershipPlanCatalogService(
            AppRegistryService appRegistryService,
            MembershipPlanRepository planRepository
    ) {
        this.appRegistryService = appRegistryService;
        this.planRepository = planRepository;
    }

    /** Active plans of the requested app only, in PlanCode order (MONTHLY, YEARLY, LIFETIME). */
    @Transactional(readOnly = true)
    public List<MembershipPlanEntity> activePlans(String appCodeHeader) {
        AppContext app = appRegistryService.requireActiveApp(appCodeHeader);
        return planRepository.findByAppIdAndStatus(app.appId(), PlanStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(MembershipPlanEntity::getPlanCode)
                        .thenComparing(MembershipPlanEntity::getId))
                .toList();
    }
}
