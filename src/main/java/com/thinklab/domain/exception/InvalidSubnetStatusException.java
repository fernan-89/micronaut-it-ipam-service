package com.thinklab.domain.exception;

/** Domain Exception: an illegal Subnet lifecycle transition, the subnet changed while the write was applied, or an allocation in a retired subnet. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidSubnetStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00409";

    public InvalidSubnetStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
