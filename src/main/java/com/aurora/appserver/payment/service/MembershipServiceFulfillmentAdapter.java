package com.aurora.appserver.payment.service;

import com.aurora.appserver.membership.service.MembershipService;
import org.springframework.stereotype.Component;

@Component
public class MembershipServiceFulfillmentAdapter implements MembershipFulfillmentPort {

    private final MembershipService membershipService;

    public MembershipServiceFulfillmentAdapter(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @Override
    public Long grantForPaidOrder(Long appId, String orderNo) {
        return membershipService.grantForPaidOrder(appId, orderNo).grantId();
    }
}
