#!/bin/bash
# ============================================
# Copy Trading End-to-End Test Script
# Run this in your terminal step by step
# ============================================

BASE="https://copy-trading-production-3981.up.railway.app"

echo "========================================="
echo "  STEP 1: Login as Master"
echo "========================================="
echo "Enter master email:"
read MASTER_EMAIL
echo "Enter master password:"
read -s MASTER_PASS

MASTER_TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$MASTER_EMAIL\",\"password\":\"$MASTER_PASS\"}" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("accessToken","LOGIN_FAILED"))')

echo "Master token: ${MASTER_TOKEN:0:20}..."

echo ""
echo "========================================="
echo "  STEP 2: List master's broker accounts"
echo "========================================="
curl -s "$BASE/api/v1/brokers/accounts" -H "Authorization: Bearer $MASTER_TOKEN" | python3 -m json.tool

echo ""
echo "Enter the broker accountId to use as active (copy from above):"
read MASTER_BROKER_ID

echo ""
echo "========================================="
echo "  STEP 3: Check broker session"
echo "========================================="
curl -s "$BASE/api/v1/brokers/accounts/$MASTER_BROKER_ID/signal" -H "Authorization: Bearer $MASTER_TOKEN" | python3 -m json.tool

echo ""
echo "If signal is 0 (disconnected), you need to login to broker first."
echo "Get OAuth URL? (y/n):"
read GET_OAUTH
if [ "$GET_OAUTH" = "y" ]; then
  echo "OAuth URL:"
  curl -s "$BASE/api/v1/brokers/accounts/$MASTER_BROKER_ID/oauth-url" -H "Authorization: Bearer $MASTER_TOKEN" | python3 -m json.tool
  echo ""
  echo "Open the oauthUrl in browser, login, then paste the token from callback:"
  read BROKER_TOKEN
  echo "Logging in to broker..."
  curl -s -X POST "$BASE/api/v1/brokers/accounts/$MASTER_BROKER_ID/login" \
    -H "Authorization: Bearer $MASTER_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"requestToken\":\"$BROKER_TOKEN\",\"authCode\":\"$BROKER_TOKEN\"}" | python3 -m json.tool
fi

echo ""
echo "========================================="
echo "  STEP 4: Set active account + enable polling"
echo "========================================="
curl -s -X POST "$BASE/api/v1/master/active-account" \
  -H "Authorization: Bearer $MASTER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"brokerAccountId\":\"$MASTER_BROKER_ID\"}" | python3 -m json.tool

curl -s -X POST "$BASE/api/v1/engine/polling" \
  -H "Authorization: Bearer $MASTER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true}' | python3 -m json.tool

echo ""
echo "========================================="
echo "  STEP 5: Check children"
echo "========================================="
curl -s "$BASE/api/v1/master/children" -H "Authorization: Bearer $MASTER_TOKEN" | python3 -m json.tool

echo ""
echo "========================================="
echo "  STEP 6: Test manual copy trade"
echo "========================================="
echo "Testing copy trade with RELIANCE BUY 1..."
curl -s -X POST "$BASE/api/v1/engine/copy-trade" \
  -H "Authorization: Bearer $MASTER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"symbol":"RELIANCE","qty":1,"side":"BUY","product":"MIS","orderType":"MARKET","price":0}' | python3 -m json.tool

echo ""
echo "========================================="
echo "  STEP 7: Now trade on your broker app!"
echo "========================================="
echo "Place a trade on Zerodha/Groww/Fyers."
echo "The poller checks every 3 seconds."
echo "Press Enter after placing a trade to check results..."
read

echo "=== Notifications ==="
curl -s "$BASE/api/v1/notifications" -H "Authorization: Bearer $MASTER_TOKEN" | python3 -m json.tool

echo "=== Copy Logs ==="
curl -s "$BASE/api/v1/copy/logs" -H "Authorization: Bearer $MASTER_TOKEN" | python3 -m json.tool

echo ""
echo "Done! Check if the trade was detected and copied."
