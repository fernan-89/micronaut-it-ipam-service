# ADR-032: IP Address Management Is for IT Staff and Their Tools

## Status
Accepted

## Decision
- Every route refuses a `REQUESTER` with 403 `ERR-IPM-00403`; any other role, or none, is staff (tools that allocate are service identities).
- Every lookup is scoped by `X-Tenant-Id`: another tenant's subnet or allocation is a 404, never a 403.
- **There is no delete.** A subnet is retired, an allocation is released; their history stays.
- Audit is kept on each aggregate (initiated, updated, retired, reactivated, allocated, released) with the executor id. Nothing free-form is kept: names are the operator's, hostnames are DNS names, the rest are numbers and fixed lists.

## Consequences
- Positive: one access rule across the operations services.
- Negative: a requester cannot see which address their device has; that is an IT answer.
