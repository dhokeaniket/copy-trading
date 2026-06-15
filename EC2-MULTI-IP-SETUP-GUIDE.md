# EC2 Multi-IP Proxy Setup Guide

Complete reference for adding new IPs to the Ascentra proxy infrastructure.

---

## Current Infrastructure (as of Jun 10, 2026)

### Stockholm EC2 (App Server)
- **Instance**: `i-0847acc4d55fd504a` | `t3.small` | `eu-north-1a`
- **SSH**: `ssh ec2-user@13.53.246.13` (key: `ascentra-key`)
- **ENIs**: 3 attached (max for t3.small)
  - `eni-0666927c9e6cdd1fc` (primary) — 4 IPs
  - `eni-0de622f6ac8003b00` (eth1) — 4 IPs
  - `eni-04da0c5abcfeef2a6` (eth2) — 3 IPs
- **Proxy**: Squid on ports 8889-8897
- **Max IPs**: 12 total (4 per ENI × 3 ENIs)

### Mumbai EC2 (Proxy Server)
- **SSH**: `ssh -i ~/proxy-mumbai.pem ubuntu@15.207.205.137`
- **ENI**: 1 (ens5) — 4 secondary IPs + 1 primary
- **Proxy**: Squid on ports 3128-3131

---

## Current IP Table

### Stockholm (10 IPs)
| # | Public IP | Private IP | ENI | Proxy Port | Status |
|---|-----------|-----------|-----|------------|--------|
| 1 | 13.53.246.13 | 172.31.26.85 | eni-0666...(primary) | — (direct) | Active |
| 2 | 13.61.58.89 | 172.31.22.83 | eni-0de6...(eth1) | 8889 | Active |
| 3 | 13.60.103.120 | 172.31.17.31 | eni-0666...(primary) | 8890 | Active |
| 4 | 56.228.67.106 | 172.31.30.41 | eni-0666...(primary) | 8891 | Active |
| 5 | 13.48.122.204 | 172.31.22.196 | eni-0666...(primary) | 8892 | Active |
| 6 | 13.63.33.92 | 172.31.31.11 | eni-0de6...(eth1) | 8893 | Active |
| 7 | 13.62.184.217 | 172.31.16.197 | eni-0de6...(eth1) | 8894 | Active |
| 8 | 13.48.142.174 | 172.31.26.18 | eni-0de6...(eth1) | 8895 | Active |
| 9 | 16.192.7.87 | 172.31.27.63 | eni-04da...(eth2) | 8896 | Active |
| 10 | 13.63.15.121 | 172.31.29.35 | eni-04da...(eth2) | 8897 | Active |

### Mumbai (5 IPs)
| # | Public IP | Private IP | Proxy Port | Status |
|---|-----------|-----------|------------|--------|
| 1 | 15.207.205.137 | 172.31.41.5 | 3128 | Active |
| 2 | 15.207.175.205 | 172.31.36.30 | 3129 | Active |
| 3 | 3.108.243.110 | 172.31.45.247 | 3130 | Active |
| 4 | 13.207.20.220 | 172.31.45.67 | 3131 | Active |
| 5 | 13.234.74.28 | (needs setup) | 3132 | Pending |

---

## How to Add a New IP (Step-by-Step)

### Step 1: Check EIP Quota
```bash
# Check current count
aws ec2 describe-addresses --region <REGION> --query "Addresses[*].PublicIp" --output text | wc -w

# If at limit, request increase from AWS Console:
# Service Quotas → EC2 → "EC2-VPC Elastic IPs" → Request increase
```

### Step 2: Allocate New EIP
```bash
aws ec2 allocate-address --domain vpc --region <REGION>
# Note the AllocationId and PublicIp
```

### Step 3: Add Private IP to ENI
```bash
# Check which ENI has room (max 4 per ENI on t3.small)
aws ec2 describe-instances --instance-ids <INSTANCE_ID> --region <REGION> \
  --query "Reservations[0].Instances[0].NetworkInterfaces[*].[NetworkInterfaceId,PrivateIpAddresses[*].PrivateIpAddress]" \
  --output json

# Add to an ENI with <4 IPs
aws ec2 assign-private-ip-addresses --network-interface-id <ENI_ID> \
  --secondary-private-ip-address-count 1 --region <REGION>
```

### Step 4: Associate EIP to Private IP
```bash
aws ec2 associate-address --allocation-id <ALLOC_ID> \
  --network-interface-id <ENI_ID> \
  --private-ip-address <PRIVATE_IP> --region <REGION>
```

### Step 5: Add IP to OS (on the EC2 instance)
```bash
# Stockholm: eth0=eni-0666, eth1=eni-0de6, eth2=eni-04da
sudo ip addr add <PRIVATE_IP>/20 dev <ethX>
```

### Step 6: Add to Squid Config
```bash
# Edit squid.conf
sudo vi /etc/squid/squid.conf

# Add:
#   http_port <NEW_PORT>
#   acl port<NEW_PORT> localport <NEW_PORT>
#   tcp_outgoing_address <PRIVATE_IP> port<NEW_PORT>

# Restart
sudo systemctl restart squid

# Verify
curl -s --proxy http://127.0.0.1:<NEW_PORT> https://api.ipify.org
```

### Step 7: Make IPs Persist (survive reboot)

**Stockholm (Amazon Linux 2023):**
Create a startup script:
```bash
sudo tee /etc/rc.d/rc.local << 'EOF'
#!/bin/bash
# Secondary IPs for proxy
ip addr add 172.31.31.11/20 dev eth1 2>/dev/null
ip addr add 172.31.16.197/20 dev eth1 2>/dev/null
ip addr add 172.31.26.18/20 dev eth1 2>/dev/null
ip addr add 172.31.27.63/20 dev eth2 2>/dev/null
ip addr add 172.31.29.35/20 dev eth2 2>/dev/null
EOF
sudo chmod +x /etc/rc.d/rc.local
sudo systemctl enable rc-local
```

**Mumbai (Ubuntu):**
Add to netplan:
```bash
sudo vi /etc/netplan/50-cloud-init.yaml
# Under the ens5 interface, add:
#   addresses:
#     - 172.31.36.30/20
#     - 172.31.45.247/20
#     - 172.31.45.67/20
sudo netplan apply
```

---

## How to Add a New ENI (when all ENIs are full)

### Option A: Upgrade instance type
| Type | ENIs | IPs/ENI | Total IPs |
|------|------|---------|-----------|
| t3.micro | 2 | 2 | 4 |
| t3.small | 3 | 4 | 12 |
| t3.medium | 3 | 6 | 18 |
| t3.large | 3 | 12 | 36 |

```bash
# Must stop instance first (from AWS Console):
# EC2 → Instance → Stop → Change Instance Type → Start
```

### Option B: Add new ENI (if slots available)
```bash
# Get subnet and SG
SUBNET_ID=$(aws ec2 describe-instances --instance-ids <ID> --region <REGION> \
  --query "Reservations[0].Instances[0].SubnetId" --output text)
SG_ID=$(aws ec2 describe-instances --instance-ids <ID> --region <REGION> \
  --query "Reservations[0].Instances[0].SecurityGroups[0].GroupId" --output text)

# Create ENI with secondary IPs
NEW_ENI=$(aws ec2 create-network-interface --subnet-id $SUBNET_ID --groups $SG_ID \
  --secondary-private-ip-address-count 3 --region <REGION> \
  --query "NetworkInterface.NetworkInterfaceId" --output text)

# Attach (next available device index)
aws ec2 attach-network-interface --network-interface-id $NEW_ENI \
  --instance-id <ID> --device-index <NEXT_INDEX> --region <REGION>
```

---

## Stockholm Squid Config (current)

File: `/etc/squid/squid.conf`
```
http_port 8889
http_port 8890
http_port 8891
http_port 8892
http_port 8893
http_port 8894
http_port 8895
http_port 8896
http_port 8897
http_access allow all
access_log none

acl port8889 localport 8889
acl port8890 localport 8890
acl port8891 localport 8891
acl port8892 localport 8892
acl port8893 localport 8893
acl port8894 localport 8894
acl port8895 localport 8895
acl port8896 localport 8896
acl port8897 localport 8897

tcp_outgoing_address 172.31.22.83 port8889
tcp_outgoing_address 172.31.17.31 port8890
tcp_outgoing_address 172.31.30.41 port8891
tcp_outgoing_address 172.31.22.196 port8892
tcp_outgoing_address 172.31.31.11 port8893
tcp_outgoing_address 172.31.16.197 port8894
tcp_outgoing_address 172.31.26.18 port8895
tcp_outgoing_address 172.31.27.63 port8896
tcp_outgoing_address 172.31.29.35 port8897
```

---

## Mumbai Squid Config (current)

File: `/etc/squid/squid.conf` (on ubuntu@15.207.205.137)
```
http_port 3128
http_port 3129
http_port 3130
http_port 3131
http_access allow all
access_log none

acl port3128 localport 3128
acl port3129 localport 3129
acl port3130 localport 3130
acl port3131 localport 3131

tcp_outgoing_address 172.31.41.5 port3128
tcp_outgoing_address 172.31.36.30 port3129
tcp_outgoing_address 172.31.45.247 port3130
tcp_outgoing_address 172.31.45.67 port3131
```

---

## App Deploy Command (Stockholm)

```bash
pkill -9 -f java; sleep 3; cd /home/ec2-user/copy-trading && nohup java -Xmx512m \
  -Dgoogle.client-id=<GOOGLE_CLIENT_ID> \
  -Dtwilio.account-sid=<TWILIO_ACCOUNT_SID> \
  -Dtwilio.api-key=<TWILIO_API_KEY> \
  -Dtwilio.api-secret=<TWILIO_API_SECRET> \
  -Dtwilio.verify-service-sid=<TWILIO_VERIFY_SERVICE_SID> \
  -jar build/libs/copy-trading-backend-0.1.0.jar >> /home/ec2-user/ascentra.log 2>&1 &
```

---

## Frontend IP Table (for users)

| # | IP to Whitelist | Proxy Host (UI) | Proxy Port (UI) | Location |
|---|----------------|-----------------|-----------------|----------|
| 1 | 13.53.246.13 | — | — | Stockholm (direct) |
| 2 | 13.61.58.89 | 127.0.0.1 | 8889 | Stockholm |
| 3 | 13.60.103.120 | 127.0.0.1 | 8890 | Stockholm |
| 4 | 56.228.67.106 | 127.0.0.1 | 8891 | Stockholm |
| 5 | 13.48.122.204 | 127.0.0.1 | 8892 | Stockholm |
| 6 | 13.63.33.92 | 127.0.0.1 | 8893 | Stockholm |
| 7 | 13.62.184.217 | 127.0.0.1 | 8894 | Stockholm |
| 8 | 13.48.142.174 | 127.0.0.1 | 8895 | Stockholm |
| 9 | 16.192.7.87 | 127.0.0.1 | 8896 | Stockholm |
| 10 | 13.63.15.121 | 127.0.0.1 | 8897 | Stockholm |
| 11 | 15.207.205.137 | 15.207.205.137 | 3128 | Mumbai |
| 12 | 15.207.175.205 | 15.207.205.137 | 3129 | Mumbai |
| 13 | 3.108.243.110 | 15.207.205.137 | 3130 | Mumbai |
| 14 | 13.207.20.220 | 15.207.205.137 | 3131 | Mumbai |
| 15 | 13.234.74.28 | 15.207.205.137 | 3132 | Mumbai (pending) |

---

## Verify All Proxies

### Stockholm
```bash
echo "=== Stockholm ==="
echo -n "Direct → "; curl -s https://api.ipify.org; echo ""
for port in 8889 8890 8891 8892 8893 8894 8895 8896 8897; do
  echo -n "Port $port → "; curl -s --proxy http://127.0.0.1:$port https://api.ipify.org; echo ""
done
```

### Mumbai (from Stockholm)
```bash
echo "=== Mumbai ==="
for port in 3128 3129 3130 3131; do
  echo -n "Port $port → "
  ssh -i ~/proxy-mumbai.pem -o StrictHostKeyChecking=no ubuntu@15.207.205.137 \
    "curl -s --proxy http://localhost:$port https://api.ipify.org"
  echo ""
done
```

---

## Troubleshooting

### "PrivateIpAddressLimitExceeded"
- Check instance type limits (t3.small = 4/ENI)
- Upgrade instance type or add new ENI

### "AddressLimitExceeded" (EIP)
- Default is 5 per region
- Request increase: AWS Console → Service Quotas → EC2 → "EC2-VPC Elastic IPs"

### Proxy returns wrong IP or empty
- Verify private IP is added to OS: `ip addr show`
- Verify Squid config has correct mapping
- Restart Squid: `sudo systemctl restart squid`

### IPs lost after reboot
- Add to `/etc/rc.d/rc.local` (Amazon Linux) or netplan (Ubuntu)
- Enable rc-local: `sudo systemctl enable rc-local`

### Squid not starting
- Check config syntax: `sudo squid -k parse`
- Check logs: `sudo journalctl -u squid -n 20`
