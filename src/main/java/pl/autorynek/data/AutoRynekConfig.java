package pl.autorynek.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AutoRynekConfig {

    public static class Settings {
        public double taxMultiplier = 0.97;
        public long defaultMargin = 100;

        // --- KALIBRACJA GUI (numery slotow: klawisz J w otwartym GUI zapisuje zrzut) ---
        public int firstListingSlot = 0;     // pierwszy slot z ofertami
        public int lastListingSlot = 44;     // ostatni slot z ofertami
        public int sortButtonSlot = -1;      // przycisk "Sortowanie" (-1 = pomin)
        public int sortLowestSlot = -1;      // opcja "Najnizszej ceny" (-1 = pomin)
        public int refreshSlot = -1;         // przycisk odswiezania (-1 = wysylaj /ah search od nowa)
        public int confirmSlot = -1;         // przycisk "Potwierdz" w oknie zakupu (-1 = brak okna)
        public String marketTitleKeyword = ""; // fragment tytulu okna rynku ("" = dowolne okno)

        // regex ceny w opisie (lore): grupa 1 = liczba, grupa 2 = opcjonalny sufiks k/m/b
        public String pricePattern = "(?i)(?:cena|price)\\D*([0-9][0-9 .,]*)\\s*([kmb])?";
        public List<String> successKeywords = new ArrayList<>(List.of("kupiłeś", "zakupiłeś", "kupiono"));

        public boolean analysisRequireMatch = true;
        public int settleTicks = 3;
        public int actionDelayTicks = 2;
        public int commandCooldownTicks = 8;
        public int confirmTimeoutTicks = 40;
        public int refreshesPerItem = 20;
        public int reanalysisMinutes = 15;
        public int afkIntervalSeconds = 120;
    }

    public Settings settings = new Settings();
    public List<WatchedItem> watchlist = new ArrayList<>();
    public Stats stats = new Stats();

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("autorynek.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static AutoRynekConfig load() {
        try {
            if (Files.exists(PATH)) {
                AutoRynekConfig c = GSON.fromJson(Files.readString(PATH), AutoRynekConfig.class);
                if (c != null) {
                    if (c.settings == null) c.settings = new Settings();
                    if (c.watchlist == null) c.watchlist = new ArrayList<>();
                    if (c.stats == null) c.stats = new Stats();
                    if (c.stats.history == null) c.stats.history = new ArrayList<>();
                    return c;
                }
            }
        } catch (Exception e) {
            System.err.println("[AutoRynek] Nie udalo sie wczytac configu: " + e);
        }
        AutoRynekConfig c = new AutoRynekConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[AutoRynek] Nie udalo sie zapisac configu: " + e);
        }
    }
}
