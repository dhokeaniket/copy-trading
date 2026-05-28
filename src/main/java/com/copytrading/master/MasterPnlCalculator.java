package com.copytrading.master;

import com.copytrading.logs.CopyLog;
import com.copytrading.trade.Trade;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Realised P&L from executed master trades (FIFO per instrument) and monthly breakdown for dashboard.
 */
final class MasterPnlCalculator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private MasterPnlCalculator() {}

    record PnlSnapshot(
            double totalRealised,
            double totalUnrealised,
            double todayRealised,
            double todayUnrealised,
            double portfolioValue,
            List<Map<String, Object>> monthlyPnl,
            List<Map<String, Object>> dailyPnl
    ) {}

    static PnlSnapshot build(List<Trade> trades, List<CopyLog> copyLogs,
                               double unrealizedFromPositions, Map<String, Object> margin) {
        List<Trade> executed = filterExecuted(trades);
        RealisedBreakdown realised = computeRealised(executed);
        double portfolio = portfolioValue(margin);
        List<Map<String, Object>> monthly = buildMonthlyPnl(executed, copyLogs, realised.byMonth(), 6);
        List<Map<String, Object>> daily = buildDailyPnl(executed, copyLogs, realised.byDay(), 30);
        LocalDate today = LocalDate.now(IST);
        double todayRealised = realised.byDay().getOrDefault(today, 0.0);
        return new PnlSnapshot(
                MasterChildMetricsHelper.round2(realised.total()),
                MasterChildMetricsHelper.round2(unrealizedFromPositions),
                MasterChildMetricsHelper.round2(todayRealised),
                MasterChildMetricsHelper.round2(unrealizedFromPositions),
                MasterChildMetricsHelper.round2(portfolio),
                monthly,
                daily
        );
    }

    private static List<Trade> filterExecuted(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) return List.of();
        return trades.stream()
                .filter(t -> t != null && isExecuted(t.getStatus()))
                .sorted(Comparator.comparing(MasterPnlCalculator::tradeTime))
                .toList();
    }

    private static boolean isExecuted(String status) {
        if (status == null) return false;
        String s = status.toUpperCase(Locale.ROOT);
        return "EXECUTED".equals(s) || "COMPLETE".equals(s) || "COMPLETED".equals(s) || "FILLED".equals(s);
    }

    private static Instant tradeTime(Trade t) {
        if (t.getExecutedAt() != null) return t.getExecutedAt();
        return t.getPlacedAt() != null ? t.getPlacedAt() : Instant.EPOCH;
    }

    private static double portfolioValue(Map<String, Object> margin) {
        if (margin == null || margin.isEmpty()) return 0;
        double total = MasterChildMetricsHelper.toDouble(margin.get("totalFunds"));
        if (total > 0) return total;
        return MasterChildMetricsHelper.toDouble(margin.get("availableMargin"))
                + MasterChildMetricsHelper.toDouble(margin.get("usedMargin"));
    }

    private record RealisedBreakdown(double total, Map<YearMonth, Double> byMonth, Map<LocalDate, Double> byDay) {}

    private static RealisedBreakdown computeRealised(List<Trade> executed) {
        Map<String, Deque<Lot>> books = new HashMap<>();
        double total = 0;
        Map<YearMonth, Double> byMonth = new LinkedHashMap<>();
        Map<LocalDate, Double> byDay = new LinkedHashMap<>();

        for (Trade t : executed) {
            String instrument = t.getInstrument() != null ? t.getInstrument() : "UNKNOWN";
            int qty = Math.max(0, t.getQuantity());
            if (qty == 0) continue;
            String side = t.getTransactionType() != null ? t.getTransactionType().toUpperCase(Locale.ROOT) : "BUY";
            double price = t.getPrice();
            Instant when = tradeTime(t);
            LocalDate day = when.atZone(IST).toLocalDate();
            YearMonth month = YearMonth.from(day);

            if ("BUY".equals(side)) {
                books.computeIfAbsent(instrument, k -> new ArrayDeque<>()).addLast(new Lot(qty, price));
            } else if ("SELL".equals(side)) {
                total += closeLots(books, instrument, qty, price, byMonth, byDay, month, day);
            }
        }
        return new RealisedBreakdown(total, byMonth, byDay);
    }

    private record Lot(int qty, double price) {}

    private static double closeLots(Map<String, Deque<Lot>> books, String instrument, int sellQty, double sellPrice,
                                    Map<YearMonth, Double> byMonth, Map<LocalDate, Double> byDay,
                                    YearMonth month, LocalDate day) {
        Deque<Lot> queue = books.get(instrument);
        if (queue == null || queue.isEmpty()) return 0;
        int remaining = sellQty;
        double realised = 0;
        while (remaining > 0 && !queue.isEmpty()) {
            Lot lot = queue.peekFirst();
            int matched = Math.min(remaining, lot.qty);
            realised += (sellPrice - lot.price) * matched;
            remaining -= matched;
            if (matched >= lot.qty) {
                queue.pollFirst();
            } else {
                queue.pollFirst();
                queue.addFirst(new Lot(lot.qty - matched, lot.price));
            }
        }
        if (realised != 0) {
            byMonth.merge(month, realised, Double::sum);
            byDay.merge(day, realised, Double::sum);
        }
        return realised;
    }

    static List<Map<String, Object>> buildMonthlyPnl(List<Trade> executed, List<CopyLog> copyLogs,
                                                     Map<YearMonth, Double> realisedByMonth, int months) {
        YearMonth end = YearMonth.now(IST);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            YearMonth ym = end.minusMonths(i);
            Instant start = ym.atDay(1).atStartOfDay(IST).toInstant();
            Instant endExclusive = ym.plusMonths(1).atDay(1).atStartOfDay(IST).toInstant();

            long win = 0;
            long loss = 0;
            if (copyLogs != null) {
                win = copyLogs.stream()
                        .filter(l -> inRange(l.getCreatedAt(), start, endExclusive))
                        .filter(l -> "SUCCESS".equals(l.getChildStatus()))
                        .count();
                loss = copyLogs.stream()
                        .filter(l -> inRange(l.getCreatedAt(), start, endExclusive))
                        .filter(l -> "FAILED".equals(l.getChildStatus()))
                        .count();
            }
            long trades = win + loss;
            long winRate = trades > 0 ? Math.round(win * 100.0 / trades) : 0;
            double netPnL = MasterChildMetricsHelper.round2(realisedByMonth.getOrDefault(ym, 0.0));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", ym.toString());
            row.put("period", ym.toString());
            row.put("trades", trades);
            row.put("win", win);
            row.put("loss", loss);
            row.put("netPnL", netPnL);
            row.put("realizedPnl", netPnL);
            row.put("winRate", winRate);
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> buildDailyPnl(List<Trade> executed, List<CopyLog> copyLogs,
                                                           Map<LocalDate, Double> realisedByDay, int days) {
        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> chart = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Instant start = d.atStartOfDay(IST).toInstant();
            Instant end = d.plusDays(1).atStartOfDay(IST).toInstant();
            long success = 0;
            long failed = 0;
            if (copyLogs != null) {
                success = copyLogs.stream()
                        .filter(l -> inRange(l.getCreatedAt(), start, end))
                        .filter(l -> "SUCCESS".equals(l.getChildStatus()))
                        .count();
                failed = copyLogs.stream()
                        .filter(l -> inRange(l.getCreatedAt(), start, end))
                        .filter(l -> "FAILED".equals(l.getChildStatus()))
                        .count();
            }
            double pnl = MasterChildMetricsHelper.round2(realisedByDay.getOrDefault(d, 0.0));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("copiesSuccess", success);
            row.put("copiesFailed", failed);
            row.put("value", success);
            row.put("pnl", pnl);
            row.put("realizedPnl", pnl);
            chart.add(row);
        }
        return chart;
    }

    private static boolean inRange(Instant ts, Instant start, Instant endExclusive) {
        return ts != null && !ts.isBefore(start) && ts.isBefore(endExclusive);
    }

    static void applySummaryAliases(Map<String, Object> summary, PnlSnapshot snap) {
        double combined = snap.totalRealised() + snap.totalUnrealised();
        summary.put("totalRealisedPnl", MasterChildMetricsHelper.round2(snap.totalRealised()));
        summary.put("totalRealizedPnl", MasterChildMetricsHelper.round2(snap.totalRealised()));
        summary.put("totalUnrealisedPnl", MasterChildMetricsHelper.round2(snap.totalUnrealised()));
        summary.put("totalUnrealizedPnl", MasterChildMetricsHelper.round2(snap.totalUnrealised()));
        summary.put("combinedUnrealizedPnl", MasterChildMetricsHelper.round2(snap.totalUnrealised()));
        summary.put("combinedRealizedPnl", MasterChildMetricsHelper.round2(snap.totalRealised()));
        summary.put("combinedPnl", MasterChildMetricsHelper.round2(combined));
        summary.put("todayPnl", MasterChildMetricsHelper.round2(snap.todayUnrealised()));
        summary.put("todayRealisedPnl", MasterChildMetricsHelper.round2(snap.todayRealised()));
        summary.put("todayRealizedPnl", MasterChildMetricsHelper.round2(snap.todayRealised()));
    }
}
