# Ascentra Copy Trading — Production Infrastructure Document

**Prepared by:** Development Team  
**For:** Aniket Dhoke (Owner), Sushil Jadhav  
**Date:** June 24, 2026  
**Target:** India-wide launch, 10,000+ concurrent users

---

## 1. Current State vs Required State

| Parameter | Current | Required (India Launch) |
|-----------|---------|------------------------|
| Instance | t3.small (2 vCPU, 2 GB) | c6i.2xlarge (8 vCPU, 16 GB) × 3 |
| Region | Stockholm (eu-north-1) | **Mumbai (ap-south-1)** |
| Database | db.t3.micro (single AZ) | db.r6g.large (Multi-AZ, read replicas) |
| Redis | Local on EC2 | ElastiCache r6g.large (cluster mode) |
| Load Balancer | None | ALB with WAF |
| CDN | None | CloudFront |
| Message Queue | None | Amazon MSK (Kafka) or SQS |
| Monitoring | Log file | Prometheus + Grafana + CloudWatch |
| CI/CD | Manual SSH | GitHub Actions → ECR → ECS |
| Docker | No | Yes (ECS Fargate or EKS) |
| SSL | Direct EC2 | ACM + ALB termination |
| Auto-scaling | No | Yes (ECS auto-scale on CPU/connections) |

---

## 2. Technology Stack (Confirmed)

| Layer | Technology | Version |
|-------|-----------|---------|
| Backend | Java + Spring Boot WebFlux (Reactive) | **Java 21**, Spring Boot 3.2.5 |
| Database | PostgreSQL (AWS RDS) | 15.x |
| Cache | Redis (ElastiCache) | 7.x |
| Message Broker | Apache Kafka (MSK) or Redis Streams | Kafka 3.5+ |
| Frontend | React / Next.js | 14+ |
| Mobile | React Native or Flutter | (if applicable) |
| Container | Docker + ECS Fargate | Latest |
| Build | Gradle 8.14 | 8.14 |
| Proxy/IP Routing | Squid (multi-IP for broker IP whitelisting) | 5.x |
| WebSocket | Netty (Spring WebFlux built-in) + Broker WS feeds | — |
| Monitoring | Prometheus + Grafana | Latest |
| Logging | Loki + Promtail (or CloudWatch Logs) | — |
| OTP/SMS | AWS SNS + Twilio Verify | — |
| Auth | JWT (access + refresh tokens) + Google OAuth | — |
| Notifications | Telegram Bot API + In-app push | — |
| Broker APIs | Groww, Upstox, Zerodha, Fyers, Angel One, Dhan | REST + WebSocket |

---

## 3. Environments Required

| Environment | Purpose | Infra | Database |
|-------------|---------|-------|----------|
| **Development** | Local dev, unit tests | Developer laptop | H2 / local Postgres |
| **Staging (UAT)** | QA, integration testing, broker sandbox | t3.medium + shared RDS | Separate DB: `copytrading_staging` |
| **Production** | Live trading, real money | Full HA setup (see below) | `copytrading` Multi-AZ |

**Staging mimics production** but with smaller instances. All broker integrations test against real APIs with test accounts.

---

## 4. Database Sizing & Growth (12 Months)

| Table | Current Rows | 12-Month Projection (10K users) |
|-------|-------------|-------------------------------|
| users | 15 | 50,000 |
| broker_accounts | 27 | 150,000 (3 brokers/user avg) |
| subscriptions | 10 | 200,000 |
| trades | 500 | 5,000,000 |
| copy_logs | 2,000 | 20,000,000 |
| risk_rules | 10 | 50,000 |
| **Total DB size** | **~50 MB** | **~20-50 GB** |

### RDS Configuration

| Setting | Value |
|---------|-------|
| Engine | PostgreSQL 15 |
| Instance | db.r6g.large (2 vCPU, 16 GB RAM) |
| Storage | 200 GB gp3 (auto-scaling to 1 TB) |
| Multi-AZ | Yes (failover in <60s) |
| Read Replicas | 1 (for dashboard queries, analytics) |
| Backup | 7-day automated + manual pre-deploy |
| IOPS | 3000 baseline (gp3) |
| Connection pooling | PgBouncer or RDS Proxy |

---

## 5. Load Balancer & SSL

| Component | Configuration |
|-----------|--------------|
| ALB | Application Load Balancer in ap-south-1 |
| SSL | AWS ACM (free, auto-renewing) |
| WAF | AWS WAF (rate limiting, bot protection) |
| Health check | `/actuator/health` every 10s |
| Target groups | 3 ECS tasks (app instances) |
| Sticky sessions | No (stateless app, sessions in Redis) |
| Domain | `api.ascentracapital.com` → ALB |
| Frontend | `app.ascentracapital.com` → CloudFront → S3 |

---

## 6. Source Code & Repository

| Item | Details |
|------|---------|
| Platform | GitHub |
| Backend repo | `github.com/dhokeaniket/copy-trading` |
| Frontend repo | Separate repo (to be confirmed) |
| Branch strategy | `main` → production, `develop` → staging |
| Protected branches | `main` (require PR + review) |
| Access | Full team access via GitHub Teams |

---

## 7. Docker Setup (Required)

### Dockerfile (multi-stage)
```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew clean build -x test

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/copy-trading-backend-0.1.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-Xmx2g", "-jar", "app.jar"]
```

### Container specs per instance
- CPU: 2 vCPU
- Memory: 4 GB
- Instances: 3 (auto-scale 3-10)

---

## 8. Deployment Strategy

### Current (Manual)
```
Developer → git push → SSH to EC2 → git pull → gradle build → restart
Downtime: 30 seconds
```

### Required (Automated, Zero-Downtime)
```
Developer → git push main → GitHub Actions → Build Docker → Push ECR → ECS Rolling Deploy
Downtime: 0 seconds (rolling update)
Rollback: 1-click to previous task definition
```

---

## 9. CI/CD Pipeline (GitHub Actions)

```yaml
# .github/workflows/deploy.yml
on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - Checkout code
      - Run tests (./gradlew test)
      - Build Docker image
      - Push to AWS ECR
      - Update ECS service (rolling deploy)
      - Health check verification
      - Notify Telegram on success/failure
```

**Time:** Push to live in ~3 minutes

---

## 10. Monitoring & Logging

| Tool | Purpose | Setup |
|------|---------|-------|
| **Prometheus** | Metrics collection (JVM, HTTP, custom) | Spring Boot Actuator + Micrometer |
| **Grafana** | Dashboards, alerting | Hosted on separate t3.small |
| **Loki** | Centralized log aggregation | All app logs → Loki |
| **AlertManager** | Critical alerts (app down, trade failures) | → Telegram + Email |
| **CloudWatch** | AWS infra metrics (CPU, network, RDS) | Built-in |
| **Uptime Robot** | External uptime check | Free tier |

### Key Dashboards
- Copy trade latency (p50, p95, p99)
- Orders placed/failed per minute
- Broker API response times
- Active WebSocket connections
- JVM heap, GC pauses
- DB connection pool usage

---

## 11. Production Architecture (India-Scale)

```
                         ┌─────────────────┐
                         │   CloudFront    │ ← Frontend (React)
                         │   (CDN + WAF)   │
                         └───────┬─────────┘
                                 │
Users (India) ──── HTTPS ──── ALB (ap-south-1)
                                 │
                    ┌────────────┼────────────┐
                    │            │            │
              ┌─────┴─────┐ ┌───┴───┐ ┌─────┴─────┐
              │  ECS App  │ │  ECS  │ │  ECS App  │
              │ Instance 1│ │  App 2│ │ Instance 3│
              └─────┬─────┘ └───┬───┘ └─────┬─────┘
                    │            │            │
         ┌──────────┼────────────┼────────────┤
         │          │            │            │
    ┌────┴────┐ ┌───┴───┐ ┌────┴────┐ ┌────┴────┐
    │  RDS    │ │ Redis │ │  Kafka  │ │  Squid  │
    │(Primary)│ │Cluster│ │  (MSK)  │ │(Proxy)  │
    └────┬────┘ └───────┘ └─────────┘ └─────────┘
         │
    ┌────┴────┐
    │   RDS   │
    │(Replica)│
    └─────────┘
```

---

## 12. Kafka & Redis for Low Latency

### Redis Configuration (ElastiCache)

| Setting | Value | Reason |
|---------|-------|--------|
| Node type | cache.r6g.large (2 vCPU, 13 GB) | Fast in-memory ops |
| Cluster mode | Enabled (3 shards) | Horizontal scale |
| Replicas per shard | 1 | Read scaling + failover |
| Same AZ as app | Yes | <1ms latency |
| Eviction | `allkeys-lru` | Auto-cleanup |
| Max connections | 65000 | Handle 10K+ users |

**Use cases:**
- Polling state (known orders, seen accounts)
- Session cache
- Rate limiting counters
- WebSocket session registry
- Pub/Sub for real-time notifications

### Kafka (MSK) Configuration

| Setting | Value | Reason |
|---------|-------|--------|
| Broker type | kafka.m5.large (2 vCPU, 8 GB) | Throughput |
| Brokers | 3 (one per AZ) | High availability |
| `acks` | 1 | Low latency (leader only) |
| `linger.ms` | 0 | No batching delay |
| `compression` | lz4 | Fast compression |
| `num.partitions` | 12 | Parallel consumers |
| `replication.factor` | 2 | Durability |
| `min.insync.replicas` | 1 | Don't block on replica |
| Consumer `fetch.max.wait.ms` | 10ms | Near-instant consume |

**Topics:**
| Topic | Partitions | Purpose |
|-------|-----------|---------|
| `master.orders.detected` | 12 | Order detection → copy engine |
| `child.orders.placed` | 12 | Placed orders → logging/notification |
| `child.orders.failed` | 6 | Failures → alerting |
| `broker.events` | 6 | Login/disconnect/session events |

**Latency target with Kafka:** Order detected → child order placed in **<100ms** (within same region)

---

## 13. Server Sizing for India Traffic

### Peak Load Assumptions
- 10,000 registered users
- 2,000 concurrent during market hours (9:15 AM - 3:30 PM)
- 100 masters, each with 50-200 subscribers
- 500 orders/minute peak (across all masters)
- 50,000 copy operations/minute peak

### Compute Requirements

| Component | Spec | Instances | Monthly Cost |
|-----------|------|-----------|-------------|
| App Server (ECS) | c6i.xlarge (4 vCPU, 8 GB) | 3-10 (auto-scale) | $300-1000 |
| RDS Primary | db.r6g.large (2 vCPU, 16 GB) | 1 | $250 |
| RDS Replica | db.r6g.large | 1 | $250 |
| ElastiCache Redis | cache.r6g.large | 3 nodes | $400 |
| MSK Kafka | kafka.m5.large | 3 brokers | $600 |
| ALB | — | 1 | $25 |
| NAT Gateway | — | 1 | $35 |
| Squid Proxy EC2 | t3.medium (multi-IP) | 2 (Mumbai + backup) | $70 |
| Monitoring (Grafana) | t3.small | 1 | $20 |
| **Total** | | | **~$1,950-2,650/month** |

### Budget-Friendly Alternative (Start)

| Component | Spec | Monthly Cost |
|-----------|------|-------------|
| App Server | c6i.large (2 vCPU, 4 GB) × 2 | $130 |
| RDS | db.t3.medium (Multi-AZ) | $60 |
| Redis | cache.t3.medium | $50 |
| ALB | — | $25 |
| Proxy EC2 | t3.small (current, move to Mumbai) | $20 |
| **Total** | | **~$285/month** |

Scale up as users grow. Start lean, add Kafka when >100 masters.

---

## 14. Migration Plan (Stockholm → Mumbai)

| Step | Action | Downtime |
|------|--------|----------|
| 1 | Set up Mumbai VPC, subnets, security groups | 0 |
| 2 | Create RDS in Mumbai, replicate data | 0 |
| 3 | Deploy app to Mumbai ECS | 0 |
| 4 | Test with staging domain | 0 |
| 5 | Switch DNS from Stockholm to Mumbai | ~2 min |
| 6 | Verify all broker APIs work from Mumbai | 0 |
| 7 | Decommission Stockholm (keep proxy for EU IPs) | 0 |

**Latency improvement:** Broker API calls drop from 400-700ms to **30-80ms**

---

## 15. Security Requirements

| Area | Requirement |
|------|-------------|
| Encryption at rest | RDS encrypted, EBS encrypted |
| Encryption in transit | TLS 1.3 everywhere |
| Secrets management | AWS Secrets Manager (not env vars) |
| API auth | JWT + refresh tokens |
| Admin access | IP-restricted + 2FA |
| Broker credentials | AES-256 encrypted in DB |
| WAF | Rate limiting, SQL injection protection |
| VPC | Private subnets for app/DB, public only for ALB |
| Audit log | All admin actions logged |

---

## 16. Answers to Specific Questions

**Q: Is the current EC2 sufficient?**  
A: No. For India launch, need minimum c6i.large in Mumbai with ALB.

**Q: Technology stack?**  
A: Java 17, Spring Boot 3 WebFlux, PostgreSQL 15, Redis 7, React frontend. See Section 2.

**Q: How many environments?**  
A: 3 — Dev (local), Staging (shared infra), Production (full HA). See Section 3.

**Q: Database size & growth?**  
A: Currently 50MB, expect 20-50GB in 12 months. See Section 4.

**Q: Load Balancer & SSL?**  
A: SSL via ACM (free), ALB required. See Section 5.

**Q: Source code access?**  
A: GitHub, full team access. See Section 6.

**Q: Dockerized?**  
A: Not yet. Required before launch. See Section 7.

**Q: Automated deployments?**  
A: Required. GitHub Actions + ECS rolling deploy. See Section 9.

**Q: CI/CD pipeline?**  
A: None currently. GitHub Actions recommended. See Section 9.

**Q: Monitoring?**  
A: Prometheus + Grafana recommended. See Section 10.

**Q: Traffic management?**  
A: ALB + auto-scaling ECS. See Section 11.

**Q: Kafka & Redis config?**  
A: Detailed in Section 12. Redis for cache/state, Kafka for event streaming at scale.

---

## 17. Priority Actions (Ordered)

| Priority | Action | Impact | Effort |
|----------|--------|--------|--------|
| � P0 | Migrate to Mumbai region | -400ms latency | 2 days |
| � P0 | Dockerize + CI/CD | Zero-downtime deploys | 1 day |
| 🟡 P1 | Add ALB + auto-scaling | Handle traffic spikes | 1 day |
| � P1 | WebSocket order detection | -300ms latency | 3 days |
| 🟡 P1 | Prometheus + Grafana | Visibility | 1 day |
| 🟢 P2 | Add Kafka for event decoupling | Scale to 500+ masters | 2 days |
| 🟢 P2 | RDS read replica | Dashboard performance | 1 hour |
| 🟢 P2 | Redis cluster mode | Handle 10K+ sessions | 1 hour |


---

## 18. PURCHASE LIST — What to Buy for Launch

Everything the owner needs to purchase/set up before India launch:

### AWS Services (Pay-as-you-go, no upfront)

| # | Service | Spec | Region | Monthly Cost | Action |
|---|---------|------|--------|-------------|--------|
| 1 | **EC2 App Server** | c6i.large (2 vCPU, 4 GB) × 2 | ap-south-1 (Mumbai) | $130 | Launch via AWS Console |
| 2 | **RDS PostgreSQL** | db.t3.medium, Multi-AZ, 100GB gp3 | ap-south-1 | $60 | Create in RDS Console |
| 3 | **ElastiCache Redis** | cache.t3.medium, 1 node | ap-south-1 | $50 | Create in ElastiCache |
| 4 | **Application Load Balancer** | ALB + target group | ap-south-1 | $25 | Create in EC2 → Load Balancers |
| 5 | **ACM SSL Certificate** | *.ascentracapital.com | ap-south-1 | **FREE** | AWS Certificate Manager |
| 6 | **Elastic IPs** | 10-15 IPs for broker whitelisting | ap-south-1 | $5/IP unused | EC2 → Elastic IPs |
| 7 | **S3 Bucket** | Frontend hosting | ap-south-1 | ~$5 | S3 Console |
| 8 | **CloudFront CDN** | Frontend distribution | Global | ~$10 | CloudFront Console |
| 9 | **Route 53** | DNS hosting | Global | $0.50/zone | Already configured? |
| 10 | **NAT Gateway** | For private subnet outbound | ap-south-1 | $35 | VPC Console |
| 11 | **Secrets Manager** | Store broker keys, DB creds | ap-south-1 | $5 | Secrets Manager |
| 12 | **CloudWatch Logs** | Basic log storage | ap-south-1 | ~$10 | Auto with EC2 |

**Total AWS: ~$330-350/month to start**

### Third-Party Services

| # | Service | Purpose | Cost | Action |
|---|---------|---------|------|--------|
| 1 | **GitHub (Team plan)** | Code hosting + CI/CD minutes | $4/user/month | Already have |
| 2 | **Twilio** | OTP/SMS verification | Pay-per-use (~$50/mo) | Already configured |
| 3 | **Telegram Bot** | Trade notifications | **FREE** | Already configured |
| 4 | **Domain** | ascentracapital.com | ~$12/year | Already have |
| 5 | **UptimeRobot** | External monitoring | **FREE** (basic) | Sign up |
| 6 | **Grafana Cloud** | Metrics + dashboards | **FREE** (10K metrics) | Sign up |

### Broker API Accounts (Required per broker)

| # | Broker | What to Get | Cost | Notes |
|---|--------|-------------|------|-------|
| 1 | **Groww** | Trade API key | FREE | Apply at groww.in/trade-api |
| 2 | **Upstox** | Developer App (OAuth) | FREE | developers.upstox.com |
| 3 | **Zerodha** | Kite Connect API | ₹2,000/month | developers.kite.trade |
| 4 | **Angel One** | SmartAPI key | FREE | smartapi.angelone.in |
| 5 | **Fyers** | MyAPI app | FREE | myapi.fyers.in |
| 6 | **Dhan** | DhanHQ API | FREE | dhanhq.co |

### One-Time Setup Tasks (Dev Team)

| # | Task | Time | Dependency |
|---|------|------|------------|
| 1 | Set up Mumbai VPC + subnets + security groups | 2 hours | AWS account |
| 2 | Create RDS + Redis in Mumbai | 1 hour | VPC ready |
| 3 | Dockerize application | 4 hours | None |
| 4 | Set up GitHub Actions CI/CD | 4 hours | Docker ready |
| 5 | Deploy to Mumbai, test all brokers | 4 hours | All above |
| 6 | Configure ALB + SSL + domain | 2 hours | ACM cert |
| 7 | Set up Squid proxy (multi-IP) in Mumbai | 3 hours | EC2 ready |
| 8 | Set up monitoring (Prometheus + Grafana) | 3 hours | App running |
| 9 | Load testing (simulate 1000 users) | 4 hours | All above |
| 10 | DNS cutover (go live) | 30 min | All above |

**Total setup time: ~3-4 days**

---

## 19. Launch Checklist

Before going live with real users:

- [ ] Mumbai infrastructure deployed and tested
- [ ] All 6 broker APIs working from Mumbai IPs
- [ ] SSL certificate active on api.ascentracapital.com
- [ ] CI/CD pipeline deploying correctly
- [ ] Monitoring dashboards showing metrics
- [ ] Telegram alerts firing on failures
- [ ] Load test passed (500 concurrent copies)
- [ ] Database backup configured (daily)
- [ ] Secrets moved from env vars to Secrets Manager
- [ ] WAF rules active (rate limiting, bot protection)
- [ ] Admin can impersonate users for support
- [ ] Risk rules set for all children (default 50 trades/day)
- [ ] Kill switch tested (stops all copying instantly)
- [ ] Sell guard working across all brokers
- [ ] WebSocket connections stable for Upstox
- [ ] Frontend deployed to S3 + CloudFront

---

## 20. Note on Java Version

**Build config:** `build.gradle` specifies Java 21 (`languageVersion = JavaLanguageVersion.of(21)`)  
**EC2 runtime:** Currently Java 17 (`amazon-corretto-17`) — Gradle auto-downloads Java 21 for compilation via toolchain.

**For Mumbai production:** Install Amazon Corretto 21 on ECS/Docker image. Use `eclipse-temurin:21-jre-alpine` in Dockerfile.
