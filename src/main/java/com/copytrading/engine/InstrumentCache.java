package com.copytrading.engine;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified instrument cache for brokers that need symbol-to-token mapping.
 *
 * Downloads instrument master files on startup and refreshes daily at 8 AM IST.
 *
 * Dhan:     symbol → securityId (numeric)     CSV: https://images.dhan.co/api-data/api-scrip-master.csv
 * Upstox:   symbol → instrument_key (NSE_EQ|ISIN)  JSON: https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz
 * AngelOne: symbol → symboltoken (numeric)    JSON: https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json
 */
@Component
public class InstrumentCache {

    private static final Logger log = LoggerFactory.getLogger(InstrumentCache.class);
    private final WebClient client;

    // Dhan: "RELIANCE" → "2885"
    private final ConcurrentHashMap<String, String> dhanEq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dhanFno = new ConcurrentHashMap<>();

    // Upstox: "RELIANCE" → "NSE_EQ|INE002A01018"
    private final ConcurrentHashMap<String, String> upstoxEq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> upstoxFno = new ConcurrentHashMap<>();

    // AngelOne: "RELIANCE-EQ" → "2885" (symboltoken)
    private final ConcurrentHashMap<String, String> angelEq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> angelFno = new ConcurrentHashMap<>();

    public InstrumentCache(WebClient.Builder builder) {
        this.client = builder.codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)).build();
    }

    @PostConstruct
    public void init() {
        loadAll();
    }

    @Scheduled(cron = "0 30 2 * * *") // 8:00 AM IST = 2:30 AM UTC
    public void refresh() {
        loadAll();
    }

    private void loadAll() {
        loadDhan();
        loadUpstox();
        loadAngelOne();
    }

    // ── DHAN ──
    private void loadDhan() {
        try {
            client.get().uri("https://images.dhan.co/api-data/api-scrip-master.csv")
                    .retrieve().bodyToMono(String.class)
                    .subscribe(csv -> {
                        int count = 0;
                        String[] lines = csv.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            String line = lines[i];
                            if (i == 0) continue; // skip header
                            try {
                                String[] c = line.split(",", -1);
                                if (c.length < 5) continue;
                                String exch = c[0].trim(), seg = c[1].trim(), secId = c[2].trim();
                                String tradingSym = c.length > 5 ? c[5].trim() : "";
                                if (secId.isEmpty() || tradingSym.isEmpty()) continue;
                                // Load all exchanges (NSE + BSE) equity and F&O
                                if ("E".equalsIgnoreCase(seg)) {
                                    String clean = tradingSym.toUpperCase().replace("-EQ", "");
                                    dhanEq.put(clean, secId);
                                    dhanEq.put(tradingSym.toUpperCase(), secId);
                                    count++;
                                } else if ("D".equalsIgnoreCase(seg)) {
                                    dhanFno.put(tradingSym.toUpperCase(), secId);
                                    count++;
                                }
                            } catch (Exception e) { /* skip */ }
                        }
                        log.info("DHAN_INSTRUMENTS: {} symbols loaded (NSE+BSE equity+FNO)", count);
                    }, err -> log.warn("DHAN_INSTRUMENTS_FAILED: {}", err.getMessage()));
        } catch (Exception e) { log.warn("DHAN_INIT_FAILED: {}", e.getMessage()); }
    }

    // ── UPSTOX ──
    private void loadUpstox() {
        // Load NSE equity
        loadUpstoxExchange("https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz", "NSE");
        // Load BSE F&O (smaller file, for SENSEX/BANKEX options)
        loadUpstoxExchange("https://assets.upstox.com/market-quote/instruments/exchange/BSE.json.gz", "BSE");
    }

    private void loadUpstoxExchange(String url, String exchange) {
        try {
            client.get().uri(url)
                    .header("Accept-Encoding", "gzip")
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .map(bytes -> {
                        try {
                            var bais = new java.io.ByteArrayInputStream(bytes);
                            var gis = new java.util.zip.GZIPInputStream(bais);
                            return new String(gis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                        }
                    })
                    .subscribe(json -> {
                        int count = 0;
                        String[] items = json.split("\\{");
                        for (String item : items) {
                            try {
                                String sym = extractJsonField(item, "trading_symbol");
                                String key = extractJsonField(item, "instrument_key");
                                if (sym == null || key == null) continue;
                                String clean = sym.toUpperCase().replace("-EQ", "").trim();
                                if (key.contains("_EQ")) {
                                    upstoxEq.put(clean, key);
                                    upstoxEq.put(sym.toUpperCase(), key);
                                    count++;
                                } else if (key.contains("_FO")) {
                                    upstoxFno.put(sym.toUpperCase(), key);
                                    count++;
                                }
                            } catch (Exception e) { /* skip */ }
                        }
                        log.info("UPSTOX_INSTRUMENTS_{}: {} symbols loaded", exchange, count);
                    }, err -> log.warn("UPSTOX_INSTRUMENTS_{}_FAILED: {}", exchange, err.getMessage()));
        } catch (Exception e) { log.warn("UPSTOX_{}_INIT_FAILED: {}", exchange, e.getMessage()); }
    }

    // ── ANGEL ONE ──
    private void loadAngelOne() {
        try {
            client.get().uri("https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json")
                    .retrieve().bodyToMono(String.class)
                    .subscribe(json -> {
                        int count = 0;
                        // Each entry: {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE","exch_seg":"NSE",...}
                        String[] items = json.split("\\{");
                        for (String item : items) {
                            try {
                                String token = extractJsonField(item, "token");
                                String sym = extractJsonField(item, "symbol");
                                String exchSeg = extractJsonField(item, "exch_seg");
                                if (token == null || sym == null || exchSeg == null) continue;
                                if ("NSE".equalsIgnoreCase(exchSeg)) {
                                    angelEq.put(sym.toUpperCase(), token);
                                    // Also map without -EQ suffix
                                    angelEq.put(sym.toUpperCase().replace("-EQ", ""), token);
                                    count++;
                                } else if ("NFO".equalsIgnoreCase(exchSeg)) {
                                    angelFno.put(sym.toUpperCase(), token);
                                    count++;
                                }
                            } catch (Exception e) { /* skip */ }
                        }
                        log.info("ANGELONE_INSTRUMENTS: {} symbols loaded", count);
                    }, err -> log.warn("ANGELONE_INSTRUMENTS_FAILED: {}", err.getMessage()));
        } catch (Exception e) { log.warn("ANGELONE_INIT_FAILED: {}", e.getMessage()); }
    }

    // ── Lookup methods ──

    /** Dhan: get numeric securityId for a symbol */
    public String getDhanSecurityId(String symbol, boolean isFnO) {
        if (symbol == null) return null;
        String clean = symbol.toUpperCase().replace("-EQ", "").trim();
        return isFnO ? dhanFno.get(symbol.toUpperCase()) : dhanEq.get(clean);
    }

    /** Upstox: get instrument_key like "NSE_EQ|INE002A01018" */
    public String getUpstoxInstrumentKey(String symbol, boolean isFnO) {
        if (symbol == null) return null;
        // If already in correct format, return as-is
        if (symbol.contains("|")) return symbol;
        String clean = symbol.toUpperCase().replace("-EQ", "").trim();
        return isFnO ? upstoxFno.get(symbol.toUpperCase()) : upstoxEq.get(clean);
    }

    /** Angel One: get numeric symboltoken */
    public String getAngelToken(String symbol, boolean isFnO) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase().trim();
        return isFnO ? angelFno.get(upper) : angelEq.get(upper);
    }

    public int totalSize() {
        return dhanEq.size() + dhanFno.size() + upstoxEq.size() + upstoxFno.size() + angelEq.size() + angelFno.size();
    }

    // Simple JSON field extractor (avoids Jackson dependency for parsing huge files)
    private static String extractJsonField(String fragment, String field) {
        String search = "\"" + field + "\":\"";
        int idx = fragment.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = fragment.indexOf("\"", start);
        if (end < 0) return null;
        return fragment.substring(start, end);
    }
}
