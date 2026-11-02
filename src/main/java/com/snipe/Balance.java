package com.snipe;

import java.util.regex.*;

/** Your coin balance, read from the reply to /bal (for example "You have 646,305.68 Coins"). */
public class Balance {
    public static double coins = -1;
    public static long updated = 0;
    static Pattern p;

    public static void recompile() {
        try { p = Pattern.compile(Config.I.balRegex); } catch (Exception e) { p = null; }
    }

    /** Called for every chat/system message. */
    public static void onMessage(String s) {
        if (p == null) recompile();
        if (p == null) return;
        Matcher m = p.matcher(s);
        if (!m.find()) return;
        try {
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            if (m.groupCount() >= 2 && m.group(2) != null) v *= Config.suffix(m.group(2));
            coins = v; updated = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    public static boolean known() { return coins >= 0 && updated > 0; }
    public static void spend(double x) { if (known()) coins = Math.max(0, coins - x); }
}
