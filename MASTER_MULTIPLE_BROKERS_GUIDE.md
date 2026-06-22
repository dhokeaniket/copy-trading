# Multiple Broker Connections for Master Accounts

## Overview
The Ascentra Capital platform natively supports **Multiple Broker Connections** for a single Master Account. This allows a Master Trader to link and execute trades across different broker platforms (e.g., Zerodha, Upstox, Dhan, AngelOne, Fyers) simultaneously, and all their trades will be seamlessly detected and copied to their subscribed children.

## API Endpoints for Frontend Integration
To build the frontend UI for multiple brokers, the frontend developer should use the following REST API endpoints (all endpoints require standard User JWT `Authorization: Bearer <token>`):

### 1. Fetch All Connected Brokers
- **GET** `/api/v1/broker/accounts`
- **Description:** Returns an array of all broker accounts currently linked to the logged-in user. The frontend should render a card/row for each item in this array.

### 2. Link a New Broker Account
- **POST** `/api/v1/broker/accounts`
- **Payload:** `{ "brokerId": "ZERODHA" | "UPSTOX" | "DHAN" | "GROWW", "nickname": "My Zerodha", "clientId": "...", "apiKey": "...", "apiSecret": "..." }`
- **Description:** Creates a new broker connection. The user can call this multiple times to link different brokers.

### 3. Initiate Broker Login (OAuth/Session)
- **POST** `/api/v1/broker/accounts/{accountId}/login`
- **Description:** Initiates the login flow or returns the OAuth URL required to activate the broker session.

### 4. Check Session Status
- **GET** `/api/v1/broker/accounts/{accountId}/status`
- **Description:** Returns whether the session is currently active (`sessionActive: true/false`). The frontend should poll this or fetch on load to show a green/red dot next to each broker.

### 5. Disconnect/Halt a Specific Broker
- **POST** `/api/v1/broker/accounts/{accountId}/disconnect`
- **Description:** Gracefully terminates the session and invalidates the access token for that specific broker, halting copying from it.

### 6. Delete a Broker Connection
- **DELETE** `/api/v1/broker/accounts/{accountId}`
- **Description:** Permanently removes the broker connection and API keys from the master's profile.

---

## How it Works Technically
1. **Database Architecture (`BrokerAccount` Model):**
   The `broker_accounts` table is designed with a one-to-many relationship mapping `user_id` to multiple broker configurations.
   - The repository uses `Flux<BrokerAccount> findByUserId(UUID userId)`.
   - A Master user can hold 1 or more active rows in this table at the same time.

2. **Engine Polling (`OrderPollingService.java`):**
   During the continuous polling cycle, when the engine targets a Master user, it executes a `flatMap` operation across *all* of their linked brokers:
   ```java
   brokerRepo.findByUserId(masterId)
       .filter(a -> a.getAccessToken() != null)
       .flatMap(account -> brokerService.getOrders(account.getId(), masterId)
   ```
   This means the system queries the orderbooks of **every connected broker in parallel**.

3. **Trade Propagation (`CopyEngineService.java`):**
   When a new trade is detected from *any* of the Master's connected brokers, it is immediately normalized into a `MasterTrade` event and logged into the `master_trades` table.
   The child engines continuously listen for new `MasterTrade` events and will instantly replicate the order on the child's assigned broker, completely agnostic of which broker the Master used to originate the trade.

## Setup Instructions for Master Users
1. Log into the platform as a Master user.
2. Navigate to the **Brokers** or **Accounts** section.
3. Link the first broker (e.g., Zerodha) by following the standard OAuth/API key flow.
4. Without disconnecting the first broker, link a second broker (e.g., Upstox) via its respective configuration panel.
5. As long as both brokers reflect an `Active` session status (`session_active = true`), any trade placed on *either* terminal will trigger the copy-trading engine for all subscribed children.

## Limitations & Best Practices
- **Rate Limiting:** Because the engine queries multiple brokers simultaneously for the same master, ensure the global polling intervals (`ENGINE_POLLING_INTERVAL_MS`) do not hit individual broker API rate limits.
- **Symbol Mapping:** Different brokers occasionally use slightly different nomenclature for identical contracts. The system normalizes these, but Master users should be mindful when trading highly exotic contracts across different broker platforms.
