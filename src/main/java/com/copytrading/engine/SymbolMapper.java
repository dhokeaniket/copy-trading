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
        String src = sourceBroker != null && !sourceBroker.isBlank()
                ? sourceBroker.toUpperCase()
                : inferSourceBroker(symbol);
        if (src == null) return symbol;
        String tgt = targetBroker != null ? targetBroker.toUpperCase() : src;
        if (src.equals(tgt)) return symbol;

        ParsedSymbol parsed = parse(symbol, src);
        if (parsed == null) return symbol;
        return build(parsed, tgt);
    }

    /** Guess master symbol format when broker id was not passed (e.g. manual copy without active account). */
    public String inferSourceBroker(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String s = symbol.toUpperCase();
        if (s.startsWith("NSE:") || s.startsWith("BSE:")) return "FYERS";
        if (s.contains("|")) {
            String tail = s.substring(s.indexOf('|') + 1);
            if (tail.matches("\\d+")) return "UPSTOX";
            return "ZERODHA";
        }
        if (s.matches("^[A-Z]+-[A-Za-z]{3}\\d{2}-\\d+-(CE|PE|FUT)$")) return "DHAN";
        if (s.matches("^[A-Z]+\\s+\\d+\\s+(CE|PE)\\s+\\d+\\s+[A-Z]{3}\\s+\\d{2}.*")) return "UPSTOX";
        if (s.matches("^[A-Z]+\\s+FUT\\s+\\d+\\s+[A-Z]{3}\\s+\\d{2}.*")) return "UPSTOX";
        if (s.matches(".*\\d+(CE|PE|FUT|F)$")) return "ZERODHA";
        return null;
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

            Matcher dhan1 = Pattern.compile("^([A-Z]+)-([A-Za-z]{3})20(\\d{2})-(\\d+)-(CE|PE|FUT)$", Pattern.CASE_INSENSITIVE).matcher(clean);
            if (dhan1.matches()) {
                return new ParsedSymbol(dhan1.group(1).toUpperCase(), dhan1.group(3),
                        dhan1.group(2).substring(0, 3).toUpperCase(), "", dhan1.group(4),
                        dhan1.group(5).toUpperCase());
            }

            Matcher upOpt = Pattern.compile("^([A-Z]+)\\s+(\\d+)\\s+(CE|PE)\\s+(\\d{1,2})\\s+([A-Z]{3})\\s+(\\d{2})(?:\\s+\\[\\d+])?\\s*$").matcher(clean);
            if (upOpt.matches()) {
                return new ParsedSymbol(upOpt.group(1), upOpt.group(6), upOpt.group(5), upOpt.group(4),
                        upOpt.group(2), upOpt.group(3));
            }
            Matcher upMon = Pattern.compile("^([A-Z]+)\\s+(\\d+)\\s+(CE|PE)\\s+([A-Z]{3})\\s+(\\d{2})\\s*$").matcher(clean);
            if (upMon.matches()) {
                return new ParsedSymbol(upMon.group(1), upMon.group(5), upMon.group(4), "",
                        upMon.group(2), upMon.group(3));
            }
            Matcher upFut = Pattern.compile("^([A-Z]+)\\s+FUT\\s+(\\d{1,2})\\s+([A-Z]{3})\\s+(\\d{2})\\s*$").matcher(clean);
            if (upFut.matches()) {
                return new ParsedSymbol(upFut.group(1), upFut.group(4), upFut.group(3), upFut.group(2), "", "FUT");
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
                return upstoxTradingSymbol(p, monthNum, isWeekly, isFut);
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

    /**
     * Upstox instrument master uses spaced trading symbols, e.g. {@code NIFTY 24850 CE 02 JUN 26},
     * not Zerodha compact {@code NIFTY2660224850CE}. Orders need {@code instrument_key} from cache lookup.
     */
    private String upstoxTradingSymbol(ParsedSymbol p, int monthNum, boolean isWeekly, boolean isFut) {
        if (isFut) {
            if (isWeekly && monthNum > 0) {
                return String.format("%s FUT %s %s %s", p.underlying, padDay(p.date), p.month, p.year);
            }
            return String.format("%s FUT %s %s", p.underlying, p.month, p.year);
        }
        if (isWeekly && monthNum > 0) {
            return String.format("%s %s %s %s %s %s", p.underlying, p.strike, p.type, padDay(p.date), p.month, p.year);
        }
        if (p.date != null && !p.date.isEmpty()) {
            return String.format("%s %s %s %s %s %s", p.underlying, p.strike, p.type, padDay(p.date), p.month, p.year);
        }
        return String.format("%s %s %s %s %s", p.underlying, p.strike, p.type, p.month, p.year);
    }

    private static String padDay(String day) {
        if (day == null || day.isEmpty()) return day;
        return day.length() == 1 ? "0" + day : day;
    }

    private record ParsedSymbol(String underlying, String year, String month, String date, String strike, String type) {}
}
