package com.copytrading.engine;

import org.springframework.stereotype.Component;
import java.util.regex.*;

@Component
public class SymbolMapper {

    private static final String[] MONTHS = {"", "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    private static final Pattern OPT_CE_PE = Pattern.compile(".*\\d+(CE|PE)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FUT_MONTHLY = Pattern.compile("^([A-Z]+)(\\d{2})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)F$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FUT_NUMERIC = Pattern.compile("^([A-Z]+)(\\d{2})(\\d{1,2})(\\d{2})F$", Pattern.CASE_INSENSITIVE);

    /** Options (CE/PE) or futures. */
    public boolean isDerivative(String symbol) {
        return classify(symbol).isDerivative();
    }

    /** Backward-compatible alias — includes futures. */
    public boolean isFnO(String symbol) {
        return isDerivative(symbol);
    }

    public InstrumentType classify(String symbol) {
        if (symbol == null || symbol.isBlank()) return InstrumentType.UNKNOWN;
        String clean = symbol.replaceFirst("^NSE_FO\\|", "").replaceFirst("^NSE:", "").toUpperCase();
        if (OPT_CE_PE.matcher(clean).matches()) {
            return clean.endsWith("PE") ? InstrumentType.OPTION_PE : InstrumentType.OPTION_CE;
        }
        if (FUT_MONTHLY.matcher(clean).matches() || FUT_NUMERIC.matcher(clean).matches() || clean.endsWith("FUT")) {
            return InstrumentType.FUTURES;
        }
        if (clean.matches("^(NIFTY|SENSEX|BANKNIFTY|FINNIFTY|MIDCPNIFTY)(\\d+)?$")) {
            return InstrumentType.INDEX;
        }
        return InstrumentType.EQUITY;
    }

    public String translate(String symbol, String sourceBroker, String targetBroker) {
        if (symbol == null) return symbol;
        if (!isDerivative(symbol)) return symbol;
        String src = sourceBroker != null ? sourceBroker.toUpperCase() : "GROWW";
        String tgt = targetBroker != null ? targetBroker.toUpperCase() : src;
        if (src.equals(tgt)) return symbol;

        ParsedSymbol parsed = parse(symbol, src);
        if (parsed == null) return symbol;
        return build(parsed, tgt);
    }

    private ParsedSymbol parse(String symbol, String broker) {
        try {
            String clean = symbol.replaceFirst("^NSE_FO\\|", "").replaceFirst("^NSE:", "");

            Matcher futM = FUT_MONTHLY.matcher(clean);
            if (futM.matches()) {
                return new ParsedSymbol(futM.group(1), futM.group(2), futM.group(3), "", "", "FUT");
            }
            Matcher futN = FUT_NUMERIC.matcher(clean);
            if (futN.matches()) {
                int monthNum = Integer.parseInt(futN.group(3));
                if (monthNum >= 1 && monthNum <= 12) {
                    return new ParsedSymbol(futN.group(1), futN.group(2), MONTHS[monthNum], futN.group(4), "", "FUT");
                }
            }

            Matcher m1 = Pattern.compile("^([A-Z]+)(\\d{2})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(\\d+)(CE|PE)$").matcher(clean);
            if (m1.matches()) {
                return new ParsedSymbol(m1.group(1), m1.group(2), m1.group(3), "", m1.group(4), m1.group(5));
            }

            for (String monthPattern : new String[]{"(\\d{1})(\\d{2})", "(\\d{2})(\\d{2})"}) {
                Matcher m2 = Pattern.compile("^([A-Z]+)(\\d{2})" + monthPattern + "(\\d+)(CE|PE)$").matcher(clean);
                if (m2.matches()) {
                    int monthNum = Integer.parseInt(m2.group(3));
                    if (monthNum >= 1 && monthNum <= 12) {
                        return new ParsedSymbol(m2.group(1), m2.group(2), MONTHS[monthNum], m2.group(4), m2.group(5), m2.group(6));
                    }
                }
            }

            Matcher m3 = Pattern.compile("^([A-Z]+)-(\\d{2})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)-(\\d+)-(CE|PE)$").matcher(clean);
            if (m3.matches()) {
                return new ParsedSymbol(m3.group(1), m3.group(2), m3.group(3), "", m3.group(4), m3.group(5));
            }
        } catch (Exception e) { /* parse failed */ }
        return null;
    }

    private String build(ParsedSymbol p, String broker) {
        int monthNum = 0;
        for (int i = 1; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(p.month)) { monthNum = i; break; }
        }
        boolean isWeekly = p.date != null && !p.date.isEmpty();
        boolean isFut = "FUT".equalsIgnoreCase(p.type);

        switch (broker) {
            case "ZERODHA" -> {
                if (isFut) {
                    return isWeekly && monthNum > 0
                            ? p.underlying + p.year + monthNum + p.date + "F"
                            : p.underlying + p.year + p.month + "F";
                }
                if (isWeekly) return p.underlying + p.year + monthNum + p.date + p.strike + p.type;
                return p.underlying + p.year + p.month + p.strike + p.type;
            }
            case "FYERS" -> {
                if (isFut) {
                    String body = isWeekly && monthNum > 0
                            ? p.underlying + p.year + monthNum + p.date + "F"
                            : p.underlying + p.year + p.month + "F";
                    return "NSE:" + body;
                }
                if (isWeekly) return "NSE:" + p.underlying + p.year + monthNum + p.date + p.strike + p.type;
                return "NSE:" + p.underlying + p.year + p.month + p.strike + p.type;
            }
            case "UPSTOX" -> {
                if (isFut) {
                    String body = isWeekly && monthNum > 0
                            ? p.underlying + p.year + monthNum + p.date + "F"
                            : p.underlying + p.year + p.month + "F";
                    return "NSE_FO|" + body;
                }
                if (isWeekly) return "NSE_FO|" + p.underlying + p.year + monthNum + p.date + p.strike + p.type;
                return "NSE_FO|" + p.underlying + p.year + p.month + p.strike + p.type;
            }
            case "DHAN" -> {
                if (isFut) {
                    String monthCap = p.month.substring(0, 1).toUpperCase() + p.month.substring(1).toLowerCase();
                    return p.underlying + "-" + monthCap + "20" + p.year + "-FUT";
                }
                if (p.month == null || p.month.isEmpty()) return p.underlying + p.year + p.strike + p.type;
                String monthCap = p.month.substring(0, 1).toUpperCase() + p.month.substring(1).toLowerCase();
                return p.underlying + "-" + monthCap + "20" + p.year + "-" + p.strike + "-" + p.type;
            }
            case "ANGELONE" -> {
                if (isFut) {
                    return isWeekly && monthNum > 0
                            ? p.underlying + p.year + monthNum + p.date + "F"
                            : p.underlying + p.year + p.month + "F";
                }
                if (isWeekly) return p.underlying + p.year + monthNum + p.date + p.strike + p.type;
                return p.underlying + p.year + p.month + p.strike + p.type;
            }
            case "GROWW" -> {
                if (isFut) {
                    return isWeekly && monthNum > 0
                            ? p.underlying + p.year + monthNum + p.date + "FUT"
                            : p.underlying + p.year + (monthNum > 0 ? monthNum : "") + "FUT";
                }
                return p.underlying + p.year + monthNum + (p.date.isEmpty() ? "" : p.date) + p.strike + p.type;
            }
            default -> {
                if (isFut) return p.underlying + p.year + p.month + "F";
                return p.underlying + p.year + p.month + p.strike + p.type;
            }
        }
    }

    private record ParsedSymbol(String underlying, String year, String month, String date, String strike, String type) {}
}
