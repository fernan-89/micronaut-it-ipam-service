package com.thinklab.domain.exception;

/** Domain Exception: an address already allocated in that subnet. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateAllocationException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00409";

    public DuplicateAllocationException(String message) {
        super(ERROR_CODE, message);
    }
}
