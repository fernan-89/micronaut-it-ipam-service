package com.thinklab.domain.exception;

/** Domain Exception: no subnet with that id in the tenant (another tenant's answers the same). RFC 7807 mapping: HTTP 404 Not Found. */
public class SubnetNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00404";

    public SubnetNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
