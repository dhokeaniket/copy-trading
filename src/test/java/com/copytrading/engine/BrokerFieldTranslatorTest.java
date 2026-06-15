package com.copytrading.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One assertion per broker per field — the only reliable way to verify the outbound mapping is correct.
 * Values mirror BROKER-FIELD-MAPPING.md.
 */
class BrokerFieldTranslatorTest {

    // ---- Order type ----
    @Test
    void orderType_SLM() {
        assertEquals("SL-M", BrokerFieldTranslator.orderType("SL-M", "ZERODHA"));
        assertEquals("SL-M", BrokerFieldTranslator.orderType("SL-M", "UPSTOX"));
        assertEquals("STOP_LOSS_MARKET", BrokerFieldTranslator.orderType("SL-M", "DHAN"));
        assertEquals("SL_M", BrokerFieldTranslator.orderType("SL-M", "GROWW"));
        assertEquals("3", BrokerFieldTranslator.orderType("SL-M", "FYERS"));
        assertEquals("STOPLOSS_MARKET", BrokerFieldTranslator.orderType("SL-M", "ANGELONE"));
    }

    @Test
    void orderType_SL() {
        assertEquals("SL", BrokerFieldTranslator.orderType("SL", "ZERODHA"));
        assertEquals("STOP_LOSS", BrokerFieldTranslator.orderType("SL", "DHAN"));
        assertEquals("SL", BrokerFieldTranslator.orderType("SL", "GROWW"));
        assertEquals("4", BrokerFieldTranslator.orderType("SL", "FYERS"));
        assertEquals("STOPLOSS_LIMIT", BrokerFieldTranslator.orderType("SL", "ANGELONE"));
    }

    @Test
    void orderType_marketAndLimit() {
        assertEquals("MARKET", BrokerFieldTranslator.orderType("MARKET", "ZERODHA"));
        assertEquals("LIMIT", BrokerFieldTranslator.orderType("LIMIT", "DHAN"));
        assertEquals("2", BrokerFieldTranslator.orderType("MARKET", "FYERS"));
        assertEquals("1", BrokerFieldTranslator.orderType("LIMIT", "FYERS"));
    }

    // ---- Product ----
    @Test
    void product() {
        assertEquals("INTRADAY", BrokerFieldTranslator.product("MIS", "DHAN", false));
        assertEquals("I", BrokerFieldTranslator.product("MIS", "UPSTOX", false));
        assertEquals("CARRYFORWARD", BrokerFieldTranslator.product("NRML", "ANGELONE", true));
        assertEquals("MIS", BrokerFieldTranslator.product("MIS", "ZERODHA", false));
        assertEquals("CNC", BrokerFieldTranslator.product("CNC", "DHAN", false));
        assertEquals("MARGIN", BrokerFieldTranslator.product("NRML", "DHAN", true));
        assertEquals("MARGIN", BrokerFieldTranslator.product("NRML", "FYERS", true));
        assertEquals("DELIVERY", BrokerFieldTranslator.product("CNC", "ANGELONE", false));
        assertEquals("D", BrokerFieldTranslator.product("CNC", "UPSTOX", false));
        // Groww: F&O CNC promotes to NRML
        assertEquals("NRML", BrokerFieldTranslator.product("CNC", "GROWW", true));
    }

    // ---- Validity field name ----
    @Test
    void validityFieldName() {
        // FYERS v3 uses "validity" (verified against the official fyers-apiv3 SDK / /api/v3/orders/sync)
        assertEquals("validity", BrokerFieldTranslator.validityFieldName("FYERS"));
        assertEquals("duration", BrokerFieldTranslator.validityFieldName("ANGELONE"));
        assertEquals("validity", BrokerFieldTranslator.validityFieldName("ZERODHA"));
        assertEquals("validity", BrokerFieldTranslator.validityFieldName("UPSTOX"));
        assertEquals("validity", BrokerFieldTranslator.validityFieldName("DHAN"));
        assertEquals("validity", BrokerFieldTranslator.validityFieldName("GROWW"));
    }

    // ---- Exchange segment ----
    @Test
    void exchangeSegment() {
        assertEquals("NSE_FNO", BrokerFieldTranslator.exchangeSegment("NSE", "DHAN", true));
        assertEquals("MCX_COMM", BrokerFieldTranslator.exchangeSegment("MCX", "DHAN", true));
        assertEquals("NFO", BrokerFieldTranslator.exchangeSegment("NSE", "ZERODHA", true));
        assertEquals("BFO", BrokerFieldTranslator.exchangeSegment("BSE", "ZERODHA", true));
        assertEquals("BSE", BrokerFieldTranslator.exchangeSegment("BSE", "ANGELONE", false));
        assertEquals("BFO", BrokerFieldTranslator.exchangeSegment("BSE", "ANGELONE", true));
        assertEquals("NSE_EQ", BrokerFieldTranslator.exchangeSegment("NSE", "DHAN", false));
        assertEquals("BSE_FNO", BrokerFieldTranslator.exchangeSegment("BSE", "DHAN", true));
        assertEquals("NSE", BrokerFieldTranslator.exchangeSegment("NSE", "GROWW", true));
        assertEquals("BSE", BrokerFieldTranslator.exchangeSegment("BSE", "FYERS", true));
    }

    // ---- Transaction type ----
    @Test
    void transactionType() {
        assertEquals("BUY", BrokerFieldTranslator.transactionType("BUY", "ZERODHA"));
        assertEquals("SELL", BrokerFieldTranslator.transactionType("SELL", "DHAN"));
        assertEquals("1", BrokerFieldTranslator.transactionType("BUY", "FYERS"));
        assertEquals("-1", BrokerFieldTranslator.transactionType("SELL", "FYERS"));
    }
}
