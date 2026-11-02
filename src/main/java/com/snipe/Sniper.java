package com.snipe;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import java.util.*;
import java.util.regex.*;

public class Sniper {
    enum State { OPEN, SCAN, CONFIRM, VERIFY, WAIT, RS_FIND, RS_SEND, RS_CONFIRM, RS_VERIFY, BAL_WAIT, CLAIM_OPEN, CLAIM_CLICK, CLAIM_TAKE }
    public static boolean running = false;
    public static String status = "idle";
    static boolean analyzing = false;   // first pass before sniping: scan only
    static boolean analyzeOnly = false; // one scan pass, then stop
    static State state = State.OPEN;
    static int cooldown, wait, page, tries;
    static Map<Integer, List<Market.Listing>> pages = new HashMap<>();
    static Map<String, Long> alerted = new HashMap<>();
    static Purchases.P pending;
    static double resellRef;
    static int pendingBefore, rsBefore, failStreak, sellFails, sellCount;
    static String pendingId = "", pendingSig = "", rsId = "";
    static boolean offline, fullScan, expectChange, endOfList;
    static String prevSig = "";
    static int pageWait;
    static int scanLimit, sortTries, sortLastSel = -1, sortClickWait;
    static boolean sortOk, sortFailed, sortWarned;
    static long lastFull;
    static long sessionStart;
    static Map<String, Long> blocked = new HashMap<>();
    static Pattern priceP, sellerP;
    // balance + claim menu
    static boolean waitingFunds, sawUnaffordable, balFailed, claimedOnce, resellScanPending;
    static double needed;
    static long lastBalAsk, balAsk, lastClaim;
    static int balFails, claimClicks, claimReason, claimFreeBefore;

    static void patterns() {
        priceP = Pattern.compile(Config.I.priceRegex);
        sellerP = Pattern.compile(Config.I.sellerRegex);
    }

    static boolean isGreen(Component c) {
        var col = c.getStyle().getColor();
        if (col != null && col.getValue() == 0x55FF55) return true;
        for (Component sib : c.getSiblings()) if (isGreen(sib)) return true;
        return false;
    }

    /**
     * Reads the AH "Sort Items" button tooltip. The selected option has an arrow (or is green), the others a bullet.
     * Returns {selectedIndex, targetIndex} or null if it can't be read.
     */
    static int[] sortState(ItemStack b) {
        ItemLore lore = b.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<String> opts = new ArrayList<>(); int sel = -1;
        for (Component line : lore.lines()) {
            String s = line.getString().trim();
            if (s.isEmpty() || s.toLowerCase().contains("click")) continue;
            char c = s.charAt(0);
            boolean arrow = "\u2192\u279C\u27A4\u25B6\u25BA\u00BB\u279E\u2794\u27A1>".indexOf(c) >= 0;
            boolean bullet = "\u25AA\u2022\u00B7\u25CF\u25E6\u2023\u2043-*".indexOf(c) >= 0;
            if (!arrow && !bullet) continue;
            opts.add(s.substring(1).trim().toLowerCase());
            if (arrow || (sel < 0 && isGreen(line))) sel = opts.size() - 1;
        }
        int target = -1;
        for (int i = 0; i < opts.size(); i++) if (opts.get(i).contains(Config.I.sortTarget.toLowerCase())) target = i;
        return sel < 0 || target < 0 ? null : new int[]{sel, target};
    }

    static void begin() {
        sortWarned = false;
        patterns();
        running = true; state = State.OPEN; cooldown = 0;
        sessionStart = System.currentTimeMillis(); failStreak = 0; sellFails = 0; offline = false;
        waitingFunds = false; resellScanPending = false; sawUnaffordable = false; balFailed = false; balFails = 0; lastBalAsk = 0; needed = 0; claimedOnce = false; Balance.recompile();
    }

    public static void start() {
        begin();
        analyzeOnly = false;
        analyzing = Config.I.buyEnabled && Config.I.analyzeFirst && System.currentTimeMillis() - Market.lastCommit > 10 * 60_000L;
        status = "starting";
        msg(!Config.I.buyEnabled ? "Tracking started (buy mode is off)" : analyzing ? "Analyzing the AH first (Snipe List items are still bought while it scans)" : "Sniping started" + (Config.I.autoSort ? " (AH sort -> " + Config.I.sortTarget + ")" : ""));
    }
    /** One full scan with no buying, then stops by itself. */
    public static void analyze() {
        if (running) { msg("Stop the bot first"); return; }
        begin(); analyzeOnly = true; analyzing = false; status = "starting analysis";
        msg("Analyzing the whole AH: every page, no buying. This can take a minute or two.");
    }
    public static void stop() { running = false; analyzing = false; analyzeOnly = false; status = "idle"; msg("Stopped"); summary(); Market.save(); }

    /** Stop because something needs your attention (inventory full, purchases failing...). */
    static void halt(String why) {
        running = false; analyzing = false; analyzeOnly = false; status = "STOPPED: " + why;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) { mc.player.closeContainer(); Compat.sound(mc, 3); }
        say(Component.literal("[Sniper] ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("STOPPED: " + why).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        summary(); Market.save();
    }

    static void summary() {
        int n = 0, listed = 0; double spent = 0, exp = 0;
        for (Purchases.P p : Purchases.list) {
            if (p.time < sessionStart) continue;
            n++; spent += p.total;
            if (p.status.startsWith("listed")) { listed++; exp += p.listedFor * (1 - Config.I.ahTaxPercent / 100) - p.total; }
        }
        if (n > 0) msg("Session: bought " + n + " for " + Config.fmt(spent) + ", " + listed + " listed, expected profit if they sell: " + (exp < 0 ? "-" : "+") + Config.fmt(Math.abs(exp)));
    }

    // ---- inventory helpers
    static int countId(Minecraft mc, String id) {
        int c = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Compat.invItem(mc, i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id)) c += s.getCount();
        }
        return c;
    }
    static int freeSlots(Minecraft mc) {
        int f = 0;
        for (int i = 0; i < 36; i++) if (Compat.invItem(mc, i).isEmpty()) f++;
        return f;
    }
    static String sig(Market.Listing l) { return l.key() + "|" + l.seller() + "|" + (long) l.total() + "|" + l.count(); }
    static boolean blockedWarn(Market.Listing l) {
        if (!isBlocked(l)) return false;
        warnOnce(l, "a recent attempt to buy it failed (retrying in a few minutes)");
        return true;
    }
    static boolean isBlocked(Market.Listing l) {
        Long t = blocked.get(sig(l));
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 5 * 60_000L) { blocked.remove(sig(l)); return false; }
        return true;
    }
    /** Cheapest other listing of the same item seen so far this scan (per item). 0 = none. */
    static double lowestOther(Market.Listing l) {
        double best = 0;
        for (var pg : pages.values()) for (Market.Listing x : pg)
            if (x != l && x.key().equals(l.key()) && (best == 0 || x.unit() < best)) best = x.unit();
        return best;
    }
    /** Resell reference per item for this listing (unit price). 0 = unknown. */
    static double refUnit(String key, double lowestOther) {
        Market.Snap s = Market.latest(key);
        return switch (Config.I.resellMode) {
            case "AVERAGE" -> s != null ? s.avg : 0;
            case "MEDIAN" -> s != null ? s.median : 0;
            default -> lowestOther > 0 ? lowestOther : (s != null ? s.median : 0);
        };
    }
    /** AFK pre-check: only buy what we could relist at a profit. */
    static boolean worthIt(Market.Listing l) {
        if (!Config.I.afk) return true;
        double ref = refUnit(l.key(), lowestOther(l));
        if (ref <= 0) { warnOnce(l, "can't price a resale (no data)"); return false; }
        double total = Math.floor(ref * (1 - Config.I.undercutPercent / 100) * l.count());
        if (total * (1 - Config.I.ahTaxPercent / 100) < l.total() * (1 + Config.I.minProfitPercent / 100)) { warnOnce(l, "resale would not be profitable"); return false; }
        return true;
    }
    /** Mistake sniping: an un-renamed item listed absurdly far under its real average (1 coin, missing zero...). */
    static boolean autoOk(Market.Listing l) {
        Config c = Config.I;
        if (!c.autoSnipe || l.custom()) return false;
        if (c.autoSnipeMaxSpend > 0 && l.total() > c.autoSnipeMaxSpend) return false;
        Market.Snap s = Market.latest(l.key());
        if (s == null || s.total < 3 || s.sellers < c.minSellers || s.avg < c.autoSnipeMinAvg) return false;
        return (1 - l.unit() / s.avg) * 100 >= c.autoSnipePercent;
    }
    static boolean underLimit(Market.Listing l) {
        if (Config.I.maxUnsold <= 0) return true;
        double held = 0;
        for (Purchases.P p : Purchases.list) if (p.status.startsWith("held")) held += p.total;
        if (held + l.total() > Config.I.maxUnsold) { warnOnce(l, "unsold stock limit reached (" + Config.fmt(held) + ")"); return false; }
        return true;
    }

    static void msg(String s) { say(Component.literal("[Sniper] ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal(s).withStyle(ChatFormatting.WHITE))); }
    static void warn(String s) { say(Component.literal("[Sniper] ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal(s).withStyle(ChatFormatting.YELLOW))); }
    static void say(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) Compat.say(mc, c);
    }

    static void addEnchants(ItemEnchantments ie, List<String> out) {
        if (ie == null) return;
        for (var e : ie.entrySet()) out.add(e.getKey().getRegisteredName().replace("minecraft:", "") + " " + e.getIntValue());
    }

    static final Pattern VANILLA_ENCH = Pattern.compile("(?i)(protection|fire protection|blast protection|projectile protection|feather falling|respiration|aqua affinity|thorns|depth strider|frost walker|binding curse|sharpness|smite|bane of arthropods|knockback|fire aspect|looting|sweeping edge|efficiency|silk touch|unbreaking|fortune|power|punch|flame|infinity|luck of the sea|lure|loyalty|impaling|riptide|channeling|multishot|quick charge|piercing|density|breach|wind burst|mending|vanishing curse|soul speed|swift sneak|lunge)( [ivx]+| \\d+)?");

    /** A custom-enchant title is a short, number-free phrase like "Demolitionist" (descriptions are longer sentences). */
    static boolean isTitle(String s) {
        return s.length() <= 22 && s.split("\\s+").length <= 3 && !s.matches(".*[0-9%.,:].*");
    }
    static boolean isPremium(String id) {
        for (String p : Config.I.premiumBases.split(",")) if (!p.isBlank() && id.contains(p.trim().toLowerCase())) return true;
        return false;
    }

    static boolean isBold(Component c) {
        if (c.getStyle().isBold()) return true;
        for (Component sib : c.getSiblings()) if (isBold(sib)) return true;
        return false;
    }
    /** Tools, weapons, armour, elytra, enchanted books: their name is cosmetic (anyone can rename them in an anvil). */
    static boolean isGear(ItemStack st, String id) { return st.isDamageableItem() || id.equals("enchanted_book"); }

    /** Name used for price grouping: the default item name for renamed gear, otherwise the real name. */
    static String groupName(ItemStack st) {
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return st.has(DataComponents.CUSTOM_NAME) && isGear(st, id) ? st.getItem().getDefaultInstance().getHoverName().getString() : st.getHoverName().getString();
    }

    /**
     * What makes two items differ in value.
     *  - Custom-enchant lore (bold title lines like "Unbroken Chain" / "Demolitionist"): the value comes from the enchant, not the base item
     *    or its name, so it is grouped by the enchant titles ("ce:...") across all base items, except premium bases (netherite) which stay separate ("@id").
     *    Renamed gear counts too (the rename is cosmetic); renamed non-gear custom items do not.
     *  - Everything else: vanilla enchantments (Prot IV chestplate != plain chestplate).
     * The AH's own lines (seller / price / time) are ignored. "" = plain item.
     */
    static String variantOf(ItemStack st) {
        if (priceP == null) patterns();
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        boolean named = st.has(DataComponents.CUSTOM_NAME), gear = isGear(st, id);
        List<String> own = new ArrayList<>(), boldLines = new ArrayList<>(), vanillaLore = new ArrayList<>();
        ItemLore lore = st.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        for (Component line : lore.lines()) {
            String s = line.getString().trim();
            if (priceP.matcher(s).find() || sellerP.matcher(s).find()) break;
            if (s.isEmpty()) continue;
            if (VANILLA_ENCH.matcher(s).matches()) { vanillaLore.add(s.toLowerCase()); continue; }
            own.add(s);
            if (isBold(line) && s.length() <= 40) boldLines.add(s);
        }
        TreeSet<String> titles = new TreeSet<>(boldLines);
        if (titles.isEmpty() && !named && !own.isEmpty()) {
            for (String s : own) if (isTitle(s)) titles.add(s);
            if (titles.isEmpty()) titles.add(own.get(0));
        }
        if (!titles.isEmpty() && (gear || !named)) {
            String v = "ce:" + String.join(" + ", titles);
            return isPremium(id) ? v + "@" + id : v;
        }
        List<String> parts = new ArrayList<>(vanillaLore);
        addEnchants(st.get(DataComponents.ENCHANTMENTS), parts);
        addEnchants(st.get(DataComponents.STORED_ENCHANTMENTS), parts);
        Collections.sort(parts);
        return String.join(";", parts);
    }

    static String keyOf(String name, String id, String variant) {
        if (variant.startsWith("ce:")) {
            String t = variant.substring(3); int at = t.indexOf('@');
            return Config.key(at >= 0 ? t.substring(0, at) : t) + "|" + (at >= 0 ? t.substring(at + 1) : "enchanted_book") + "|any";
        }
        return Config.vkey(name, id) + (variant.isEmpty() ? "" : "|" + variant);
    }

    /** Item identity of a stack, same format used for AH listings (includes enchants / custom lore). */
    static String keyOf(ItemStack st) {
        return keyOf(groupName(st), BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), variantOf(st));
    }

    static Market.Listing listing(ItemStack st) {
        ItemLore lore = st.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        double total = -1; String seller = "?";
        for (Component line : lore.lines()) {
            String s = line.getString();
            if (total < 0) {
                Matcher m = priceP.matcher(s);
                if (m.find()) {
                    try { total = Double.parseDouble(m.group(1).replace(",", "")) * Config.suffix(m.groupCount() >= 2 && m.group(2) != null ? m.group(2) : ""); }
                    catch (Exception ignored) {}
                }
            }
            if (seller.equals("?")) { Matcher sm = sellerP.matcher(s); if (sm.find()) seller = sm.group(1); }
        }
        if (total <= 0) return null;
        String name = st.getHoverName().getString();
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        String variant = variantOf(st);
        String gname = groupName(st);
        boolean spoofable = st.has(DataComponents.CUSTOM_NAME) && !isGear(st, id) && !variant.startsWith("ce:");
        return new Market.Listing(keyOf(gname, id, variant), name, id, spoofable, total, st.getCount(), seller, variant, gname);
    }

    static int findButton(ChestMenu h, String contains) {
        int size = h.getRowCount() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = h.getSlot(i).getItem();
            if (!s.isEmpty() && s.getHoverName().getString().toLowerCase().contains(contains.toLowerCase())) return i;
        }
        return -1;
    }

    static void click(Minecraft mc, ChestMenu h, int slot) { Compat.click(mc, h, slot); cooldown = Config.I.delayTicks; }

    static void warnOnce(Market.Listing l, String why) {
        String sig = "skip|" + l.key() + "|" + l.seller() + "|" + (long) l.total();
        if (alerted.containsKey(sig)) return;
        alerted.put(sig, System.currentTimeMillis());
        warn("Skipped " + l.label() + " for " + Config.fmt(l.total()) + ": " + why);
    }

    /** Your snipe entry for this listing: the exact item/variant first, else the plain item (an enchanted one is never worth less). */
    static Config.Entry entryFor(Market.Listing l) {
        Config.Entry e = Config.I.entries.get(l.key());
        if (e != null && e.enabled && e.maxPrice > 0) return e;
        Config.Entry b = Config.I.entries.get(l.baseKey());
        return b != null && b.enabled && b.maxPrice > 0 ? b : null;
    }
    static boolean good(Market.Snap s) { return s != null && s.total >= 3 && s.sellers >= Config.I.minSellers; }
    /** Price data to judge a listing by: its own variant if it has enough data, else the plain item. */
    static Market.Snap snapFor(Market.Listing l) {
        Market.Snap s = Market.latest(l.key());
        if (good(s)) return s;
        Market.Snap b = Market.latest(l.baseKey());
        return good(b) ? b : s;
    }

    /** Safety checks before buying: needs price data, and must not look too good to be true. */
    static boolean safe(Market.Listing l) {
        Market.Snap s = snapFor(l);
        boolean haveData = good(s);
        if (Config.I.requireData && !haveData) { warnOnce(l, "no price data for this exact item yet (run Analyze)"); return false; }
        double guard = l.custom() ? Config.I.scamGuardPercent : Config.I.scamGuardPlainPercent;
        if (haveData && (1 - l.unit() / s.avg) * 100 > guard) {
            warnOnce(l, Math.round((1 - l.unit() / s.avg) * 100) + "% under average, looks fake"); return false;
        }
        return true;
    }

    /** Chat alert for a listing far below the average of an item you are not sniping. */
    static void alertIfGood(Market.Listing l, int pageNo) {
        Config.Entry e = Config.I.entries.get(l.key());
        if (e != null && e.enabled) return;
        Market.Snap s = Market.latest(l.key());
        if (s == null || s.total < 3 || s.sellers < Config.I.minSellers || s.avg < Config.I.alertMinPrice) return;
        double disc = 1 - l.unit() / s.avg;
        if (disc * 100 < Config.I.alertPercent) return;
        String sig = l.key() + "|" + l.seller() + "|" + (long) l.total() + "|" + l.count();
        long now = System.currentTimeMillis();
        Long t = alerted.get(sig);
        if (t != null && now - t < 30 * 60_000L) return;
        if (alerted.size() > 500) alerted.clear();
        alerted.put(sig, now);
        say(Chat.deal(l, s, pageNo, false));
        if (Config.I.dealSound) Compat.sound(Minecraft.getInstance(), Chat.tier(Math.round(disc * 100)).sound());
    }

    /** Price to relist at (total for the stack), 0 = no data, -1 = not profitable. */
    /** Reference price per item for relisting. With the rescan on, LOWEST uses the fresh market's real lowest BIN. */
    static double resellRefPrice() {
        Market.Snap s = Market.latest(pending.key);
        if (!Config.I.resellRescan) return refUnit(pending.key, resellRef);
        switch (Config.I.resellMode) {
            case "AVERAGE": return s != null ? s.avg : 0;
            case "MEDIAN": return s != null ? s.median : 0;
            default: {
                if (s == null) return 0;
                boolean fresh = System.currentTimeMillis() - s.t <= Config.I.resellFreshSec * 1000L + 30_000L;
                if (!fresh) return s.median;   // nobody else has it listed right now: use the usual price
                double low = s.low;
                if (s.total >= 3 && s.avg > 0 && (1 - low / s.avg) * 100 > Config.I.scamGuardPercent) low = s.median; // fake-cheap listing is not the real lowest
                return low;
            }
        }
    }

    static double resellPrice() {
        double ref = resellRefPrice();
        if (ref <= 0) return 0;
        double total = Math.floor(ref * (1 - Config.I.undercutPercent / 100) * sellCount);
        double net = total * (1 - Config.I.ahTaxPercent / 100);
        double cost = pending.total / Math.max(1, pending.count) * sellCount;
        return net < cost * (1 + Config.I.minProfitPercent / 100) ? -1 : total;
    }

    static void bought(Minecraft mc) { state = State.VERIFY; wait = 0; cooldown = 4; status = "Verifying purchase"; }

    /** Tells you in chat why a listing that looks like one of your snipes was NOT bought. */
    static void diagnose(Market.Listing l, boolean canBuy) {
        Config.Entry e = entryFor(l);
        if (e != null) {
            boolean priceOk = (e.each ? l.unit() : l.total()) <= e.maxPrice;
            if (priceOk && !canBuy) warnOnce(l, resellScanPending ? "rescanning the AH before relisting an item (back to sniping in a moment)" : analyzeOnly ? "this is an Analyze-only run (it never buys)" : waitingFunds ? "waiting for more coins" : !Config.I.buyEnabled ? "Buy mode is OFF in Settings" : "the bot is not buying right now");
            return;
        }
        String n1 = Config.key(l.gname()), n2 = Config.key(l.name());
        for (var en : Config.I.entries.entrySet()) {
            Config.Entry x = en.getValue();
            if (!x.enabled || x.maxPrice <= 0) continue;
            String kn = en.getKey().split("\\|", 2)[0];
            if (!kn.equals(n1) && !kn.equals(n2)) continue;
            if ((x.each ? l.unit() : l.total()) > x.maxPrice) continue;
            warnOnce(l, "it has the same NAME as a Snipe List item but is a different item/version [" + l.id() + (l.variant().isEmpty() ? "" : ", " + Market.shortVariant(l.variant())) + "]. Add this exact one from the Economy tab to snipe it.");
            return;
        }
    }
    /** A Snipe List item that looks suspiciously cheap is still bought (you set the price), but you get a warning. */
    static void headsUp(Market.Listing l) {
        if (!l.custom() || entryFor(l) == null) return;
        Market.Snap s = snapFor(l);
        if (!good(s) || s.avg <= 0) return;
        double pct = (1 - l.unit() / s.avg) * 100;
        if (pct > Config.I.scamGuardPercent) warn("Heads up: " + l.label() + " is " + Math.round(pct) + "% under its average - buying anyway because it is on your Snipe List.");
    }

    // ---------------------------------------------------------------- balance
    /** Ask the server for your coins every balRefreshSec. Returns true if it started a check. */
    static boolean needBalance(Minecraft mc) {
        if (!Config.I.checkBalance || balFailed || analyzeOnly || !Config.I.buyEnabled) return false;
        long now = System.currentTimeMillis();
        if (now - lastBalAsk < Math.max(5, Config.I.balRefreshSec) * 1000L) return false;
        if (Compat.screen(mc) != null) mc.player.closeContainer();
        lastBalAsk = now; balAsk = Balance.updated;
        Compat.command(mc, Config.I.balCommand);
        state = State.BAL_WAIT; wait = 0; cooldown = 4; status = "Checking balance";
        return true;
    }
    static void fundsCheck() {
        if (waitingFunds && Balance.known() && Balance.coins >= needed) {
            waitingFunds = false;
            msg("You have " + Config.fmt(Balance.coins) + " coins again - sniping resumes");
            needed = 0;
        }
    }
    /** false (and remembers the shortfall) if the listing costs more than your balance. */
    static boolean afford(Market.Listing l) {
        if (!Config.I.checkBalance || balFailed || !Balance.known()) return true;
        if (Balance.coins >= l.total()) return true;
        sawUnaffordable = true;
        needed = needed <= 0 ? l.total() : Math.min(needed, l.total());
        return false;
    }

    // ---------------------------------------------------------------- AH claim menu
    static void startClaim(int why) {
        claimReason = why; claimedOnce = true; claimClicks = 0; wait = 0;
        state = State.CLAIM_OPEN; cooldown = Config.I.delayTicks; status = "Checking AH claim menu";
    }
    /** First real item in the claim menu (skips borders, barrier "empty" marker and buttons). */
    static int nextClaimSlot(ChestMenu h) {
        int size = h.getRowCount() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack st = h.getSlot(i).getItem();
            if (st.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (id.equals("barrier") || id.endsWith("_pane")) continue;
            String n = st.getHoverName().getString().trim().toLowerCase();
            if (n.isEmpty() || n.contains("back") || n.contains("close") || n.contains("next") || n.contains("previous") || n.contains("page") || n.contains("claim")) continue;
            return i;
        }
        return -1;
    }
    static void claimDone(Minecraft mc) {
        if (Compat.screen(mc) != null) mc.player.closeContainer();
        lastClaim = System.currentTimeMillis();
        switch (claimReason) {
            case 1 -> { if (countId(mc, pendingId) > pendingBefore) { msg("Found " + pending.name + " in the AH claim menu and claimed it"); purchaseOk(mc); } else purchaseFailed(mc); }
            case 2 -> { state = State.RS_FIND; tries = 0; cooldown = 10; }
            default -> { if (claimClicks > 0) msg("Claimed " + claimClicks + " item(s) from the AH claim menu"); state = State.OPEN; cooldown = 10; }
        }
    }

    // ---------------------------------------------------------------- purchase result
    static void purchaseOk(Minecraft mc) {
        failStreak = 0; Balance.spend(pending.total);
        Purchases.add(pending);
        if (Config.I.resellOn()) { state = State.RS_FIND; tries = 0; cooldown = Config.I.resellWaitTicks; status = "Preparing resell"; }
        else { state = State.SCAN; cooldown += 8; }
    }
    static void purchaseFailed(Minecraft mc) {
        if (Compat.screen(mc) != null) mc.player.closeContainer();
        if (Config.I.checkBalance && Balance.known() && Balance.coins < pending.total) {
            waitingFunds = true; needed = pending.total; failStreak = 0;
            warn("Not enough coins for " + pending.name + " (need " + Config.fmt(pending.total) + ", have " + Config.fmt(Balance.coins) + ") - analyzing the AH until you have more");
            state = State.OPEN; cooldown = 10; return;
        }
        failStreak++;
        blocked.put(pendingSig, System.currentTimeMillis());
        warn("Purchase of " + pending.name + " did not go through (" + failStreak + "/4). Listing gone, not enough coins, or wrong confirm button?");
        if (failStreak >= 4) { halt("Purchases keep failing - check your coins, inventory and confirm button name"); return; }
        state = State.OPEN; cooldown = 10;
    }

    public static void tick(Minecraft mc) {
        if (!running) return;
        if (mc.player == null || mc.getConnection() == null) { offline = true; status = "Disconnected - waiting"; return; }
        if (offline) { offline = false; state = State.OPEN; cooldown = 100; status = "Reconnected"; return; }
        if (cooldown > 0) { cooldown--; return; }

        switch (state) {
            case OPEN -> {
                if (needBalance(mc)) return;
                sawUnaffordable = false;
                pages.clear(); page = 0; wait = 0; sortOk = false; sortFailed = false; sortTries = 0; sortLastSel = -1; sortClickWait = 0; expectChange = false; endOfList = false; pageWait = 0; status = "Opening AH";
                // fast cycles only look at the first few pages; a full scan (for price stats) runs every fullScanMinutes
                fullScan = analyzing || analyzeOnly || waitingFunds || resellScanPending || !Config.I.buyEnabled || (!Config.I.autoSort && Config.I.snipePages >= Config.I.maxPages)
                    || System.currentTimeMillis() - lastFull > Config.I.fullScanMinutes * 60_000L;
                scanLimit = analyzeOnly ? 1000 : fullScan ? Config.I.maxPages : Math.max(1, Config.I.autoSort ? Config.I.newPages : Config.I.snipePages);
                Compat.command(mc, Config.I.command);
                state = State.SCAN; cooldown = Config.I.delayTicks * 2;
            }
            case SCAN -> {
                if (!(Compat.screen(mc) instanceof ContainerScreen gs)) { if (++wait > 80) state = State.OPEN; return; }
                wait = 0;
                ChestMenu h = gs.getMenu();
                if (page == 0 && Config.I.autoSort && !sortOk) {
                    int sb = findButton(h, Config.I.sortName);
                    int[] ss = sb >= 0 ? sortState(h.getSlot(sb).getItem()) : null;
                    if (ss == null) { sortOk = true; sortFailed = true; }
                    else if (ss[0] == ss[1]) sortOk = true;
                    else if (ss[0] == sortLastSel && ++sortClickWait <= 30) return;   // our last click has not shown up yet
                    else if (sortTries++ < 7) { sortLastSel = ss[0]; sortClickWait = 0; click(mc, h, sb); cooldown += 6; return; }
                    else { sortOk = true; sortFailed = true; }
                    if (sortFailed && !sortWarned) { sortWarned = true; warn("Could not set the AH sort to \"" + Config.I.sortTarget + "\" - scanning every page instead (slow). Check sortName / sortTarget in config/snipe.json."); }
                }
                if (sortFailed && !fullScan) scanLimit = Config.I.maxPages;
                int size = h.getRowCount() * 9;
                List<Market.Listing> list = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    ItemStack st = h.getSlot(i).getItem();
                    if (st.isEmpty()) continue;
                    Market.Listing l = listing(st);
                    if (l != null) { list.add(l); slots.add(i); }
                }
                String psig = String.join(",", list.stream().map(Sniper::sig).toList());
                if (expectChange) {
                    // the server may not have sent the next page yet: wait for the contents to change instead of re-reading the old page
                    if ((list.isEmpty() || psig.equals(prevSig)) && ++pageWait <= 30) return;
                    if (psig.equals(prevSig)) endOfList = true;
                    expectChange = false; pageWait = 0;
                }
                pages.put(page, list);
                int seen = 0; for (var p : pages.values()) seen += p.size();
                String mode = resellScanPending ? "Rescanning AH for the resale price" : waitingFunds ? "Waiting for coins (need " + Config.fmt(needed) + ", have " + Config.fmt(Balance.coins) + ") - analyzing" : analyzeOnly || analyzing ? "Analyzing" : Config.I.buyEnabled ? "Sniping" : "Tracking";
                status = mode + " - page " + (page + 1) + ", " + seen + " listings";

                if (Config.I.alerts) for (Market.Listing l : list) alertIfGood(l, page);

                boolean buyNow = Config.I.buyEnabled && !analyzeOnly && !waitingFunds && !resellScanPending;   // Snipe List items are bought even during the first analysis pass
                for (Market.Listing l : list) diagnose(l, buyNow);
                if (buyNow) {
                    int best = -1; double bestUnit = Double.MAX_VALUE;
                    for (int j = 0; j < list.size(); j++) {
                        Market.Listing l = list.get(j);
                        Config.Entry e = entryFor(l);
                        boolean manual = e != null && (e.each ? l.unit() : l.total()) <= e.maxPrice;
                        if (!manual && !autoOk(l)) continue;
                        if (l.unit() < bestUnit && !blockedWarn(l) && ((manual && Config.I.trustSnipeList) || safe(l)) && worthIt(l) && underLimit(l) && afford(l)) { best = j; bestUnit = l.unit(); }
                    }
                    if (best >= 0) {
                        Market.Listing l = list.get(best);
                        headsUp(l);
                        if (freeSlots(mc) < 1) { halt("Inventory is full - free some space and press Start"); return; }
                        claimedOnce = false;
                        resellRef = lowestOther(l);
                        pendingBefore = countId(mc, l.id()); pendingId = l.id(); pendingSig = sig(l);
                        pending = new Purchases.P();
                        pending.label = l.label(); pending.name = l.name(); pending.key = l.key(); pending.count = l.count(); pending.total = l.total(); pending.time = System.currentTimeMillis();
                        Market.Snap bs = Market.latest(l.key());
                        if (bs != null && bs.avg > 0) {
                            say(Chat.deal(l, bs, page, true));
                            if (Config.I.dealSound) Compat.sound(mc, Chat.tier(Math.round((1 - l.unit() / bs.avg) * 100)).sound());
                        } else msg("Buying " + l.label() + " x" + l.count() + " for " + Config.fmt(l.total()));
                        click(mc, h, slots.get(best));
                        state = State.CONFIRM; wait = 0;
                        return;
                    }
                }
                int next = findButton(h, Config.I.nextName);
                if (next >= 0 && page < scanLimit && !endOfList) { prevSig = psig; expectChange = true; pageWait = 0; page++; click(mc, h, next); cooldown += 2; return; }

                List<Market.Listing> all = new ArrayList<>();
                for (var p : pages.values()) all.addAll(p);
                if (sawUnaffordable && !waitingFunds && Config.I.checkBalance && !analyzeOnly && !analyzing) {
                    waitingFunds = true;
                    warn("Found deals but not enough coins (need " + Config.fmt(needed) + ", have " + Config.fmt(Balance.coins) + "). I'll keep analyzing the AH and resume when you have enough.");
                }
                if (fullScan) { Market.commit(all); lastFull = System.currentTimeMillis(); }
                mc.player.closeContainer();
                if (resellScanPending) {
                    resellScanPending = false;
                    state = State.RS_FIND; tries = 0; cooldown = 10; status = "Pricing " + pending.name;
                    return;
                }
                if (analyzeOnly) {
                    running = false; analyzeOnly = false;
                    status = "Analysis done (" + all.size() + " listings)";
                    msg("Analysis complete: scanned " + (endOfList ? page : page + 1) + " pages, " + all.size() + " listings, "
                        + Market.d.latest.size() + " items now have a fresh lowest BIN / average / median");
                    if (next >= 0 && page >= scanLimit && !endOfList) warn("Stopped at the safety limit of " + scanLimit + " pages - there may be more.");
                    Market.save();
                    return;
                }
                if (analyzing) { analyzing = false; msg("Analysis complete - sniping now"); }
                state = State.WAIT; cooldown = Config.I.refreshTicks; status = "Scan done (" + all.size() + " listings)";
            }
            case CONFIRM -> {
                if (Compat.screen(mc) instanceof ContainerScreen gs) {
                    int c = findButton(gs.getMenu(), Config.I.confirmName);
                    if (c >= 0) { click(mc, gs.getMenu(), c); bought(mc); return; }
                }
                if (++wait > 20) bought(mc);
            }
            case VERIFY -> {
                if (countId(mc, pendingId) > pendingBefore) { purchaseOk(mc); return; }
                if (++wait > 30) {
                    if (Config.I.claimFallback && !claimedOnce) { startClaim(1); return; }
                    purchaseFailed(mc);
                }
            }
            case WAIT -> {
                if (Config.I.claimFallback && Config.I.claimMinutes > 0 && Config.I.resellOn()
                    && System.currentTimeMillis() - lastClaim > Config.I.claimMinutes * 60_000L && freeSlots(mc) > 0) { startClaim(0); return; }
                state = State.OPEN;
                if (Config.I.resellOn()) {
                    long now = System.currentTimeMillis();
                    for (Purchases.P p : Purchases.list) {
                        if (p.status.startsWith("held") && p.retries < 6 && now - p.lastTry > Config.I.sweepMinutes * 60_000L) {
                            p.retries++; p.lastTry = now; pending = p; resellRef = 0; claimedOnce = false;
                            state = State.RS_FIND; tries = 0; status = "Retrying held item " + p.name;
                            break;
                        }
                    }
                }
            }

            case RS_FIND -> {
                if (Compat.screen(mc) != null) mc.player.closeContainer();
                int found = -1;
                for (int i = 0; i < 36 && found < 0; i++) {
                    ItemStack s = Compat.invItem(mc, i);
                    if (!s.isEmpty() && s.getHoverName().getString().equalsIgnoreCase(pending.name)) found = i;
                }
                if (found < 0) {
                    if (++tries > 10) {
                        if (Config.I.claimFallback && !claimedOnce) { startClaim(2); return; }
                        pending.status = "bought (not found in inventory)"; Purchases.save(); state = State.OPEN;
                    }
                    else cooldown = 10;
                    return;
                }
                ItemStack fs = Compat.invItem(mc, found);
                sellCount = fs.getCount();
                rsId = BuiltInRegistries.ITEM.getKey(fs.getItem()).getPath();
                if (Config.I.resellRescan && System.currentTimeMillis() - lastFull > Math.max(10, Config.I.resellFreshSec) * 1000L) {
                    resellScanPending = true; state = State.OPEN; cooldown = 5;
                    status = "Rescanning AH before relisting " + pending.name;
                    return;
                }
                double price = resellPrice();
                if (price <= 0) {
                    pending.status = price < 0 ? "held (resell not profitable)" : "held (no price data)";
                    Purchases.save(); msg(pending.name + ": " + pending.status); state = State.OPEN; return;
                }
                pending.listedFor = price;
                Compat.holdSlot(mc, found);
                state = State.RS_SEND; cooldown = 6; status = "Relisting " + pending.name;
            }
            case RS_SEND -> {
                rsBefore = countId(mc, rsId);
                Compat.command(mc, Config.I.sellCommand.replace("{price}", String.valueOf((long) pending.listedFor)));
                pending.status = "listing"; Purchases.save();
                Market.Snap rs = Market.latest(pending.key);
                msg("Relisting " + pending.name + " for " + Config.fmt(pending.listedFor)
                    + (rs != null ? "  (market now: lowest " + Config.fmt(rs.low) + ", median " + Config.fmt(rs.median) + " each, " + rs.listings + " listed)" : ""));
                state = State.RS_CONFIRM; wait = 0; cooldown = Config.I.delayTicks * 3;
            }
            case RS_CONFIRM -> {
                if (Compat.screen(mc) instanceof ContainerScreen gs) {
                    int c = findButton(gs.getMenu(), Config.I.confirmName);
                    if (c >= 0) { click(mc, gs.getMenu(), c); cooldown += 10; state = State.RS_VERIFY; wait = 0; return; }
                }
                if (++wait > 20) { state = State.RS_VERIFY; wait = 0; }
            }
            case RS_VERIFY -> {
                if (countId(mc, rsId) < rsBefore) {
                    sellFails = 0; pending.status = "listed"; Purchases.save();
                    state = State.OPEN; cooldown = 10; return;
                }
                if (++wait > 30) {
                    sellFails++; pending.listedFor = 0; pending.status = "held (sell failed)"; Purchases.save();
                    warn("Listing " + pending.name + " failed (" + sellFails + "/3). AH listing limit reached or wrong sell command?");
                    if (sellFails >= 3) { halt("Listings keep failing - AH listing limit or wrong sell command"); return; }
                    if (Compat.screen(mc) != null) mc.player.closeContainer();
                    state = State.OPEN; cooldown = 10;
                }
            }

            case BAL_WAIT -> {
                if (Balance.updated > balAsk) { balFails = 0; fundsCheck(); state = State.OPEN; return; }
                if (++wait > 40) {
                    if (++balFails >= 3) { balFailed = true; warn("Could not read your balance from /" + Config.I.balCommand + " (looking for text like \"You have 1,000 Coins\"). Balance checking is off until you press Start again - fix balRegex in config/snipe.json."); }
                    state = State.OPEN;
                }
            }
            case CLAIM_OPEN -> {
                if (freeSlots(mc) < 1) {
                    if (claimReason == 0) { state = State.OPEN; cooldown = 10; return; }
                    halt("Inventory is full - free some space; your item may be waiting in the AH Claim Items menu"); return;
                }
                if (Compat.screen(mc) != null) mc.player.closeContainer();
                Compat.command(mc, Config.I.command);
                state = State.CLAIM_CLICK; wait = 0; cooldown = Config.I.delayTicks * 2;
            }
            case CLAIM_CLICK -> {
                if (!(Compat.screen(mc) instanceof ContainerScreen gs)) { if (++wait > 80) claimDone(mc); return; }
                ChestMenu h = gs.getMenu();
                if (Compat.title(gs).toLowerCase().contains("claim")) { state = State.CLAIM_TAKE; wait = 0; return; }
                int c = findButton(h, Config.I.claimName);
                if (c < 0) { if (++wait > 20) { warn("No \"" + Config.I.claimName + "\" button found in the AH (claimName in config/snipe.json)"); claimDone(mc); } return; }
                click(mc, h, c); cooldown += 6; state = State.CLAIM_TAKE; wait = 0;
            }
            case CLAIM_TAKE -> {
                if (!(Compat.screen(mc) instanceof ContainerScreen gs) || !Compat.title(gs).toLowerCase().contains("claim")) { if (++wait > 60) claimDone(mc); return; }
                ChestMenu h = gs.getMenu();
                int slot = nextClaimSlot(h);
                if (slot < 0) { if (++wait >= 8) claimDone(mc); return; }
                if (freeSlots(mc) < 1) { claimDone(mc); halt("Inventory is full - free some space; items are waiting in the AH Claim Items menu"); return; }
                Compat.shiftClick(mc, h, slot); claimClicks++; wait = 0; cooldown = Config.I.delayTicks;
                if (claimClicks > 45) claimDone(mc);
            }
        }
    }
}
