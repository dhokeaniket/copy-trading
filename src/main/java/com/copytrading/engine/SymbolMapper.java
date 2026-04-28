package com.copytrading.engine;

import org.springframework.stereotype.Component;
import java.util.regex.*;

@Component
public class SymbolMapper {

    private static final String[] MONTHS = {"", "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    // Detect if symbol is F&O (contains CE/PE at end with numbers)
    public boolean isFnO(String symbol) {
        return symbol != null && symbol.matches(".*\\d+(CE|PE)$");
    }

    // Translate symbol from source broker format to target broker format
    public String translate(String symbol, String sourceBroker, String targetBroker) {
        if (!isFnO(symbol)) return symbol; // Equity symbols are same across brokers
        if (sourceBroker.equals(targetBroker)) return symbol;

        // Parse the symbol into components
        ParsedSymbol parsed = parse(symbol, sourceBroker);
        if (parsed == null) return symbol; // Can't parse, return as-is

        // Build for target broker
        return build(parsed, targetBroker);
    }

    private ParsedSymbol parse(String symbol, String broker) {
        try {
            // Remove broker prefixes
            String clean = symbol.replaceFirst("^NSE_FO\\|", "").replaceFirst("^NSE:", "");

            // Try pattern: UNDERLYING + YY + MONTH_NAME + STRIKE + CE/PE (Zerodha/Fyers/Upstox format)
            Matcher m1 = Pattern.compile("^([A-Z]+)(\\d{2})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(\\d+)(CE|PE)$").matcher(clean);
            if (m1.matches()) {
                return new ParsedSymbol(m1.group(1), m1.group(2), m1.group(3), "", m1.group(4), m1.group(5));
            }

            // Try pattern: UNDERLYING + YY + M + DD + STRIKE + CE/PE (Groww format, M is month number)
            Matcher m2 = Pattern.compile("^([A-Z]+)(\\d{2})(\\d{1,2})(\\d{2})(\\d+)(CE|PE)$").matcher(clean);
            if (m2.matches()) {
                int monthNum = Integer.parseInt(m2.group(3));
                String monthName = monthNum >= 1 && monthNum <= 12 ? MONTHS[monthNum] : "";
                return new ParsedSymbol(m2.group(1), m2.group(2), monthName, m2.group(4), m2.group(5), m2.group(6));
            }

            // Try Dhan format: UNDERLYING-YYMMM-STRIKE-CE/PE
            Matcher m3 = Pattern.compile("^([A-Z]+)-(\\d{2})(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)-(\\d+)-(CE|PE)$").matcher(clean);
            if (m3.matches()) {
                return new ParsedSymbol(m3.group(1), m3.group(2), m3.group(3), "", m3.group(4), m3.group(5));
            }
        } catch (Exception e) { /* parse failed */ }
        return null;
    }

    private String build(ParsedSymbol p, String broker) {
        switch (broker) {
            case "ZERODHA": return p.underlying + p.year + p.month + p.strike + p.type;
            case "FYERS": return "NSE:" + p.underlying + p.year + p.month + p.strike + p.type;
            case "UPSTOX": return "NSE_FO|" + p.underlying + p.year + p.month + p.strike + p.type;
            case "DHAN": return p.underlying + "-" + p.year + p.month + "-" + p.strike + "-" + p.type;
            case "ANGELONE": return p.underlying + p.year + p.month + p.strike + p.type;
            case "GROWW": {
                int monthNum = 0;
                for (int i = 1; i < MONTHS.length; i++) { if (MONTHS[i].equals(p.month)) { monthNum = i; break; } }
                return p.underlying + p.year + monthNum + (p.date.isEmpty() ? "" : p.date) + p.strike + p.type;
            }
            default: return p.underlying + p.year + p.month + p.strike + p.type;
        }
    }

    private record ParsedSymbol(String underlying, String year, String month, String date, String strike, String type) {}
}
