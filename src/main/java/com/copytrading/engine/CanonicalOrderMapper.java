package com.copytrading.engine;

import com.copytrading.trade.Trade;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Single entry point: raw broker order map → {@link CanonicalOrder} → copy trade / DB trade.
 */
@Component
public class CanonicalOrderMapper {

    private final SymbolMapper symbolMapper;

    public CanonicalOrderMapper(SymbolMapper symbolMapper) {
        this.symbolMapper = symbolMapper;
    }

    public CanonicalOrder fromBrokerOrder(Map<String, Object> raw, String sourceBrokerId) {
        CanonicalOrder o = new CanonicalOrder();
        o.setSourceBrokerId(sourceBrokerId != null && !sourceBrokerId.isBlank()
                ? sourceBrokerId.toUpperCase() : null);
        o.setOrderId(OrderNormalizer.extractOrderId(raw));
        // Normalize status and side to canonical values immediately on ingestion so the
        // rest of the engine never reasons about raw broker strings.
        o.setStatus(BrokerStatusNormalizer.toCanonical(OrderNormalizer.extractStatus(raw), sourceBrokerId));
        o.setSymbol(OrderNormalizer.extractSymbol(raw));
        o.setSide(BrokerStatusNormalizer.toCanonicalSide(OrderNormalizer.extractSide(raw), sourceBrokerId));
        o.setFilledQty(OrderNormalizer.extractFilledQty(raw));
        o.setPrice(OrderNormalizer.extractPrice(raw));
        o.setTriggerPrice(OrderNormalizer.extractTriggerPrice(raw));
        o.setExchange(defaultExchange(OrderNormalizer.extractField(raw, "exchange"), o.getSymbol()));
        o.setOrderType(defaultOrderType(OrderNormalizer.extractField(raw, "order_type", "orderType")));

        String rawProduct = OrderNormalizer.extractField(raw, "product", "productType", "product_type");
        o.setProduct(BrokerProductMapper.normalizeProduct(rawProduct));

        InstrumentType type = o.getSymbol() != null ? symbolMapper.classify(o.getSymbol()) : InstrumentType.UNKNOWN;
        o.setInstrumentType(type);
        o.setSegment(type.isDerivative() ? "FNO" : defaultSegment(OrderNormalizer.extractField(raw, "segment")));

        int orderQty = OrderNormalizer.extractOrderQty(raw);
        o.setReadyForCopy(o.getOrderId() != null && o.getSymbol() != null && o.getSide() != null
                && OrderNormalizer.shouldProcessForCopy(o.getStatus(), o.getFilledQty(), orderQty));
        return o;
    }

    public CopyTradeRequest toCopyTradeRequest(CanonicalOrder o) {
        CopyTradeRequest req = new CopyTradeRequest();
        req.setSymbol(o.getSymbol());
        req.setQty(o.getFilledQty());
        req.setSide(o.getSide() != null ? o.getSide().toUpperCase() : "BUY");
        req.setProduct(o.getProduct());
        req.setOrderType(o.getOrderType());
        req.setExchange(o.getExchange());
        req.setMasterBrokerId(o.getSourceBrokerId());
        req.setMasterOrderId(o.getOrderId());
        boolean limit = "LIMIT".equalsIgnoreCase(o.getOrderType());
        boolean sl = "SL".equalsIgnoreCase(o.getOrderType()) || "SL-M".equalsIgnoreCase(o.getOrderType());
        req.setPrice(limit || sl ? o.getPrice() : 0);
        req.setTriggerPrice(sl ? o.getTriggerPrice() : 0);
        return req;
    }

    public Trade toMasterTrade(CanonicalOrder o, UUID masterId, UUID brokerAccountId) {
        Trade t = new Trade();
        t.setUserId(masterId);
        t.setBrokerAccountId(brokerAccountId);
        t.setBrokerOrderId(o.getOrderId());
        t.setInstrument(o.getSymbol());
        t.setExchange(o.getExchange());
        t.setSegment(o.getSegment());
        t.setOrderType(o.getOrderType());
        t.setTransactionType(o.getSide() != null ? o.getSide().toUpperCase() : "BUY");
        t.setQuantity(o.getFilledQty());
        t.setPrice(o.getPrice());
        t.setTriggerPrice(o.getTriggerPrice() > 0 ? o.getTriggerPrice() : null);
        t.setProduct(o.getProduct());
        t.setStatus("EXECUTED");
        t.setPlacedAt(Instant.now());
        t.setExecutedAt(Instant.now());
        return t;
    }

    private static String defaultExchange(String exchange, String symbol) {
        if (exchange != null && !exchange.isBlank()) return exchange.toUpperCase();
        if (symbol != null && (symbol.toUpperCase().startsWith("SENSEX") || symbol.toUpperCase().startsWith("BANKEX"))) {
            return "BSE";
        }
        return "NSE";
    }

    private static String defaultOrderType(String orderType) {
        return orderType != null && !orderType.isBlank() ? orderType.toUpperCase() : "MARKET";
    }

    private static String defaultSegment(String segment) {
        return segment != null && !segment.isBlank() ? segment.toUpperCase() : "EQUITY";
    }
}
