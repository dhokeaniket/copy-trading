package com.copytrading.engine;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads Dhan's instrument master CSV on startup and builds a
 * tradingSymbol → securityId mapping. Refreshes daily at 8 AM IST.
 *
 * CSV source: https://images.dhan.co/api-data/api-scrip-master.csv
 * Columns: SEM_EXM_EXCH_ID, SEM_SEGMENT, SEM_SMST_SECURITY_ID, SEM_INSTRUMENT_NAME,
 *          SEM_TRADING_SYMBOL, SEM_LOT_UNITS, ...
 */
@Component
public class DhanSecurityMapper {

    private static final Logger log = LoggerFactory.getLogger(DhanSecurityMapper.class);
    private static final String CSV_URL = "https://images.dhan.co/api-data/api-scrip-master.csv";

    private final ConcurrentHashMap<String, String> nseEqMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> nseFnoMap = new ConcurrentHashMap<>();
    private final WebClient client;

    public DhanSecurityMapper(WebClient.Builder builder) {
        this.client = builder.build();
    }

    @PostConstruct
    public void init() {
        loadInstruments();
    }

    // Refresh daily at 8:00 AM IST (2:30 AM UTC)
    @Scheduled(cron = "0 30 2 * * *")
    public void scheduledRefresh() {
        loadInstruments();
    }

    private void loadInstruments() {
        try {
            client.get().uri(CSV_URL)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(csv -> {
                        int count = 0;
                        String[] lines = csv.split("\n");
                        for (int i = 1; i < lines.length; i++) {
                            try {
                                String[] cols = lines[i].split(",", -1);
                                if (cols.length < 5) continue;
                                String exchange = cols[0].trim();    // SEM_EXM_EXCH_ID
                                String segment = cols[1].trim();     // SEM_SEGMENT
                                String secId = cols[2].trim();       // SEM_SMST_SECURITY_ID
                                String symbol = cols[4].trim();      // SEM_TRADING_SYMBOL

                                if (secId.isEmpty() || symbol.isEmpty()) continue;

                                if ("NSE".equalsIgnoreCase(exchange) && "E".equalsIgnoreCase(segment)) {
                                    // Equity: store both "RELIANCE" and "RELIANCE-EQ"
                                    String clean = symbol.replace("-EQ", "").toUpperCase();
                                    nseEqMap.put(clean, secId);
                                    nseEqMap.put(symbol.toUpperCase(), secId);
                                    count++;
                                } else if ("NSE".equalsIgnoreCase(exchange) && "D".equalsIgnoreCase(segment)) {
                                    nseFnoMap.put(symbol.toUpperCase(), secId);
                                    count++;
                                }
                            } catch (Exception e) { /* skip bad line */ }
                        }
                        log.info("DHAN_INSTRUMENTS_LOADED: {} symbols mapped", count);
                    }, err -> log.warn("DHAN_INSTRUMENTS_LOAD_FAILED: {}", err.getMessage()));
        } catch (Exception e) {
            log.warn("DHAN_INSTRUMENTS_INIT_FAILED: {}", e.getMessage());
        }
    }

    /**
     * Get Dhan securityId for a given trading symbol.
     * Returns null if not found.
     */
    public String getSecurityId(String symbol, String exchangeSegment) {
        if (symbol == null) return null;
        String clean = symbol.toUpperCase().trim();
        if ("NSE_FNO".equals(exchangeSegment)) {
            return nseFnoMap.get(clean);
        }
        // Try exact match first, then without -EQ
        String id = nseEqMap.get(clean);
        if (id == null) id = nseEqMap.get(clean.replace("-EQ", ""));
        return id;
    }

    public int size() {
        return nseEqMap.size() + nseFnoMap.size();
    }
}
