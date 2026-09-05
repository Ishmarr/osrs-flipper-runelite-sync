# Afhandeling P2-audit

Versie 5.2.31 behandelt de twaalf Medium-punten (5–16) uit de audit, bovenop de P1-fixes van v5.2.30. Publicatie verloopt via de eigen GitHub-repository. De lokale RuneLite-testclient laadt bijgewerkte code bij een herstart.

| Auditpunt | Opgelost gedrag | Belangrijkste bestanden en regressietests |
| --- | --- | --- |
| 5. Onnodige buy-limitqueries | `guidance_updated` markeert een batch niet meer als gewijzigde buy limits, ook niet als het event cumulatieve fills herhaalt. Echte mutaties en herhaalde bevestigingen behouden hun refresh. | `OsrsFlipperSyncPlugin.java`, `OverviewRefreshContractTest.java` |
| 6. Beschadigde cache en outbox | Legacycaches en nieuwe afgeleide cachevelden worden apart ingelezen. Intacte journal-events en cashopdrachten blijven behouden en verzendbaar. Onleesbare duurzame opdrachten stoppen de verzending zonder het bronbestand te overschrijven. | `OsrsFlipperSyncPlugin.java`, `SyncStorageRegressionTest.java` |
| 7. Verouderde prijsregel | Wiki-/overview-antwoorden vernieuwen een open editor. De klik herberekent advies uit de huidige lokale data en controleert item, zijde, slot en lifecycle/context. | `OsrsFlipperSyncPlugin.java`, `GeQuantityEditorSafetyTest.java` |
| 8. Cashvalidatie | Alleen gehele GP van 0 t/m 2147483647, eventueel met correcte spatiegroepen. Tekens, decimalen en suffixen worden afgewezen; ongeldige invoer veroorzaakt geen opslagaanroep. Ook de interne opdracht valideert de grens. | `OsrsFlipperSyncPanel.java`, `PendingCashUpdate.java`, `CashBalanceInputTest.java` |
| 9. HTTP 2xx-validatie | Cash, status en heartbeat vereisen een volledig succesvol antwoord volgens het bestaande Worker-contract. Lege/HTML/afgebroken antwoorden en verkeerde identiteit activeren de bestaande back-off; een onbevestigde cashopdracht behoudt haar ID. | `WorkerResponseValidation.java`, `OsrsFlipperSyncPlugin.java`, `WorkerResponseValidationTest.java`, `WorkerAcknowledgementRecoveryTest.java`, `CashUpdateRetryTest.java` |
| 10. Verkoop koppelt toekomstige voorraad | De fallback respecteert verwervingstijd, verkoopreserveringen en reeds verkochte voorraad. Legacyherstel gebruikt beschikbare cyclevoorraad wanneer die bekend is. | `OfferGuidanceResolver.java`, `OsrsFlipperSyncPlugin.java`, `SellStockLinkRegressionTest.java` |
| 11. GE-belastingvrijstellingen | Belasting, winst en break-even gebruiken item-ID's en een lokale vrijstellingslijst. Afronding gebruikt gehele getallen; er zijn geen extra runtimeverzoeken nodig. | `SessionStatsTracker.java`, `OfferGuidanceResolver.java`, plugin/panel-callsites, `SessionStatsTrackerTest.java` |
| 12. Verouderde Wiki-callbacks | Requests zijn annuleerbaar en gebonden aan lifecycle, accountcontext en exacte Call. Een oud antwoord kan geen nieuwe prijzen of in-flight-status overschrijven. | `OsrsFlipperSyncPlugin.java`, `PluginLifecycleRegressionTest.java` |
| 13. Lifecycle en threads | Start-/stopopslag en mutable synchronisatiestatus lopen op de clientthread. Stop sluit de requestgate onmiddellijk; Swingwerk blijft op de EDT. Oude panelacties worden afgewezen. | `OsrsFlipperSyncPlugin.java`, `PluginLifecycleRegressionTest.java` |
| 14. Token in DEBUG-logs | Nieuwe tokens gaan naar bestanden met uitsluitend toegang voor de eigenaar, buiten ConfigManager en RuneLite-configsync. Een logfilter beschermt legacytokenregels; de normale starter gebruikt geen `--debug`. | `PairingCredentialStore.java`, `TokenLogFilter.java`, plugin/starter, `PairingCredentialSecurityTest.java` |
| 15. Token volgt gewijzigd adres | De opgeslagen token is gebonden aan RuneLite-profiel, genormaliseerde HTTPS-origin, eigenaar en apparaat. Een andere host, poort, scheme, eigenaar of profiel krijgt geen Authorization-header en geen geauthenticeerde Call. | `PairingCredentials.java`, `OsrsFlipperSyncPlugin.java`, `PairingCredentialSecurityTest.java` |
| 16. Reproduceerbare build/start | RuneLite 1.12.37 vastgezet, dependencylocks, geen mavenLocal, gecontroleerde Gradle-distributiehash, strikte testdiscovery en een checkoutrelatieve Windows-starter met lokale JDK-detectie als JAVA_HOME/PATH ontbreken. Gradle vereist JDK 17+; pluginbytecode blijft Java 11. | `build.gradle`, `gradle.lockfile`, `gradle/wrapper/gradle-wrapper.properties`, `start-runelite-testclient.cmd`, `find-local-jdk.ps1`, README/installatiehandleiding |

## Cloudflare Free en publicatie

Het project blijft ontworpen voor twee gebruikers op Cloudflare Free. Deze fixes voegen geen periodieke serververzoeken, grotere batches, databases, betaalde diensten of Worker-wijzigingen toe. Prijsklikken, belastingvrijstellingen en beveiligingscontroles zijn lokaal. Guidance-only updates veroorzaken minder geforceerde buy-limitreads. De bestaande serialisatie van Worker-verzoeken en back-off blijven behouden.

De RuneLite Plugin Hub is geen publicatiebestemming. Het expliciete verbod in `AGENTS.md` blijft gelden.

## Eenmalige overgang naar lokale tokenopslag

Een oude ConfigManager-token heeft geen betrouwbare vastgelegde pairing-origin. De plugin neemt daarom niet aan dat het huidige instelbare adres de oorspronkelijke uitgever is. Bij de eerste start wordt die oude configuratietoken verwijderd en vraagt het paneel om opnieuw te koppelen met een nieuwe code. De nieuwe token blijft lokaal en blijft bij volgende herstarts geldig voor dezelfde binding.

Laat vóór upgraden de vorige client zijn wachtrij afwerken. Bestaande journals blijven in hun oorspronkelijke context bewaard; een nieuwe apparaatidentiteit verstuurt geen geschiedenis van de vorige. Deze update wist geen bestaande logbestanden en kan historische tokenvermeldingen niet ongedaan maken.

## Verificatie

Resultaat op 5 september 2026: **343 tests in 43 suites, 0 failures, 0 errors, 0 skipped**. Dat zijn 49 tests meer dan de P1-baseline van 294. Zowel de build die de locks schreef als de gewone offline build met die locks is geslaagd. `git diff --check` is geslaagd; de Worker-repository is ongewijzigd.

Ook de releasebuild met versienummer 5.2.31 slaagt met dezelfde 343 tests. De Windows-starter is zonder RuneLite te starten gecontroleerd met automatische JDK-detectie, expliciete JAVA_HOME, Java op PATH en een ongeldige JAVA_HOME; de drie geldige routes slagen en de ongeldige route eindigt met foutcode 1.

De regressies gebruiken geïsoleerde tijdelijke opslag, RuneLite-testfixtures en nagebootste HTTP-antwoorden. Ze omvatten verloren antwoorden, herstarts, account-/profielwissels, corrupte caches, oude callbacks, daadwerkelijke Windows-bestandsrechten en ontbrekende/ongeldige Worker-velden. Er zijn geen echte trades, accountwijzigingen of netwerkmutaties als test uitgevoerd.

Build: `gradlew.bat build --offline --write-locks`, gevolgd door controle met de vastgelegde dependencylocks. Getest met JDK 26.0.1 en RuneLite 1.12.37. De bestaande Java 11-bytecodegrens blijft actief.

De live RuneLite-client is niet gestart of herstart voor deze test. De build geeft nog de bestaande JDK 26-waarschuwing over Gson 2.8.5 die immutable cashopdrachten reflectief herstelt; dit verhindert de huidige build of hersteltests niet. De door RuneLite beheerde Gson-versie is niet zelfstandig vervangen.

## Regelbronnen

- [Jagex: GE tax en item sink, 2021](https://secure.runescape.com/m=news/grand-exchange-tax--item-sink?oldschool=1).
- [Jagex: GE-taxwijzigingen, 2025](https://secure.runescape.com/m=news/yama-cas--more?oldschool=1).
- [Gradle: officiële distributie- en wrapperchecksums](https://gradle.org/release-checksums/). De lokale wrapper-JAR is gecontroleerd tegen de gepubliceerde SHA-256 voor 9.6.0.
