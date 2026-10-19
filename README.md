# micronaut-it-ipam-service

BIAN-aligned Service Domain **it-ipam** (Control Record: `Subnet`), port `8110`.

The third slice of Journey 15 (breadth): **IP address management**. A `Subnet` is an IPv4 range the organisation hands addresses out of. An
`Allocation` is one address handed out of it, to an asset or a host name. The registry guarantees that no two active subnets overlap and that an
address is never held twice, and says, **when you ask**, how full each subnet is.

## What it guarantees, and what it does not

- **The registry hands addresses out** (ADR-030): ask for an address, or leave it out and the lowest free one is taken. The network and broadcast
  addresses are never handed out. IPv4 only, dotted quads only (no leading zeros). A hostname must be a DNS name; nothing about a person is kept.
- **No overlap, no double allocation** (ADR-033): an address is held by one ALLOCATED allocation at most (a partial unique index: twenty requests
  at once give one winner, 409 for the others); no two ACTIVE subnets of a tenant overlap (settled after the write: of twenty overlapping
  creations at once exactly one survives). A released address can be handed out again.
- **A subnet is judged when read** (ADR-031): `level` is `OK`, `NEAR_FULL` (from `warnPercent` of the usable addresses on), `FULL` (nothing
  free) or `RETIRED`, with `used`, `usable`, `free` and `usedPercent`.
- **The range never changes**: a different range is a different subnet. A retired subnet takes no new address; reactivating it is refused (409)
  if an active subnet now overlaps it.
- **Staff only** (ADR-032): every route refuses a `REQUESTER` with 403 `ERR-IPM-00403`; another tenant's subnet or allocation answers 404. There is
  no delete (a subnet is retired, an allocation released).
- **Not here yet:** IPv6, DHCP pools and reserved ranges, DNS records, reconciliation with what is really on the network (a pairing with discovery).

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call; `X-Executor` is mandatory on the actions; `X-Role` is optional and, with platform security on, comes from
the verified token.

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-ipam/v1/initiate` `{"name":"Office LAN","cidr":"10.20.0.0/24","vlan":120,"siteId":"<uuid, optional>","warnPercent":80}` (prefix /8 to /30) |
| retrieve | `GET /it-ipam/v1/{id}/retrieve` (judged now) |
| retrieve (collection) | `GET /it-ipam/v1/retrieve?status=&siteId=&level=` (oldest first) |
| update | `PUT /it-ipam/v1/{id}/update` `{"name":"...","vlan":null,"siteId":null,"warnPercent":90}` (everything but the range, in any status) |
| control/retire | `PUT /it-ipam/v1/{id}/control/retire` (ACTIVE -> RETIRED: no new address) |
| control/reactivate | `PUT /it-ipam/v1/{id}/control/reactivate` (RETIRED -> ACTIVE, 409 if an active subnet overlaps it now) |
| audit-log/retrieve | `GET /it-ipam/v1/{id}/audit-log/retrieve` |
| allocation/initiate | `POST /it-ipam/v1/allocation/initiate` `{"subnetId":"<uuid>","ip":"10.20.0.50 (optional: the lowest free one)","assetId":"<uuid, optional>","hostname":"printer-01 (optional)"}` |
| allocation/retrieve | `GET /it-ipam/v1/allocation/{id}/retrieve` |
| allocation/retrieve (collection) | `GET /it-ipam/v1/allocation/retrieve?subnetId=&status=&assetId=&limit=` (newest first, 100 unless asked, at most 1000) |
| allocation/control/release | `PUT /it-ipam/v1/allocation/{id}/control/release` (ALLOCATED -> RELEASED: the address is free again) |
| allocation/audit-log/retrieve | `GET /it-ipam/v1/allocation/{id}/audit-log/retrieve` |

```text
subnet:     ACTIVE <-> RETIRED ; judged when read: OK / NEAR_FULL / FULL
allocation: ALLOCATED -> RELEASED (one way)
```

```bash
curl -X POST "http://localhost:8110/it-ipam/v1/allocation/initiate" -H "X-Tenant-Id: <organisationId>" -H "X-Executor: <userId>" \
  -H "Content-Type: application/json" -d '{"subnetId":"<subnetId>","hostname":"printer-01"}'
```

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-IPM-00403` | 403 | A REQUESTER used IP address management (ADR-032) |
| `ERR-IPM-00404` | 404 | Subnet or allocation not found (another tenant's answers the same) |
| `ERR-IPM-00409` | 409 | A name already used, a range that overlaps an active subnet, an address already held, a full subnet, an allocation in a retired subnet, an illegal transition, or it changed while the write was applied (retry) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure, a CIDR that is not a network address or outside /8../30, an address that is not a dotted quad, outside the subnet or its network or broadcast address, a hostname that is not a DNS name |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Configuration

`HASH_SERVICE_URL` (default `http://localhost:8080`, the sovereign ids), `MICRONAUT_SERVER_PORT` (default 8110), `mongodb.uri`,
`thinklab.mongo.create-indexes` (default on). The service calls no other service and has no scheduler.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 subnets and allocations, IPv4 only, handed out by the registry · 031 a subnet is judged when read · 032 staff only · 033 no overlapping subnets, one holder per address.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
