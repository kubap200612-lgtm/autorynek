package pl.autorynek.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import pl.autorynek.data.AutoRynekConfig;
import pl.autorynek.data.AutoRynekConfig.Settings;
import pl.autorynek.data.Stats;
import pl.autorynek.data.WatchedItem;

import java.util.List;
import java.util.Locale;

/**
 * Maszyna stanow bota. Dziala w kazdym ticku klienta (20 razy/s).
 * Tryby: ANALYSIS (liczenie cen skupu) -> BUYING (petla odswiezania i kupowania).
 */
public class MarketBot {
    public enum Mode { OFF, ANALYSIS, BUYING }

    private enum Step { OPEN, WAIT_SCREEN, SETTLE, SORT_BTN, SORT_OPT, SCAN, WAIT_CONFIRM }

    private record Offer(int slot, long price) {}

    private final AutoRynekConfig cfg;

    private Mode mode = Mode.OFF;
    private Step step = Step.OPEN;
    private boolean analysisOnly;

    private int delay;
    private int waitTicks;
    private int analysisIndex;
    private int buyIndex;
    private int refreshCount;
    private int baseSyncId;
    private long cycleStartNs;
    private long clickNs;
    private long lastAnalysisMs;
    private long lastAfkMs = System.currentTimeMillis();

    private WatchedItem current;
    private WatchedItem pendingItem;
    private long pendingPrice;
    private boolean awaitingResult;
    private int pendingTicks;

    private boolean afkActive;
    private int afkTicks;

    public MarketBot(AutoRynekConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ API

    public Mode getMode() {
        return mode;
    }

    public boolean isRunning() {
        return mode != Mode.OFF;
    }

    public void start(boolean analysisOnly) {
        if (cfg.watchlist.isEmpty()) {
            stats().log("Lista pusta - dodaj przedmioty klawiszem L");
            return;
        }
        this.analysisOnly = analysisOnly;
        mode = Mode.ANALYSIS;
        analysisIndex = 0;
        step = Step.OPEN;
        delay = 0;
        waitTicks = 0;
        afkActive = false;
        lastAfkMs = System.currentTimeMillis();
        stats().log(analysisOnly ? "Start: tylko analiza rynku" : "Start: analiza + skupowanie");
    }

    public void stop(String reason) {
        if (mode != Mode.OFF) stats().log("Stop: " + reason);
        mode = Mode.OFF;
        afkActive = false;
        awaitingResult = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) mc.options.jumpKey.setPressed(false);
    }

    /** Reset rynku: ponowne otwarcie /ah search od zera. */
    public void resetMarket() {
        if (mode == Mode.OFF) return;
        step = Step.OPEN;
        delay = 0;
        waitTicks = 0;
        stats().log("Reset rynku (reczny)");
    }

    /** Wywolywane dla kazdej wiadomosci systemowej z serwera. */
    public void onGameMessage(String message) {
        if (!awaitingResult) return;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String kw : cfg.settings.successKeywords) {
            if (!kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                recordPurchase();
                return;
            }
        }
    }

    // ----------------------------------------------------------------- tick

    public void tick(MinecraftClient mc) {
        if (mode == Mode.OFF) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            stop("rozlaczono z serwerem");
            return;
        }
        if (tickAfk(mc)) return;
        if (awaitingResult && ++pendingTicks > 60) {
            awaitingResult = false;
            stats().log("Brak potwierdzenia zakupu");
        }
        if (delay > 0) {
            delay--;
            return;
        }
        if (mode == Mode.ANALYSIS) tickAnalysis(mc);
        else tickBuying(mc);
    }

    // ------------------------------------------------------------- anty-AFK

    /** @return true gdy anty-AFK aktualnie przejmuje sterowanie */
    private boolean tickAfk(MinecraftClient mc) {
        if (!afkActive) {
            long intervalMs = cfg.settings.afkIntervalSeconds * 1000L;
            if (System.currentTimeMillis() - lastAfkMs < intervalMs) return false;
            if (step != Step.OPEN) return false; // czekamy na bezpieczny moment miedzy cyklami
            afkActive = true;
            afkTicks = 0;
            if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
            stats().log("Anty-AFK: wyjscie z rynku + skok");
        }
        afkTicks++;
        if (afkTicks == 6 || afkTicks == 14) mc.options.jumpKey.setPressed(true);
        if (afkTicks == 9 || afkTicks == 17) mc.options.jumpKey.setPressed(false);
        if (afkTicks >= 30) {
            afkActive = false;
            lastAfkMs = System.currentTimeMillis();
            return false;
        }
        return true;
    }

    // --------------------------------------------------- wspolne: otwarcie GUI

    /** Otwiera /ah search, czeka na GUI i sortuje. @return true gdy gotowe do skanowania (step >= SCAN). */
    private boolean prepare(MinecraftClient mc, WatchedItem w) {
        Settings s = cfg.settings;
        switch (step) {
            case OPEN -> {
                baseSyncId = mc.player.currentScreenHandler.syncId;
                cycleStartNs = System.nanoTime();
                if (mode == Mode.BUYING) stats().marketResets++;
                mc.getNetworkHandler().sendChatCommand("ah search " + w.searchQuery);
                waitTicks = 0;
                delay = s.commandCooldownTicks;
                step = Step.WAIT_SCREEN;
            }
            case WAIT_SCREEN -> {
                GenericContainerScreenHandler h = container(mc, false);
                if (h != null && h.syncId != baseSyncId) {
                    waitTicks = s.settleTicks;
                    step = Step.SETTLE;
                } else if (++waitTicks > 100) {
                    stats().log("Brak odpowiedzi na /ah search");
                    waitTicks = 0;
                    delay = 40;
                    step = Step.OPEN;
                }
            }
            case SETTLE -> {
                if (--waitTicks <= 0) step = Step.SORT_BTN;
            }
            case SORT_BTN -> {
                if (s.sortButtonSlot >= 0) {
                    click(mc, s.sortButtonSlot);
                    delay = s.actionDelayTicks + 2;
                }
                step = Step.SORT_OPT;
            }
            case SORT_OPT -> {
                if (s.sortLowestSlot >= 0) {
                    click(mc, s.sortLowestSlot);
                    delay = s.settleTicks + 2;
                }
                waitTicks = 0;
                step = Step.SCAN;
            }
            default -> {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- analiza

    private void tickAnalysis(MinecraftClient mc) {
        if (analysisIndex >= cfg.watchlist.size()) {
            finishAnalysis(mc);
            return;
        }
        WatchedItem w = cfg.watchlist.get(analysisIndex);
        if (!prepare(mc, w)) return;
        if (step != Step.SCAN) return;

        GenericContainerScreenHandler h = container(mc, true);
        if (h == null) {
            if (++waitTicks > 40) {
                waitTicks = 0;
                step = Step.OPEN;
            }
            return;
        }
        waitTicks = 0;

        Offer o = findOffer(h, w, Long.MAX_VALUE, cfg.settings.analysisRequireMatch);
        if (o == null) {
            w.marketPrice = 0;
            w.buyPrice = 0;
            stats().log("Analiza: brak ofert - " + w.label);
        } else {
            w.marketPrice = o.price();
            w.recalc(cfg.settings.taxMultiplier);
            stats().log("Analiza: " + w.label + " rynek " + o.price() + " -> skup " + w.buyPrice);
        }
        cfg.save();
        analysisIndex++;
        step = Step.OPEN;
        delay = 10;
    }

    private void finishAnalysis(MinecraftClient mc) {
        if (mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        lastAnalysisMs = System.currentTimeMillis();
        stats().log("Analiza zakonczona");
        if (analysisOnly) {
            stop("analiza zakonczona");
            return;
        }
        mode = Mode.BUYING;
        step = Step.OPEN;
        buyIndex = 0;
        delay = 10;
    }

    // -------------------------------------------------------------- kupowanie

    private void tickBuying(MinecraftClient mc) {
        Settings s = cfg.settings;
        if (step == Step.OPEN && System.currentTimeMillis() - lastAnalysisMs > s.reanalysisMinutes * 60_000L) {
            stats().log("Ponowna analiza rynku");
            mode = Mode.ANALYSIS;
            analysisOnly = false;
            analysisIndex = 0;
            return;
        }
        if (step == Step.OPEN) {
            List<WatchedItem> active = cfg.watchlist.stream().filter(w -> w.buyPrice > 0).toList();
            if (active.isEmpty()) {
                stop("brak przedmiotow z cena skupu");
                return;
            }
            current = active.get(Math.floorMod(buyIndex++, active.size()));
            refreshCount = 0;
        }
        if (current == null) {
            step = Step.OPEN;
            return;
        }
        if (!prepare(mc, current)) return;
        switch (step) {
            case SCAN -> scanForPurchase(mc);
            case WAIT_CONFIRM -> waitConfirm(mc);
            default -> {}
        }
    }

    private void scanForPurchase(MinecraftClient mc) {
        Settings s = cfg.settings;
        GenericContainerScreenHandler h = container(mc, true);
        if (h == null) {
            if (++waitTicks > 40) {
                waitTicks = 0;
                step = Step.OPEN;
            }
            return;
        }
        waitTicks = 0;
        stats().lastRefreshMs = (System.nanoTime() - cycleStartNs) / 1_000_000L;

        Offer o = findOffer(h, current, current.buyPrice, true);
        if (o != null) {
            stats().attempts++;
            pendingItem = current;
            pendingPrice = o.price();
            clickNs = System.nanoTime();
            baseSyncId = h.syncId;
            click(mc, o.slot());
            if (s.confirmSlot >= 0) {
                waitTicks = 0;
                step = Step.WAIT_CONFIRM;
            } else {
                beginPending();
                step = Step.OPEN;
                delay = 10;
            }
            return;
        }

        if (s.refreshSlot >= 0 && ++refreshCount < s.refreshesPerItem) {
            cycleStartNs = System.nanoTime();
            click(mc, s.refreshSlot);
            delay = s.actionDelayTicks;
        } else {
            step = Step.OPEN;
        }
    }

    private void waitConfirm(MinecraftClient mc) {
        GenericContainerScreenHandler h = container(mc, false);
        if (h != null && h.syncId != baseSyncId) {
            stats().lastConfirmMs = (System.nanoTime() - clickNs) / 1_000_000L;
            click(mc, cfg.settings.confirmSlot);
            beginPending();
            step = Step.OPEN;
            delay = 10;
        } else if (++waitTicks > cfg.settings.confirmTimeoutTicks) {
            stats().log("Brak okna potwierdzenia");
            waitTicks = 0;
            step = Step.OPEN;
        }
    }

    private void beginPending() {
        pendingTicks = 0;
        if (cfg.settings.successKeywords.isEmpty()) {
            recordPurchase(); // brak slow kluczowych - liczymy zakup od razu po kliknieciu
        } else {
            awaitingResult = true;
        }
    }

    private void recordPurchase() {
        awaitingResult = false;
        if (pendingItem == null) return;
        Stats st = stats();
        st.purchases++;
        st.spent += pendingPrice;
        st.expectedRevenue += Math.round(pendingItem.marketPrice * cfg.settings.taxMultiplier);
        st.history.add(new Stats.HistoryEntry(System.currentTimeMillis(), pendingItem.label, pendingPrice, pendingItem.marketPrice));
        while (st.history.size() > 100) st.history.remove(0);
        st.log("KUPIONO " + pendingItem.label + " za " + pendingPrice);
        cfg.save();
    }

    // --------------------------------------------------------------- helpery

    private Offer findOffer(GenericContainerScreenHandler h, WatchedItem w, long maxPrice, boolean requireMatch) {
        Settings s = cfg.settings;
        int last = Math.min(s.lastListingSlot, h.getRows() * 9 - 1);
        Offer best = null;
        for (int i = Math.max(0, s.firstListingSlot); i <= last; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (requireMatch && !w.key.matches(st)) continue;
            long price = PriceParser.parse(st);
            if (price <= 0 || price > maxPrice) continue;
            if (best == null || price < best.price()) best = new Offer(i, price);
        }
        return best;
    }

    private void click(MinecraftClient mc, int slot) {
        if (slot < 0) return;
        GenericContainerScreenHandler h = container(mc, false);
        if (h == null || slot >= h.slots.size()) return;
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    /** @param market true = dodatkowo wymagaj, aby tytul okna zawieral marketTitleKeyword */
    private GenericContainerScreenHandler container(MinecraftClient mc, boolean market) {
        if (!(mc.currentScreen instanceof GenericContainerScreen scr)) return null;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return null;
        String kw = cfg.settings.marketTitleKeyword;
        if (market && kw != null && !kw.isBlank()
                && !scr.getTitle().getString().toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return h;
    }

    private Stats stats() {
        return cfg.stats;
    }
}
