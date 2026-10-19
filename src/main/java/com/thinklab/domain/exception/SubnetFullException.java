package com.thinklab.domain.exception;

/** Domain Exception: no address of the subnet is free to hand out. RFC 7807 mapping: HTTP 409 Conflict. */
public class SubnetFullException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00409";

    public SubnetFullException(String message) {
        super(ERROR_CODE, message);
    }
}
