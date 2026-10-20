# ADR-033: No Overlapping Subnets, One Holder per Address

## Status
Accepted

## Context
Two people creating overlapping subnets, or asking for the same address, at the same moment must never both succeed. "Look first, then write" is a race; a unique index cannot express "ranges do not overlap".

## Decision
- **One holder per address** is a **partial unique index** on `(organisationId, subnetId, address)` over the ALLOCATED allocations: the second writer gets a 409 (`ERR-IPM-00409`), a released allocation no longer counts, so its address can be handed out again. "Next free" takes the lowest free usable address and, if somebody takes it first, a free one picked at random among the 64 lowest (so many simultaneous requests do not all race for the same address), up to five times; a subnet with nothing free is a 409.
- **No two ACTIVE subnets of a tenant overlap**, settled **after the write, by an order the store gives**: the new subnet is inserted, then takes a **ticket** from an atomic counter of its organisation (one `$inc`, the same order for every instance) and keeps it. The ACTIVE subnets that share an address with it are then read; if any of them has a **lower ticket** the new subnet removes itself and the answer is a 409 naming the other range. The order is total, so of any number of simultaneous creations of ranges that all overlap each other exactly one survives (proved with twenty at once, thirty rounds in a row, and with a subnet created earlier in the caller's memory that reaches the store later). The removal is internal: there is still no delete route.
  - **Why not the creation time** (this ADR's first version): the time was taken in the caller's memory *before* the write, so a subnet created earlier that reached the store later was not seen by one created later that had already looked, and both stayed (found by the platform's end-to-end run: two survivors out of twenty). A ticket is taken *after* the insert: if one subnet's ticket is lower than another's, its insert was committed before the other took its ticket, so the other's read sees it.
  - **A subnet that has no ticket yet and was created in the last five seconds is being created right now**: the read is repeated (every 20 ms, up to five seconds) until it has one. It needs nothing from the waiting subnet to get one, so nobody waits for each other. A subnet with no ticket that is not being created was made before tickets existed (or was abandoned) and comes before every ticket.
  - The counter is created by the first subnet of an organisation; if two create it at once, the loser asks again.
- A **reactivation** is checked the same way after its write; if an ACTIVE subnet overlaps it, it goes back to RETIRED with a `REACTIVATION_UNDONE` entry on its trail and the answer is a 409. Two simultaneous overlapping reactivations may both be undone (safe, never an overlap).
- Every other change is one atomic update guarded by the status loaded, which also appends the audit entry. A subnet name is unique per organisation (unique index).

## Consequences
- Positive: no overlap and no double allocation under any concurrency, without a lock or a transaction.
- Negative: for a moment two overlapping subnets exist (until the later one removes itself), visible only to a reader in that instant; a caller that loses a race reads again and retries. A creation can wait up to a few milliseconds for a subnet being created at the same moment, and a crash between the insert and the ticket leaves a subnet with no ticket, which becomes older than every ticket after five seconds.
