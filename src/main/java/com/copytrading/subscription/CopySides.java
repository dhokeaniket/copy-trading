package com.copytrading.subscription;

/** Which master trade sides a child subscription copies. */
public final class CopySides {
    /** Copy BUY only; SELL only if child has copied BUY + live position (default). */
    public static final String BUY_ONLY = "BUY_ONLY";
    /** Copy BUY and SELL when child has sufficient live broker position. */
    public static final String BUY_AND_SELL = "BUY_AND_SELL";
    /** Mirror all sides; optional naked short if allowShortSelling is true. */
    public static final String MIRROR = "MIRROR";

    private CopySides() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return BUY_ONLY;
        return switch (value.trim().toUpperCase()) {
            case BUY_AND_SELL, "BUY_SELL", "BOTH" -> BUY_AND_SELL;
            case MIRROR, "ALL" -> MIRROR;
            default -> BUY_ONLY;
        };
    }
}
