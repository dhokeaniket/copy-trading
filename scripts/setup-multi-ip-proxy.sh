#!/bin/bash
# ============================================================
# Multi-IP Proxy Setup for Groww per-user IP routing
# Run this on EC2 as root (sudo)
# ============================================================
# Your EC2 has 2 ENIs:
#   eth0 (primary): 172.31.26.85  → EIP 13.53.246.13  (Groww User A)
#   eth1 (secondary): 172.31.22.83 → EIP 13.61.58.89  (Groww User B)
#
# This script:
# 1. Configures policy routing so eth1 can send outbound traffic
# 2. Installs tinyproxy on eth1 (port 8889) so Java can route through it
# ============================================================

set -e

echo "=== Step 1: Configure policy routing for eth1 ==="

# Get the gateway (usually .1 of the subnet)
GATEWAY="172.31.16.1"  # Default VPC gateway for 172.31.x.x subnet
ETH1_IP="172.31.22.83"

# Add routing table for eth1
echo "200 eth1rt" | sudo tee -a /etc/iproute2/rt_tables 2>/dev/null || true

# Route traffic from eth1's IP through eth1
sudo ip rule add from $ETH1_IP table eth1rt priority 100 2>/dev/null || true
sudo ip route add default via $GATEWAY dev eth1 table eth1rt 2>/dev/null || true

echo "Policy routing configured. Traffic from $ETH1_IP will go out via eth1 (EIP 13.61.58.89)"

echo "=== Step 2: Install tinyproxy ==="
sudo yum install -y tinyproxy 2>/dev/null || sudo amazon-linux-extras install -y epel && sudo yum install -y tinyproxy

echo "=== Step 3: Configure tinyproxy on eth1 (port 8889) ==="
sudo tee /etc/tinyproxy/tinyproxy-eth1.conf > /dev/null << 'CONF'
User tinyproxy
Group tinyproxy
Port 8889
Listen 127.0.0.1
Bind 172.31.22.83
Timeout 30
DefaultErrorFile "/usr/share/tinyproxy/default.html"
MaxClients 50
Allow 127.0.0.1
Allow 172.31.0.0/16
ConnectPort 443
ConnectPort 80
CONF

echo "=== Step 4: Start tinyproxy ==="
# Kill any existing tinyproxy on port 8889
sudo fuser -k 8889/tcp 2>/dev/null || true
sleep 1

# Start with custom config
sudo tinyproxy -c /etc/tinyproxy/tinyproxy-eth1.conf

echo "=== Step 5: Verify ==="
echo "Testing proxy on port 8889..."
sleep 2
curl -s --proxy http://127.0.0.1:8889 https://ifconfig.me && echo ""
echo ""
echo "If the IP above shows 13.61.58.89, the proxy is working!"
echo ""
echo "=== Setup Complete ==="
echo "IP 1 (direct):      13.53.246.13 — default outbound, Groww User A"
echo "IP 2 (via proxy):   13.61.58.89  — proxy on 127.0.0.1:8889, Groww User B"
echo ""
echo "To make this persist across reboots, add to /etc/rc.local or create a systemd service."
