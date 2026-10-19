package com.thinklab.domain.exception;

/** Domain Exception: a REQUESTER tried to use IP address management (staff only, ADR-032). RFC 7807 mapping: HTTP 403 Forbidden. */
public class IpamAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-IPM-00403";

    public IpamAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
