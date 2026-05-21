package com.copytrading.engine;

import org.springframework.stereotype.Component;

/**
 * Rounds F&amp;O quantities to exchange lot multiples after scaling.
 */
@Component
public class LotSizeScaler {

    private final InstrumentCache instruments;
    private final SymbolMapper symbolMapper;

    public LotSizeScaler(InstrumentCache instruments, SymbolMapper symbolMapper) {
        this.instruments = instruments;
        this.symbolMapper = symbolMapper;
    }

    /**
     * @return scaled lot-rounded qty, or 0 if below one lot
     */
    public int apply(int rawQty, String symbol) {
        if (rawQty < 1) return 0;
        if (!symbolMapper.isDerivative(symbol)) return rawQty;
        int lot = instruments.getLotSize(symbol);
        if (lot <= 1) return rawQty;
        int lots = rawQty / lot;
        return lots >= 1 ? lots * lot : 0;
    }
}
