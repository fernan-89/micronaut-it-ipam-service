# ADR-030: Subnets and Allocations, IPv4 Only, Handed Out by the Registry

## Status
Accepted

## Context
Journey 15 needs to know which addresses are in use and who holds them. The user chose that addresses are **allocated in the registry** (by staff or by their tools), the classic IPAM, rather than learned from a network scan.

## Decision
- Two aggregates. A **`Subnet`** (the Control Record) is an IPv4 range: a name (unique per organisation), the CIDR written as its network address (`10.20.0.0/24`; host bits set are refused, never silently cleared), a prefix from /8 to /30, an optional VLAN (1..4094) and site (a site directory id, not checked), and the use at which it is nearly full. The range **never changes**: a different range is a different subnet. ACTIVE or RETIRED (retired takes no new allocation).
- An **`Allocation`** is one address handed out of an ACTIVE subnet: an address asked for, or **the lowest free one** when none is given. The network and broadcast addresses are never handed out. It may name the asset that holds it and a **hostname that is a DNS name** (labels of letters, digits and dashes): never a person. ALLOCATED or RELEASED; a release frees the address and the allocation stays as history.
- **IPv4 only, dotted quads only** (no leading zeros, which some tools read as octal). IPv6 is a later slice: its ranges do not fit in a number and "next free" means something else there.
- The registry **does not scan or configure** anything: DHCP, DNS and the network keep their own truth; the registry is the plan.

## Consequences
- Positive: a small model that answers "which address can I use" and "who holds this one" without personal data.
- Negative: no IPv6, no DHCP pools or ranges reserved in bulk, no reconciliation with what is really on the wire (a pairing with discovery would be the next step).
