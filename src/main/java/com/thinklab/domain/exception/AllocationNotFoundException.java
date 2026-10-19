package com.thinklab.domain.exception;

/** Domain Exception: no allocation with that id in the tenant (another tenant's answers the same). RFC 7807 mapping: HTTP 404 Not Found. */
public class AllocationNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00404";

    public AllocationNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
