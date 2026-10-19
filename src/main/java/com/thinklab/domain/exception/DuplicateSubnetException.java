package com.thinklab.domain.exception;

/** Domain Exception: a subnet name already used in the organisation, or a range that overlaps an active subnet. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateSubnetException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00409";

    public DuplicateSubnetException(String message) {
        super(ERROR_CODE, message);
    }
}
