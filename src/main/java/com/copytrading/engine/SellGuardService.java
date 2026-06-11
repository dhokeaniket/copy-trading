package com.copytrading.engine;

import com.copytrading.logs.CopyLogRepository;
import com.copytrading.positions.PositionDto;
import com.copytrading.positions.PositionsService;
import com.copytrading.subscription.CopySides;
import com.copytrading.subscription.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * SELL copy guards: subscription copySides, copied-BUY history, and live broker positions.
 */
@Service
public class SellGuardService {

    private static final Logger log = LoggerFactory.getLogger(SellGuardService.class);

    private final CopyLogRepository copyLogs;
    private final PositionsService positionsService;
    private final SymbolMapper symbolMapper;

    public SellGuardService(CopyLogRepository copyLogs, PositionsService positionsService, SymbolMapper symbolMapper) {
        this.copyLogs = copyLogs;
        this.positionsService = positionsService;
        this.symbolMapper = symbolMapper;
    }

    public Mono<String> checkSellAllowed(UUID masterId, Subscription sub, String symbol, int scaledSellQty,
                                         UUID childBrokerAccountId, String childBrokerId, String masterBrokerId) {
        String copySides = CopySides.normalize(sub.getCopySides());
        boolean allowShort = sub.isAllowShortSelling();

        if (CopySides.MIRROR.equals(copySides) && allowShort) {
            return Mono.just("");
        }

        Mono<Boolean> copiedBuyMono = hasCopiedBuySinceSubscription(masterId, sub.getChildId(), symbol, sub.getCreatedAt());

        if (CopySides.BUY_ONLY.equals(copySides)) {
            return copiedBuyMono.flatMap(hasBuy -> {
                if (!hasBuy) {
                    return Mono.just("NO_POSITION: No copied BUY for " + symbol + " since subscription");
                }
                return livePositionSufficient(childBrokerAccountId, symbol, masterBrokerId, childBrokerId, scaledSellQty);
            });
        }

        if (CopySides.BUY_AND_SELL.equals(copySides) || CopySides.MIRROR.equals(copySides)) {
            // For BUY_AND_SELL: if we previously copied a BUY for this symbol, allow the SELL
            // (handles limit orders that may not have filled yet — position shows 0 but order exists)
            return copiedBuyMono.flatMap(hasBuy -> {
                if (hasBuy) {
                    // We know we placed a BUY for this child — allow the exit SELL
                    return Mono.just("");
                }
                // No prior BUY copy — check live position as fallback
                return livePositionSufficient(childBrokerAccountId, symbol, masterBrokerId, childBrokerId, scaledSellQty);
            });
        }

        return Mono.just("");
    }

    private Mono<Boolean> hasCopiedBuySinceSubscription(UUID masterId, UUID childId, String symbol, Instant subscribedAt) {
        return copyLogs.findByMasterIdAndChildId(masterId, childId)
                .filter(cl -> symbol.equals(cl.getSymbol())
                        && "BUY".equalsIgnoreCase(cl.getTradeType())
                        && "SUCCESS".equals(cl.getChildStatus()))
                .filter(cl -> {
                    if (subscribedAt == null) return true;
                    if (cl.getCreatedAt() == null) return false;
                    return !cl.getCreatedAt().isBefore(subscribedAt);
                })
                .hasElements();
    }

    private Mono<String> livePositionSufficient(UUID brokerAccountId, String symbol, String masterBrokerId,
                                                String childBrokerId, int qty) {
        if (brokerAccountId == null) {
            return Mono.just("INSUFFICIENT_POSITION: No broker account linked");
        }
        return positionsService.getPositionsForAccount(brokerAccountId)
                .map(positions -> {
                    int net = netLongQty(positions, symbol, masterBrokerId, childBrokerId);
                    if (net < qty) {
                        log.info("SELL_GUARD_BLOCKED net={} required={} symbol={} childBroker={} masterBroker={} positionsCount={} positionSymbols={}",
                                net, qty, symbol, childBrokerId, masterBrokerId, positions.size(),
                                positions.stream().map(p -> p.getSymbol() + ":" + p.getQty()).limit(10).toList());
                        return "INSUFFICIENT_POSITION: Live qty " + net + " < sell qty " + qty + " for " + symbol;
                    }
                    return "";
                })
                .onErrorResume(e -> {
                    log.warn("SELL_GUARD_POSITION_FETCH_FAIL symbol={} broker={} err={}", symbol, childBrokerId, e.getMessage());
                    // On error fetching positions, allow the sell through (fail-open)
                    return Mono.just("");
                });
    }

    private int netLongQty(java.util.List<PositionDto> positions, String masterSymbol, String masterBrokerId,
                           String childBrokerId) {
        if (positions == null || masterSymbol == null) return 0;
        String tgt = childBrokerId != null ? childBrokerId : "";
        String translated = !tgt.isBlank()
                ? symbolMapper.translate(masterSymbol, masterBrokerId, tgt)
                : masterSymbol;
        String cleanMaster = cleanSymbol(masterSymbol);
        String cleanTranslated = cleanSymbol(translated);
        String underlying = extractUnderlying(masterSymbol);
        int net = 0;
        for (PositionDto p : positions) {
            if (p.getSymbol() == null || p.getQty() <= 0) continue;
            String ps = cleanSymbol(p.getSymbol());
            boolean match = ps.equals(cleanMaster)
                    || ps.equals(cleanTranslated)
                    || masterSymbol.equalsIgnoreCase(p.getSymbol())
                    || translated.equalsIgnoreCase(p.getSymbol());
            // Fallback: if underlying matches and it's the same instrument type (CE/PE/FUT/equity)
            if (!match && underlying != null && !underlying.isEmpty()) {
                String posUnderlying = extractUnderlying(p.getSymbol());
                if (underlying.equals(posUnderlying)) {
                    // Same underlying — now check strike/type match loosely
                    // For F&O: both must contain same CE/PE/FUT suffix
                    String masterType = extractOptionType(masterSymbol);
                    String posType = extractOptionType(p.getSymbol());
                    if (masterType != null && masterType.equals(posType)) {
                        // Same underlying + same option type — likely the same contract
                        match = true;
                    } else if (masterType == null && posType == null) {
                        // Both equity — match
                        match = true;
                    }
                }
            }
            if (match) {
                if ("SELL".equalsIgnoreCase(p.getSide())) {
                    net -= p.getQty();
                } else {
                    net += p.getQty();
                }
            }
        }
        return Math.max(0, net);
    }

    /** Extract underlying symbol (e.g., NIFTY from NIFTY2660924850CE or NIFTY-Jun2026-24850-CE) */
    private static String extractUnderlying(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "";
        String s = symbol.toUpperCase()
                .replaceFirst("^NSE_FO\\|", "")
                .replaceFirst("^NSE:", "")
                .replace("-EQ", "");
        // Dhan format: NIFTY-Jun2026-24850-CE → split by dash
        if (s.contains("-")) {
            return s.split("-")[0];
        }
        // Zerodha/Groww: NIFTY2660924850CE → take leading alpha chars
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) sb.append(c);
            else break;
        }
        String result = sb.toString();
        // Remove trailing CE/PE/FUT if that's all we got
        if (result.endsWith("FUT")) result = result.substring(0, result.length() - 3);
        return result;
    }

    /** Extract option type: CE, PE, FUT, or null for equity */
    private static String extractOptionType(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase();
        if (upper.endsWith("CE") || upper.contains("-CE")) return "CE";
        if (upper.endsWith("PE") || upper.contains("-PE")) return "PE";
        if (upper.endsWith("FUT") || upper.endsWith("F") || upper.contains("-FUT")) return "FUT";
        return null;
    }

    private static String cleanSymbol(String s) {
        if (s == null) return "";
        return s.toUpperCase()
                .replaceFirst("^NSE_FO\\|", "")
                .replaceFirst("^NSE:", "")
                .replace("-EQ", "");
    }
}
