package com.copytrading.master;

import com.copytrading.positions.PositionDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalizes margin / P&L fields for master follower table & analytics. */
final class MasterChildMetricsHelper {

    private MasterChildMetricsHelper() {}

    static void applyZeroMargin(Map<String, Object> m, String reason) {
        m.put("marginAvailable", 0);
        m.put("marginUsed", 0);
        m.put("margin", 0);
        m.put("marginUsedPercent", 0);
        m.put("fundsUtilizationStatus", "RED");
        m.put("pnlToday", 0);
        m.put("pnl", 0);
        m.put("openPositionsCount", 0);
        m.put("pos", 0);
        m.put("sessionActive", false);
        m.put("lowMargin", true);
        if (reason != null) m.put("marginError", reason);
    }

    static void applyMarginAndPnl(Map<String, Object> m, Map<String, Object> margin,
                                  List<PositionDto> positions, boolean sessionActive) {
        double available = toDouble(margin.get("availableMargin"));
        double used = toDouble(margin.get("usedMargin"));
        double total = toDouble(margin.get("totalFunds"));
        if (total <= 0) total = available + used;

        double pnlToday = positions.stream().mapToDouble(PositionDto::getPnl).sum();
        int posCount = positions.size();

        m.put("marginAvailable", available);
        m.put("marginUsed", used);
        m.put("margin", available);
        m.put("totalMargin", total);
        m.put("marginUsedPercent", total > 0 ? round1(used / total * 100) : 0);
        m.put("fundsUtilizationStatus", utilizationStatus(used, total));
        m.put("pnlToday", round2(pnlToday));
        m.put("pnl", round2(pnlToday));
        m.put("openPositionsCount", posCount);
        m.put("pos", posCount);
        m.put("sessionActive", sessionActive);
        m.put("lowMargin", available < 5000);
        if (margin.containsKey("error")) m.put("marginError", margin.get("error"));
        if (margin.containsKey("errorCode")) m.put("marginErrorCode", margin.get("errorCode"));
    }

    static Map<String, Object> marginFromProfile(Map<String, Object> profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("availableMargin", profile.getOrDefault("marginAvailable", 0));
        m.put("usedMargin", profile.getOrDefault("marginUsed", 0));
        m.put("totalFunds", profile.getOrDefault("totalMargin", 0));
        return m;
    }

    private static String utilizationStatus(double used, double total) {
        if (total <= 0) return "GREEN";
        double pct = used / total * 100;
        if (pct > 80) return "RED";
        if (pct > 60) return "YELLOW";
        return "GREEN";
    }

    static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (Exception e) {
            return 0;
        }
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
