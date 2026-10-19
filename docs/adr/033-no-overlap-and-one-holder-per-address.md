# ADR-033: No Overlapping Subnets, One Holder per Address

## Status
Accepted

## Context
Two people creating overlapping subnets, or asking for the same address, at the same moment must never both succeed. "Look first, then write" is a race; a unique index cannot express "ranges do not overlap".

## Decision
- **One holder per address** is a **partial unique index** on `(organisationId, subnetId, address)` over the ALLOCATED allocations: the second writer gets a 409 (`ERR-IPM-00409`), a released allocation no longer counts, so its address can be handed out again. "Next free" takes the lowest free usable address and, if somebody takes it first, a free one picked at random among the 64 lowest (so many simultaneous requests do not all race for the same address), up to five times; a subnet with nothing free is a 409.
- **No two ACTIVE subnets of a tenant overlap**, settled **after the write**: the new subnet is inserted, then the ACTIVE subnets that share an address with it are read. If any of them is earlier (by creation time, then id), the new subnet removes itself and the answer is a 409 naming the other range. The order is total, so of any number of simultaneous creations of ranges that all overlap each other exactly one survives (proved with twenty at once); in general, no two survivors overlap. Times are compared to the millisecond, the precision of the store: comparing a subnet's own finer in-memory time with the times read back let two subnets created in the same millisecond each find the other earlier and both leave (found by the live smoke). The removal is internal: there is still no delete route.
- A **reactivation** is checked the same way after its write; if an ACTIVE subnet overlaps it, it goes back to RETIRED with a `REACTIVATION_UNDONE` entry on its trail and the answer is a 409. Two simultaneous overlapping reactivations may both be undone (safe, never an overlap).
- Every other change is one atomic update guarded by the status loaded, which also appends the audit entry. A subnet name is unique per organisation (unique index).

## Consequences
- Positive: no overlap and no double allocation under any concurrency, without a lock or a transaction.
- Negative: for a moment two overlapping subnets exist (until the later one removes itself), visible only to a reader in that instant; a caller that loses a race reads again and retries.
