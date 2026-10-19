# ADR-031: A Subnet Is Judged When Read

## Status
Accepted. Same principle as the registries of Journey 14 and 15.

## Decision
- Nothing of the judgment is stored. Every read of a subnet counts its ALLOCATED addresses and judges it: `FULL` when no usable address is free, `NEAR_FULL` from `warnPercent` of the usable addresses on, `OK` below; a `RETIRED` subnet is not judged. The answer carries `used`, `usable` (the range minus its network and broadcast addresses), `free` and `usedPercent`.
- The list filters by `status`, `siteId` and `level`; the level comes from the counts, so it is filtered in memory over the tenant's subnets (four counted at a time), oldest first.

## Consequences
- Positive: it cannot go stale and needs no job.
- Negative: a read costs one count per subnet.
