package com.thinklab.application.usecase;

import com.thinklab.domain.exception.IpamAccessDeniedException;

/** IP address management is for IT staff and their tools (ADR-032): a REQUESTER is refused everywhere. */
final class IpamAccess {

    static final String REQUESTER_ROLE = "REQUESTER";

    private IpamAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new IpamAccessDeniedException("A requester cannot " + operation + ": subnets and allocations are handled by IT staff.");
        }
    }
}
