# Cloud Infrastructure Proposal — Ascentra Capital

**Prepared by:** Aniket Dhoke  
**For:** Business Leadership / Decision Makers  
**Date:** June 24, 2026  
**Project:** Ascentra Copy Trading Platform — India Launch

---

## Executive Summary

Ascentra needs to migrate from Stockholm (EU) to India to reduce trade execution latency from **700ms to under 100ms**. This document compares all major cloud options with costs, latency projections, and recommendations.

**Key Decision:** Where to host for lowest latency + cost efficiency for 10,000+ Indian users.

---

## Why Migration is Critical

| Metric | Current (Stockholm) | After Migration (India) |
|--------|--------------------|-----------------------|
| Broker API latency | 400-700ms | **30-80ms** |
| Trade copy end-to-end | 800-1200ms | **100-200ms** |
| User experience | Noticeable delay | Near-instant |
| Competitive advantage | None | Industry-leading speed |

**Every 100ms matters in trading.** A 500ms improvement means our users get filled at better prices.

---

## Side-by-Side Cost Comparison (India Region, Same Specs)

### Budget Setup (2 App Servers + DB + Redis + LB)

| Component | AWS (Mumbai) | GCP (Mumbai) | Azure (Pune) | DigitalOcean (Bangalore) | Hetzner (No India) |
|-----------|-------------|-------------|-------------|------------------------|-------------------|
| App Server (2 vCPU, 4 GB) × 2 | $130 | $120 | $140 | $96 | $22 (EU only) |
| Managed PostgreSQL (2 vCPU, 4 GB, 100 GB) | $60 | $55 | $70 | $60 | $30 (EU only) |
| Managed Redis (2 GB) | $50 | $45 | $55 | $30 | N/A |
| Load Balancer | $25 | $20 | $25 | $12 | N/A |
| Static IPs (10) | $0 (if attached) | $0 | $0 | $5 | N/A |
| CDN | $10 | $0 (free tier) | $10 | Free (Cloudflare) | Free (Cloudflare) |
| SSL | Free (ACM) | Free (managed) | Free (managed) | Free (Let's Encrypt) | Free |
| NAT/Egress | $35 | $30 | $35 | $0 | $0 |
| **Monthly Total** | **~$310** | **~$270** | **~$335** | **~$203** | **~$52** (no India) |

### Full Scale (3+ App Servers + HA DB + Redis Cluster + Kafka)

| Component | AWS (Mumbai) | GCP (Mumbai) | Azure (Pune) |
|-----------|-------------|-------------|-------------|
| App Server (4 vCPU, 8 GB) × 3 | $390 | $350 | $420 |
| RDS/Cloud SQL (Multi-AZ, 16 GB) | $250 | $220 | $280 |
| Redis Cluster (3 nodes) | $400 | $350 | $420 |
| Kafka/Pub-Sub | $600 (MSK) | $200 (Pub/Sub) | $500 (Event Hubs) |
| Load Balancer + WAF | $50 | $40 | $60 |
| Monitoring | $20 | Free (Cloud Monitoring) | $30 |
| **Monthly Total** | **~$1,710** | **~$1,160** | **~$1,710** |

---

## Detailed Provider Breakdown

### 1. AWS (Amazon Web Services) — CURRENT

**Mumbai Region:** ap-south-1 ✅

| Pros | Cons |
|------|------|
| Already using it — zero migration | Most expensive of the big 3 |
| Best ecosystem (150+ services) | Complex pricing |
| Excellent India presence (Mumbai AZs) | NAT Gateway costs add up |
| ECS/Fargate for containers | Support costs extra |
| Multi-IP (Elastic IP) native | |

**Equivalent Services:**
| Need | AWS Service | Monthly Cost |
|------|------------|-------------|
| App hosting | ECS Fargate / EC2 | $65-130/instance |
| Database | RDS PostgreSQL | $60-250 |
| Cache | ElastiCache Redis | $50-400 |
| Message queue | MSK (Kafka) | $600 |
| Load Balancer | ALB | $25 |
| CDN | CloudFront | $10 |
| Monitoring | CloudWatch + Grafana | $20 |
| Secrets | Secrets Manager | $5 |
| CI/CD | GitHub Actions (free) | $0 |

---

### 2. Google Cloud Platform (GCP)

**Mumbai Region:** asia-south1 ✅

| Pros | Cons |
|------|------|
| $300 free credits for new accounts | Fewer India-specific services |
| Pub/Sub is much cheaper than Kafka | Smaller community |
| Good auto-scaling (Cloud Run) | Less mature managed Redis |
| Free tier generous | |
| Committed use discounts (40% off) | |

**Equivalent Services:**
| Need | GCP Service | Monthly Cost |
|------|------------|-------------|
| App hosting | Cloud Run / GCE | $55-120/instance |
| Database | Cloud SQL PostgreSQL | $55-220 |
| Cache | Memorystore Redis | $45-350 |
| Message queue | Pub/Sub | $50-200 (pay per message) |
| Load Balancer | Cloud Load Balancer | $20 |
| CDN | Cloud CDN | Free tier |
| Monitoring | Cloud Monitoring | Free |
| Secrets | Secret Manager | $3 |
| CI/CD | Cloud Build | Free tier |

**Best for:** If you want cheapest managed Kafka alternative (Pub/Sub) and free monitoring.

---

### 3. Microsoft Azure

**India Region:** Central India (Pune) ✅, South India (Chennai) ✅

| Pros | Cons |
|------|------|
| Two India regions | Most expensive |
| Good enterprise integration | Complex interface |
| Azure DevOps CI/CD built-in | Slower community support |
| $200 free credits | |

**Equivalent Services:**
| Need | Azure Service | Monthly Cost |
|------|------------|-------------|
| App hosting | Azure Container Instances / VM | $70-140/instance |
| Database | Azure Database for PostgreSQL | $70-280 |
| Cache | Azure Cache for Redis | $55-420 |
| Message queue | Event Hubs (Kafka compatible) | $200-500 |
| Load Balancer | Azure Application Gateway | $25 |
| CDN | Azure CDN | $10 |
| Monitoring | Azure Monitor | $30 |
| CI/CD | Azure DevOps | Free tier |

**Best for:** Enterprise environments, Microsoft stack teams.

---

### 4. DigitalOcean

**India Region:** Bangalore (BLR1) ✅

| Pros | Cons |
|------|------|
| **40-60% cheaper** than AWS | No managed Kafka |
| Simple pricing (flat monthly) | Fewer managed services |
| Good for startups | Limited auto-scaling |
| Managed DB + Redis available | Smaller IP pool |
| App Platform (like ECS) | No WAF |

**Equivalent Services:**
| Need | DO Service | Monthly Cost |
|------|------------|-------------|
| App hosting | Droplet (4 GB) × 2 | $48/each = $96 |
| Database | Managed PostgreSQL (4 GB) | $60 |
| Cache | Managed Redis (2 GB) | $30 |
| Message queue | Self-managed (Redis Streams) | $0 (use same Redis) |
| Load Balancer | DO Load Balancer | $12 |
| CDN | Cloudflare (free) | $0 |
| Monitoring | Uptime + Grafana Cloud (free) | $0 |
| CI/CD | GitHub Actions | $0 |

**Total: ~$198/month** for budget setup

**Best for:** Budget-conscious launch, simple ops. Good enough for 1000 users.

---

### 5. Hetzner Cloud

**India Region:** ❌ NO (EU + US only)

| Pros | Cons |
|------|------|
| **Cheapest** (70-80% less than AWS) | **No India region** — high latency to brokers |
| Excellent specs for price | No managed Kafka/Redis |
| Good for non-latency apps | Limited managed services |
| EU data privacy | |

**Not recommended for this project** — broker APIs need India proximity.

---

### 6. Oracle Cloud (OCI)

**India Region:** Mumbai ✅, Hyderabad ✅

| Pros | Cons |
|------|------|
| **Always Free Tier** (2 ARM VMs, 24 GB RAM!) | Smaller ecosystem |
| 10 TB/month free egress | Less community support |
| Cheapest managed Kubernetes | Complex setup |
| Two India regions | |

**Free Tier (genuinely free forever):**
- 2× ARM instances (4 vCPU, 24 GB each)
- 200 GB block storage
- 10 TB outbound data
- Autonomous Database (20 GB)

**Best for:** If you want to run cheaply or test for free. The ARM free tier is incredible.

---

## Decision Matrix

| Criteria (weight) | AWS | GCP | Azure | DigitalOcean | Oracle |
|-------------------|-----|-----|-------|-------------|--------|
| India region (10) | 10 | 10 | 10 | 10 | 10 |
| Cost efficiency (9) | 5 | 7 | 4 | 9 | 10 |
| Managed services (8) | 10 | 9 | 9 | 6 | 5 |
| Latency to brokers (10) | 10 | 10 | 10 | 10 | 10 |
| Migration effort (7) | 10 | 6 | 5 | 7 | 5 |
| Auto-scaling (6) | 10 | 9 | 8 | 5 | 6 |
| Team familiarity (8) | 10 | 5 | 4 | 7 | 3 |
| Kafka/event streaming (5) | 9 | 8 | 7 | 3 | 4 |
| **Weighted Score** | **452** | **399** | **368** | **383** | **351** |

---

## Final Recommendation

### For Immediate Launch (< 1000 users):
**Stay on AWS Mumbai** — zero migration, 1 day to set up. Cost: ~$310/month

### If Budget is the Priority:
**DigitalOcean Bangalore** — 40% cheaper, simple, good enough for 1000 users. Cost: ~$200/month

### For Maximum Scale (10,000+ users):
**AWS Mumbai** with full HA setup — best auto-scaling, managed Kafka, enterprise-grade. Cost: ~$1,700/month

### Free Testing:
**Oracle Cloud Free Tier** — 2 ARM VMs with 24 GB RAM each, free forever. Test everything before paying.

---

## Migration Effort Estimate

| From → To | Effort | Downtime |
|-----------|--------|----------|
| AWS Stockholm → AWS Mumbai | 1-2 days | 2 min (DNS switch) |
| AWS → GCP Mumbai | 3-4 days | 30 min |
| AWS → DigitalOcean | 2-3 days | 30 min |
| AWS → Azure | 3-4 days | 30 min |

**Recommendation: Stay on AWS, just move to Mumbai region.** Least risk, fastest execution.


---

## LATENCY ANALYSIS — Most Critical Factor

All Indian broker APIs are hosted in Mumbai/Bangalore. Network latency from server to broker:

| Server Location | Latency to NSE/Broker APIs | Suitable? |
|-----------------|---------------------------|-----------|
| **Mumbai (ap-south-1)** | **5-20ms** | ✅ BEST |
| **Bangalore** | 10-30ms | ✅ Good |
| **Chennai/Pune** | 15-40ms | ✅ Acceptable |
| **Singapore** | 60-100ms | ⚠️ Borderline |
| **Stockholm (current)** | 350-500ms | ❌ Too slow |
| **US East** | 250-400ms | ❌ Too slow |
| **EU (Hetzner/OVH)** | 300-450ms | ❌ Too slow |

**Conclusion: Only India-based servers are viable for copy trading.**

---

## ALL CLOUD PROVIDERS COMPARED

### Providers WITH India Presence

| Provider | India Location | Type | Budget Setup/mo | Full Scale/mo | Latency |
|----------|---------------|------|----------------|--------------|---------|
| **AWS** | Mumbai | Enterprise | $310 | $1,700 | 5-15ms |
| **GCP** | Mumbai | Enterprise | $270 | $1,160 | 5-15ms |
| **Azure** | Pune, Chennai | Enterprise | $335 | $1,710 | 10-25ms |
| **DigitalOcean** | Bangalore | Developer | $200 | $500 | 10-20ms |
| **Vultr** | Mumbai, Delhi, Bangalore | Developer | $160 | $400 | 5-15ms |
| **Oracle Cloud** | Mumbai, Hyderabad | Enterprise | $0 (free tier!) | $300 | 5-15ms |
| **Hostinger VPS** | Mumbai (via datacenter) | Budget | $60-100 | $200 | 15-30ms |
| **Linode (Akamai)** | Mumbai | Developer | $180 | $450 | 10-20ms |

### Providers WITHOUT India Presence (NOT RECOMMENDED)

| Provider | Closest Region | Latency to Brokers | Verdict |
|----------|---------------|-------------------|---------|
| **Hetzner** | Finland, Germany | 300-450ms | ❌ Not suitable |
| **OVH** | Singapore | 60-100ms | ⚠️ Too slow |
| **Contabo** | India (limited) | Unknown reliability | ⚠️ Risky |
| **Hostinger Shared** | EU/US | 300+ms | ❌ Not suitable |

---

## DETAILED PROVIDER PROFILES

### 7. Hostinger VPS

**India Datacenter:** Yes (via partnership, Mumbai routing)

| Pros | Cons |
|------|------|
| Very cheap ($5-30/mo VPS) | No managed DB/Redis |
| Simple panel (hPanel) | No load balancer |
| Good for basic hosting | No auto-scaling |
| | No Kafka/queue service |
| | Limited support for production |
| | Shared infrastructure concerns |

| VPS Plan | Spec | Cost/month |
|----------|------|-----------|
| KVM 2 | 2 vCPU, 8 GB RAM, 100 GB | $12 |
| KVM 4 | 4 vCPU, 16 GB RAM, 200 GB | $16 |
| KVM 8 | 8 vCPU, 32 GB RAM, 400 GB | $25 |

**Setup for Ascentra:**
| Component | How | Cost |
|-----------|-----|------|
| App Server | KVM 4 × 2 | $32 |
| Database | Self-managed PostgreSQL on VPS | $16 |
| Redis | Self-managed on same VPS | $0 |
| Load Balancer | Nginx (self-managed) | $0 |
| **Total** | | **~$48/month** |

**⚠️ RISK:** No managed services means YOU manage backups, failover, security patches, upgrades. One DB crash = data loss if backup fails. Not recommended for financial applications handling real money.

---

### 8. Vultr

**India Regions:** Mumbai ✅, Delhi NCR ✅, Bangalore ✅ (3 locations!)

| Pros | Cons |
|------|------|
| 3 India locations (best coverage) | No managed Kafka |
| Very competitive pricing | Smaller ecosystem than AWS |
| Managed DB + LB available | Limited auto-scaling |
| Bare metal options | |
| Excellent network (Tier 1) | |

| Component | Vultr Service | Cost/month |
|-----------|-------------|-----------|
| App Server (4 GB) × 2 | Cloud Compute | $48 |
| Managed PostgreSQL (4 GB) | Managed Database | $60 |
| Managed Redis (2 GB) | — (self-host on VPS) | $12 |
| Load Balancer | Vultr LB | $10 |
| **Total** | | **~$130/month** |

**Best for:** Cheapest "proper" cloud with India Mumbai presence.

---

### 9. Linode (now Akamai Cloud)

**India Region:** Mumbai ✅

| Pros | Cons |
|------|------|
| Simple, developer-friendly | Fewer managed services |
| Flat pricing (predictable) | No managed Kafka |
| Good support | Smaller scale options |
| Managed DB available | |

| Component | Linode Service | Cost/month |
|-----------|-------------|-----------|
| App Server (4 GB) × 2 | Linode 4GB | $72 |
| Managed PostgreSQL (4 GB) | Managed DB | $65 |
| Redis (shared) | Self-hosted on dedicated Linode | $12 |
| NodeBalancer | | $10 |
| **Total** | | **~$159/month** |

---

### 10. Oracle Cloud (OCI)

**India Region:** Mumbai ✅, Hyderabad ✅

| Pros | Cons |
|------|------|
| **FREE TIER: 2 ARM VMs (24 GB each!)** | Learning curve |
| 10 TB/month free egress | Smaller community |
| Autonomous DB free (20 GB) | Less documentation |
| Cheapest at scale | Complex interface |

**Free Forever Tier:**
- 2× Ampere ARM instances (4 OCPU, 24 GB RAM each)
- 200 GB block storage
- 10 TB/month outbound
- Autonomous Database 20 GB
- Load Balancer (10 Mbps)

**Cost for Ascentra on Free Tier: $0/month** (seriously)

**Paid (if you outgrow free):**
| Component | OCI Service | Cost/month |
|-----------|-------------|-----------|
| Compute (4 OCPU, 24 GB) × 2 | VM.Standard.A1.Flex | $50 |
| Autonomous PostgreSQL | Autonomous DB | $60 |
| Redis | OCI Cache | $40 |
| LB | Flexible LB | $10 |
| **Total** | | **~$160/month** |

---

## RECOMMENDATION FOR BUSINESS LEADERSHIP

### Option A: Stay on AWS, Move to Mumbai (RECOMMENDED)

| Factor | Rating |
|--------|--------|
| Risk | ★☆☆☆☆ (lowest — no migration complexity) |
| Latency | ★★★★★ (5-15ms to brokers) |
| Cost | ★★★☆☆ ($310/mo budget, $1,700 full scale) |
| Scalability | ★★★★★ (auto-scale to any size) |
| Time to go live | **1-2 days** |

### Option B: Vultr Mumbai (BEST VALUE)

| Factor | Rating |
|--------|--------|
| Risk | ★★☆☆☆ (migration needed, simpler platform) |
| Latency | ★★★★★ (5-15ms, Mumbai datacenter) |
| Cost | ★★★★★ ($130/mo — 60% cheaper than AWS) |
| Scalability | ★★★☆☆ (manual scaling, no Kafka) |
| Time to go live | **3-4 days** |

### Option C: Oracle Cloud Free Tier (CHEAPEST START)

| Factor | Rating |
|--------|--------|
| Risk | ★★★☆☆ (new platform, learning curve) |
| Latency | ★★★★★ (5-15ms, Mumbai) |
| Cost | ★★★★★ (**$0/month** on free tier!) |
| Scalability | ★★★★☆ (good, but less familiar) |
| Time to go live | **4-5 days** |

### Option D: Hostinger VPS (NOT RECOMMENDED for Finance)

| Factor | Rating |
|--------|--------|
| Risk | ★★★★★ (highest — no managed services, no failover) |
| Latency | ★★★★☆ (15-30ms, acceptable) |
| Cost | ★★★★★ ($48/mo — cheapest) |
| Scalability | ★☆☆☆☆ (manual everything) |
| Time to go live | **5-7 days** |

**⚠️ NOT SUITABLE for financial application.** No automated backups, no failover, no managed DB. One server failure = downtime + possible data loss.

---

## FINAL VERDICT

For a **copy trading platform handling real money** in production:

1. **Best overall:** AWS Mumbai — proven, scalable, already familiar. Cost: $310/mo
2. **Best value:** Vultr Mumbai — 60% cheaper, same latency, good enough for 1000+ users. Cost: $130/mo
3. **Free start:** Oracle Cloud Free Tier — test everything for $0, move to paid when needed
4. **Avoid:** Hostinger/shared hosting — not suitable for financial applications

**My recommendation as Technical Lead:** Start with **AWS Mumbai** (Option A). Zero risk, 1 day migration, already proven. Scale costs with growth. If budget is tight, **Vultr Mumbai** (Option B) is a solid alternative.
