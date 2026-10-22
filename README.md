# AutoRynek (Fabric 1.21.4) – wersja 0.1

## Budowanie
1. Zainstaluj JDK 21.
2. Brakuje `gradlew` – skopiuj go (`gradlew`, `gradlew.bat`, folder `gradle/`) z szablonu
   https://fabricmc.net/develop/template/ albo uruchom `gradle wrapper --gradle-version 8.12`.
3. `./gradlew build` → gotowy plik w `build/libs/autorynek-0.1.0.jar` (nie `-sources`).
4. Wrzuć do `mods/` razem z Fabric API dla 1.21.4.
5. W IntelliJ: otwórz folder jako projekt Gradle, potem `./gradlew genSources` i `runClient`.

## Obsługa
- **L** w dowolnym oknie z ekwipunkiem – dodaje przedmiot pod kursorem (id + nazwa + enchanty).
- **J** w oknie rynku – zapisuje zrzut slotów do `config/autorynek-dump.txt` (numery slotów + lore z ceną).
- `/autorynek start | analyze | stop | reset | resetstats`
- `/autorynek margin <nr> <kwota>` – marża przedmiotu (nr jak w HUD)
- `/autorynek query <nr> <tekst>` – tekst używany w `/ah search`
- `/autorynek remove <nr>`

## Kalibracja (obowiązkowa)
Otwórz `/ah search coś`, naciśnij **J**, odczytaj z `autorynek-dump.txt` numery slotów i wpisz w
`config/autorynek.json` (sekcja `settings`): `sortButtonSlot`, `sortLowestSlot`, `refreshSlot`,
`confirmSlot`, `marketTitleKeyword`, a gdy cena nie jest odczytywana – `pricePattern`.
`successKeywords` = fragmenty wiadomości serwera po udanym zakupie (małe litery).

## Wzór ceny skupu
`cena_skupu = floor(najniższa_cena * 0.97) - marża`

## Jar bez instalowania czegokolwiek (GitHub Actions)
1. Załóż puste repozytorium na GitHubie i wrzuć do niego zawartość tego folderu (razem z `.github`).
2. Zakładka **Actions** → workflow `build` → po zakończeniu pobierz artefakt `autorynek-jar`.
3. W środku użyj pliku `autorynek-0.1.0.jar` (nie `-sources`).
