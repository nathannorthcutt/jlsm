---
title: "Service Discovery Patterns for Cluster Bootstrap"
aliases: ["service discovery", "seed nodes", "bootstrap", "node discovery"]
topic: "distributed-systems"
category: "cluster-membership"
tags: ["discovery", "bootstrap", "seed-nodes", "dns", "mtls", "spi", "rediscovery"]
complexity:
  time_build: "N/A"
  time_query: "O(1) cached, O(seeds) on miss"
  space: "O(cluster size) membership view"
research_status: "active"
confidence: "high"
last_researched: "2026-04-13"
applies_to: []
related:
  - "distributed-systems/cluster-membership/cluster-membership-protocols.md"
  - "distributed-systems/networking/multiplexed-transport-framing.md"
decision_refs: ["discovery-environment-config", "continuous-rediscovery", "authenticated-discovery", "table-ownership-discovery"]
sources:
  - url: "https://docs.hazelcast.com/hazelcast/5.6/clusters/discovery-mechanisms"
    title: "Hazelcast Discovery Mechanisms"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://cassandra.apache.org/doc/latest/cassandra/architecture/dynamo.html"
    title: "Apache Cassandra — Dynamo Architecture"
    accessed: "2026-04-13"
    type: "docs"
  - url: "https://www.elastic.co/guide/en/elasticsearch/reference/current/modules-discovery.html"
    title: "Elasticsearch Discovery and Cluster Formation"
    accessed: "2026-04-13"
    type: "docs"
---

# Service Discovery Patterns for Cluster Bootstrap

## summary

Service discovery solves the bootstrap problem: a membership protocol assumes at
least one known peer, but how does a node find that first peer? This article
covers bootstrap strategies, pluggable discovery SPI, continuous rediscovery,
and authenticated discovery.

## bootstrap-strategies

### static-seed-list

Each node is configured with a fixed list of seed addresses. On startup, contact
seeds until one responds with the current membership view.

```
bootstrap(seeds):
  for addr in seeds:
    response = tryConnect(addr)
    if response != null: return response.membershipView()
  throw BootstrapFailedException("no seed reachable")
```

**Used by:** Cassandra (`seed_provider`), CockroachDB (`--join` flag).
**Tradeoffs:** Zero dependencies. Brittle when seeds change. Needs config
management to keep lists current.

### dns-based-discovery

Resolve a well-known DNS name to obtain seed addresses. SRV records provide host
and port; A/AAAA records provide hosts only.

```
bootstrap(dnsName, recordType):
  records = dnsResolve(dnsName, recordType)  // A, AAAA, or SRV
  return contactSeeds(records.map(r -> NodeAddress(r.host, r.port)))
```

**Used by:** Elasticsearch (`discovery.seed_providers: dns`), K8s headless
Services, Consul DNS interface.
**Tradeoffs:** Decouples config from IPs. DNS TTL introduces stale window.
Requires DNS to be available before the cluster.

### cloud-provider-api-discovery

Query cloud metadata APIs to find peers by tag, label, or security group.

```
bootstrap(cloudProvider, filter):
  instances = cloudProvider.describeInstances(filter)
  return contactSeeds(instances.map(i -> NodeAddress(i.privateIp, clusterPort)))
```

| Environment | API | Filter |
|-------------|-----|--------|
| AWS EC2 | DescribeInstances | Tag key/value |
| GCP | instances.list | Label selector |
| Kubernetes | Endpoints API | Label selector on Service |

**Used by:** Hazelcast (auto-detects AWS/GCP/Azure/K8s), Elasticsearch (cloud
plugins), Consul (`-retry-join` with cloud auto-join).
**Tradeoffs:** Fully dynamic. Requires IAM permissions and metadata service access.

### multicast-broadcast

Announce on a multicast group; peers respond. **Used by:** Hazelcast (dev
default), JGroups. **Tradeoffs:** Zero config but unusable in most cloud/container
environments (multicast blocked by VPC). Security risk. Not for production.

## pluggable-discovery-spi

A discovery SPI decouples the membership protocol from the bootstrap mechanism.
jlsm's `DiscoveryProvider` interface follows this pattern:

```
interface DiscoveryProvider:
  discoverSeeds() -> Set<NodeAddress>     // required
  register(self: NodeAddress) -> void     // optional, default no-op
  deregister(self: NodeAddress) -> void   // optional, default no-op
```

The `register`/`deregister` hooks support self-announcing providers (cloud APIs,
service registries) while remaining no-ops for passive providers (static lists,
DNS). Stale registrations from crashed nodes are harmless -- the membership
protocol handles liveness detection independently.

**Implementation strategies by environment:**

| Environment | DiscoveryProvider impl | register/deregister |
|-------------|----------------------|---------------------|
| Development/test | InJvmDiscoveryProvider (static set) | Self-announcing |
| Bare metal | StaticSeedProvider (config file) | No-op |
| DNS-managed | DnsSeedProvider (SRV lookup) | No-op |
| Kubernetes | K8sEndpointsProvider (API watch) | No-op (K8s manages) |
| AWS EC2 | Ec2TagProvider (DescribeInstances) | No-op (tags pre-set) |
| Service registry | ConsulProvider / EurekaProvider | Self-announcing |

## continuous-rediscovery

In dynamic environments (containers, autoscaling groups), the seed list resolved
at bootstrap may become stale. Continuous rediscovery periodically re-resolves
seeds and feeds new addresses to the membership protocol.

```
rediscoveryLoop(provider, membership, interval):
  while running:
    sleep(interval)
    freshSeeds = provider.discoverSeeds()
    knownMembers = membership.members().map(m -> m.address())
    newSeeds = freshSeeds - knownMembers
    for seed in newSeeds:
      membership.introduceSeed(seed)
```

**Design considerations:** Re-resolution interval of 30s--120s balances
freshness against API rate limits. Stale seeds are harmless -- connection times
out, membership protocol never marks them alive. DNS re-resolution interval
should be >= DNS TTL to avoid redundant lookups against cached results.

## authenticated-discovery

For multi-tenant or untrusted networks, verify identity before admitting a node.

**mTLS:** Both sides present certificates during TLS handshake. Connection
established only if certs chain to a trusted CA. Standard in production
Cassandra, CockroachDB, Elasticsearch. Requires PKI infrastructure.

**Token-based:** Shared secret presented during join handshake via HMAC. Used by
Consul (`-encrypt` gossip key), Kubernetes (bearer tokens). Simpler than mTLS
but token compromise admits arbitrary nodes.

**Trust-on-first-use (TOFU):** Record seed's public key on first contact; reject
mismatches later. Vulnerable to MITM on first contact. Development only.

| Strategy | Identity strength | Infrastructure cost | Rotation | Best for |
|----------|------------------|--------------------|-----------|----|
| mTLS | Strong (CA-backed) | High (PKI) | Cert rotation | Production, multi-tenant |
| Shared token | Moderate (HMAC) | Low (config) | Token rotation | Small clusters, dev |
| TOFU | Weak (first-contact) | None | Manual re-pin | Development, prototyping |
| None | None | None | N/A | Single-tenant, trusted network |

## production-system-survey

| System | Bootstrap | Rediscovery | Auth | Notes |
|--------|-----------|-------------|------|-------|
| Cassandra | Static seeds in cassandra.yaml | Gossip protocol propagates | mTLS (optional) | At least 1 seed per DC; seeds are not special after bootstrap |
| Elasticsearch | Seed hosts providers (static, file, DNS, plugins) | Zen discovery re-resolves periodically | TLS + security plugin | File-based provider watches for changes; cloud plugins for EC2/GCE |
| CockroachDB | `--join` flag (static list) | Gossip propagates full address map | mTLS (required by default) | `cockroach init` bootstraps first node; others join via gossip |
| Consul | `-join` flag or `-retry-join` | `-retry-join` with cloud auto-join | Gossip encryption + mTLS | Auto-join supports AWS, GCP, Azure tag-based discovery |
| Hazelcast | Auto-detect (cloud) or TCP/multicast | Membership protocol propagates | TLS (Enterprise) | Auto-detects AWS/GCP/Azure/K8s; falls back to multicast |

## practical-guidance

1. **Start with static seeds** -- sufficient for fixed-size / bare-metal.
2. **Add DNS** when IPs are unstable. K8s headless Services give this for free.
3. **Add cloud API** for autoscaling with tag-based instance discovery.
4. **Layer mTLS** for untrusted networks (orthogonal to discovery strategy).
5. **Add continuous rediscovery** for dynamic topologies (containers, spot).

**First-node problem:** If `discoverSeeds()` returns empty and the node is
configured as bootstrap, initialize a single-member cluster. Only one node
should ever bootstrap -- concurrent bootstraps risk split-brain.

---
*Researched: 2026-04-13 | Next review: 2026-10-13*
