# Frontend Fixes Specification (A3–A9) + Session Re-Architecture

## Backend Fix Applied This Session

**404 `/api/v1/broker/accounts/{id}/dashboard`** — Frontend uses singular `/broker/`, backend had only `/brokers/`. Added singular path aliases to `BrokerController.java` for all endpoints. Deploy the backend to fix.

---

## A3. Fix Broken Chart Axes (₹0K ₹0K ₹0K…)

**Files**: `charts/LineChart.jsx`, `charts/BarChart.jsx`

**Problem**: When P&L data is all zeros or the series is flat, the Y-axis shows "₹0K ₹0K ₹0K" repeatedly because the chart library doesn't know how to space identical values.

**Fix**:

```jsx
// utils/formatters.js
export const formatCompactINR = (value) => {
  if (value === 0) return '₹0';
  if (Math.abs(value) >= 10000000) return `₹${(value / 10000000).toFixed(1)}Cr`;
  if (Math.abs(value) >= 100000) return `₹${(value / 100000).toFixed(1)}L`;
  if (Math.abs(value) >= 1000) return `₹${(value / 1000).toFixed(1)}K`;
  return `₹${value.toFixed(0)}`;
};
```

```jsx
// charts/LineChart.jsx
import { formatCompactINR } from '../utils/formatters';

// Before rendering chart, check if data is flat:
const allZero = data.every(d => d.value === 0);
const isFlat = data.length > 0 && new Set(data.map(d => d.value)).size === 1;

if (allZero || isFlat) {
  return (
    <div className="chart-empty-overlay">
      <div className="baseline-line" />
      <span className="text-muted">No P&L yet</span>
    </div>
  );
}

// In <YAxis>:
<YAxis tickFormatter={formatCompactINR} />
```

Same approach for `BarChart.jsx`.

---

## A4. Don't Fabricate Metrics — Show "—" for Null Data

**Files**: `master/Overview.jsx:99`, `child/FindMasters.jsx:67,277,330`

**Problem**: Showing `0%` or `+28%/28%/+28%` when there's no real data. Testing with fake numbers misleads.

**Fix**:

```jsx
// Replace all instances of (winRate || 0) or hardcoded %:

// master/Overview.jsx:99
{analytics.winRate == null ? '—' : formatPercent(analytics.winRate)}

// child/FindMasters.jsx — all 3 occurrences:
{master.winRate == null ? '—' : formatPercent(master.winRate)}
{master.avgReturn == null ? '—' : formatPercent(master.avgReturn)}
{master.totalReturn == null ? '—' : formatPercent(master.totalReturn)}

// utils/formatters.js — add:
export const formatPercent = (value) => {
  if (value == null) return '—';
  const sign = value >= 0 ? '+' : '';
  return `${sign}${value.toFixed(1)}%`;
};
```

---

## A5. Fix Trade Timeline Default Filter

**Files**: `TradeTimeline.jsx` (or wherever the filter lives)

**Problem**: Defaults to "Today" which shows "Showing 0 of 41". Should default to 7D with context-aware empty state.

**Fix**:

```jsx
// Change default filter from 'today' to '7d'
const [timeFilter, setTimeFilter] = useState('7d');

// Context-aware empty state:
const EmptyTradeState = ({ filter, totalTrades }) => {
  if (filter === 'today' && totalTrades > 0) {
    return (
      <div className="empty-state">
        <p>No trades today — {totalTrades} in last 30D</p>
        <button onClick={() => setTimeFilter('30d')}>Show all</button>
      </div>
    );
  }
  return (
    <div className="empty-state">
      <p>No trades in this period</p>
    </div>
  );
};
```

---

## A6. Surface Skip/Fail Reasons — Bigger, Concise Error Messages

**Files**: `child/CopiedTrades.jsx`, `master/OptionsStatus.jsx`

**Problem**: Error column shows tiny, verbose messages. Need short, readable text.

**Fix**:

```jsx
// utils/errorMessages.js — map verbose reasons to concise ones:
const ERROR_MAP = {
  'Broker session inactive. Child needs to re-login.': 'Session expired',
  'No broker account linked': 'No broker linked',
  'Risk limit: max_trades_per_day exceeded': 'Daily trade limit',
  'Risk limit: copy_paused': 'Copy paused',
  'Scaled quantity is 0': 'Qty too small',
  'SUB_LOT_SIZE': 'Below lot size',
  'NO_POSITION': 'No position to sell',
  'INSUFFICIENT_POSITION': 'Insufficient qty',
  'MARKET_CLOSED': 'Market closed',
  'Upstox order 403': 'IP blocked',
  'GA005': 'Groww IP rejected',
};

export const conciseError = (msg) => {
  if (!msg) return '—';
  for (const [key, short] of Object.entries(ERROR_MAP)) {
    if (msg.includes(key)) return short;
  }
  // Fallback: truncate to 30 chars
  return msg.length > 30 ? msg.substring(0, 30) + '…' : msg;
};
```

```jsx
// In table column:
<td className="text-sm font-medium text-red-400">
  {conciseError(trade.errorMessage || trade.skipReason)}
</td>
```

CSS: increase font size from `text-xs` to `text-sm`, use `font-medium`.

---

## A7. Replace Silent `catch {}` with Error States

**Files**: `master/Overview.jsx:51` + 6 others

**Problem**: Empty `catch {}` blocks swallow errors — user sees blank content identical to "no data".

**Fix pattern**:

```jsx
// Before (broken):
try {
  const data = await fetchOverview();
  setAnalytics(data);
} catch {} // silent!

// After (correct):
try {
  const data = await fetchOverview();
  setAnalytics(data);
} catch (err) {
  setError(err.message || 'Failed to load');
  addToast('Failed to load overview', 'error');
}

// In JSX:
{error ? (
  <ErrorState 
    message={error} 
    onRetry={() => { setError(null); fetchData(); }} 
  />
) : (
  // normal content
)}
```

Create a shared `<ErrorState>` component:

```jsx
// components/ErrorState.jsx
export const ErrorState = ({ message, onRetry }) => (
  <div className="flex flex-col items-center gap-3 p-6 text-center">
    <span className="text-red-400 text-sm">{message}</span>
    {onRetry && (
      <button onClick={onRetry} className="btn btn-sm btn-outline">
        Retry
      </button>
    )}
  </div>
);
```

---

## A8. Fix `useRiskPnl` Shared Loading/Error

**File**: `hooks/useRiskPnl.js:6-7`

**Problem**: One shared `loading` flag across 7 fetchers — one finishing sets `loading=false` while others are still in-flight. Risk Settings page gets stuck.

**Fix**:

```jsx
// hooks/useRiskPnl.js — track per-operation state
const [state, setState] = useState({});

const fetchWithKey = async (key, fetcher) => {
  setState(s => ({ ...s, [key]: { loading: true, error: null } }));
  try {
    const data = await fetcher();
    setState(s => ({ ...s, [key]: { loading: false, data, error: null } }));
    return data;
  } catch (err) {
    setState(s => ({ ...s, [key]: { loading: false, error: err.message } }));
    throw err;
  }
};

// Usage:
const isLoading = (key) => state[key]?.loading ?? false;
const getError = (key) => state[key]?.error ?? null;
const getData = (key) => state[key]?.data ?? null;

// Export per-key helpers:
return { fetchWithKey, isLoading, getError, getData };
```

---

## A9. Stop Rendering Placeholder 80

**File**: `child/RiskSettings.jsx:11`

**Problem**: Initial value is hardcoded `80` instead of `null`, so the form shows `80` before data loads.

**Fix**:

```jsx
// Before:
const [maxExposure, setMaxExposure] = useState(80);

// After:
const [maxExposure, setMaxExposure] = useState(null);

// Gate the form:
if (isLoading('riskRules') || maxExposure === null) {
  return <SkeletonLoader rows={5} />;
}
```

---

## Broker Session Re-Architecture

### Reality: Daily Re-Login Is Required

All Indian brokers mandate daily 2FA per SEBI rules. No refresh tokens work across days. This is **not a bug** — it's regulatory.

| Broker | Token Lifetime | Re-auth Method |
|--------|---------------|----------------|
| Groww | ~24h | Access token from Groww dashboard |
| Zerodha | 6AM–6AM | request_token via OAuth redirect |
| Upstox | ~24h | auth_code via OAuth redirect |
| Fyers | ~24h | auth_code via OAuth redirect |
| Dhan | ~24h | tokenId via OAuth redirect |
| Angel One | ~24h | TOTP (authenticator app) |

### Proposed Architecture: Morning Reconnect Flow

**Backend changes needed:**

1. **`BrokerSessionExpiryMonitor`** — scheduled job at 8:30 AM IST:
   - Mark all accounts' `session_active = false`
   - Send push notification to each user: "Market opens in 1h — reconnect your broker"
   - Telegram alert: "🔑 Daily re-login needed. Tap to reconnect."

2. **Frontend "Reconnect" flow:**
   - On login/app open, check `/api/v1/brokers/accounts` → if `sessionActive: false`
   - Show prominent "Reconnect Broker" banner
   - Clicking opens the broker-specific OAuth/token flow
   - After reconnect, session_active = true, session_expires = now + 24h

3. **No auto-retry on expired sessions:**
   - If copy engine gets 401/session expired during market hours → mark inactive
   - Push notification: "Broker disconnected — reconnect to resume copy trading"
   - Don't silently retry — user MUST do 2FA

4. **Frontend notification badge:**
   - Red dot on broker icon when session is expired
   - Count of disconnected accounts in header

### What NOT to build:
- ❌ Auto-refresh tokens (won't work across days)
- ❌ Stored credentials for auto-login (security risk, SEBI non-compliant)
- ❌ Background session renewal (impossible without user 2FA interaction)

---

## Deployment Steps

1. Push backend (Upstox position fix + singular broker path aliases):
   ```bash
   git add -A && git commit -m "fix: add /broker/ singular aliases + upstox position mapping"
   git push origin feature/backend_code
   git push upstream feature/backend_code
   ```

2. Deploy on EC2:
   ```bash
   cd /home/ec2-user/copy-trading && git pull origin feature/backend_code
   ./gradlew clean build -x test --no-daemon
   kill $(pgrep -f copy-trading-backend) 2>/dev/null
   nohup java -Xmx512m -jar build/libs/copy-trading-backend-0.1.0.jar >> /home/ec2-user/ascentra.log 2>&1 &
   ```

3. Frontend fixes: apply A3–A9 in the `copy-trading-fe` repo per the patterns above.
