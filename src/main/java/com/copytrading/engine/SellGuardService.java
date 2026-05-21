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
            return livePositionSufficient(childBrokerAccountId, symbol, masterBrokerId, childBrokerId, scaledSellQty);
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
                        log.info("SELL_GUARD net={} required={} symbol={}", net, qty, symbol);
                        return "INSUFFICIENT_POSITION: Live qty " + net + " < sell qty " + qty + " for " + symbol;
                    }
                    return "";
                })
                .onErrorResume(e -> {
                    log.warn("SELL_GUARD_POSITION_FETCH_FAIL symbol={} err={}", symbol, e.getMessage());
                    return Mono.just("");
                });
    }

    private int netLongQty(java.util.List<PositionDto> positions, String masterSymbol, String masterBrokerId,
                           String childBrokerId) {
        if (positions == null || masterSymbol == null) return 0;
        String src = masterBrokerId != null ? masterBrokerId : "GROWW";
        String tgt = childBrokerId != null ? childBrokerId : src;
        String translated = symbolMapper.translate(masterSymbol, src, tgt);
        String cleanMaster = cleanSymbol(masterSymbol);
        String cleanTranslated = cleanSymbol(translated);
        int net = 0;
        for (PositionDto p : positions) {
            if (p.getSymbol() == null || p.getQty() <= 0) continue;
            String ps = cleanSymbol(p.getSymbol());
            if (ps.equals(cleanMaster) || ps.equals(cleanTranslated)
                    || masterSymbol.equalsIgnoreCase(p.getSymbol())
                    || translated.equalsIgnoreCase(p.getSymbol())) {
                if ("SELL".equalsIgnoreCase(p.getSide())) {
                    net -= p.getQty();
                } else {
                    net += p.getQty();
                }
            }
        }
        return Math.max(0, net);
    }

    private static String cleanSymbol(String s) {
        if (s == null) return "";
        return s.toUpperCase()
                .replaceFirst("^NSE_FO\\|", "")
                .replaceFirst("^NSE:", "")
                .replace("-EQ", "");
    }
}
