package com.thinklab.domain.exception;

/** Domain Exception: an illegal Allocation lifecycle transition, or the allocation changed while the write was applied. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidAllocationStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00409";

    public InvalidAllocationStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
