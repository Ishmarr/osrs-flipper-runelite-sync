# Fase 4 – lokale RuneLite-testclient en eigen GitHub-repository

**Harde instructie van de eigenaar: nooit iets bij de RuneLite Plugin Hub indienen.** Geen Plugin Hub-pull requests, manifestupdates of publicatieaanvragen. Een deploymentverzoek heft dit verbod niet op. Cloudflare moet Free blijven; het project wordt door twee mensen gebruikt.

## Belangrijke release-eigenschappen

- de tijdelijke koppelcode is uit de gewone pluginconfig verwijderd;
- de apparaatnaam is uit de gewone pluginconfig verwijderd;
- het webapp-adres is instelbaar en moet HTTPS gebruiken;
- de verbindingsstatus blijft zichtbaar;
- uitgebreide logging blijft optioneel;
- **Apparaat koppelen** en **Opnieuw synchroniseren** staan als echte knoppen in een eigen RuneLite-zijpaneel;
- tokens staan buiten de RuneLite-config, met lokale bestandstoegang en binding aan profiel, HTTPS-origin, eigenaar en apparaat;
- een oude token zonder betrouwbare origin-binding wordt verwijderd uit de configuratie; koppel in dat geval eenmaal opnieuw;
- de handmatige synchronisatiestatus eindigt na bevestiging op **Synchronisatie voltooid**;
- het RuneLite-icoon is hetzelfde als het app-icoon;
- geserialiseerde aanvragen en exponentiële back-off voorkomen een retry-storm bij Cloudflare 503/1102;
- de Gradle 9-build ondersteunt de RuneLite-ontwikkelstarter in `src/test`, zonder die starter als JUnit-test te behandelen.

## Vereisten vóór publicatie

Voer vóór publicatie `gradlew.bat build` uit met JDK 17 of hoger. De volledige regressiesuite gebruikt een nieuw geïsoleerd RuneLite-profiel onder `build/test-runtime`; de echte configuratie, tokens en `client.log` blijven buiten de tests. De eigen GitHub-repository voert deze build ook automatisch uit bij pushes en pull requests naar `main`.

Controleer de volledige overview-route van request en asynchrone callback tot het Flips-paneel: de volledige lijst, itemselectie en sluiten, vertraagde antwoorden, lege/oude/ongeldige marktdata en automatisch herstel. Controleer daarbij requestaantallen en accountwissels. Test ook apparaatkoppeling, onmiddellijke GE-wijzigingen, volledige snapshots, retries en serverreconciliatie. De onderstaande handmatige stappen gebruiken de lokale RuneLite-testclient met het bestaande profiel.

Een pluginupdate vereist alleen een Worker-update als het API-contract dat expliciet nodig maakt. De auditfixes gebruiken de bestaande Worker; Cloudflare blijft Free voor twee gebruikers.

## Testvolgorde

### 1. Upgrade vanaf een oudere versie

1. Laat de huidige client zijn wachtende synchronisatie afronden en sluit hem.
2. Open dit project in IntelliJ met een JDK van Java 17 of hoger voor Gradle 9.6.0.
3. Start de Gradle-taak `run`.
4. Bij een oude koppeling zonder origin-binding vraagt de status om eenmaal opnieuw te koppelen.
5. Gebruik **Apparaat koppelen** en een nieuwe code uit je eigen webapp. Daarna blijft de nieuwe lokale koppeling bij herstarts behouden. Journals van vorige apparaten worden niet onder een nieuwe apparaatidentiteit verstuurd.

### 2. Nieuwe apparaatkoppeling

1. Trek het testapparaat desgewenst eerst in via de webapp.
2. Klik in het RuneLite-zijpaneel op **Apparaat koppelen**.
3. Maak in de geopende webapp een tijdelijke code.
4. Vul de code in RuneLite in.
5. Controleer dat de status naar `Gekoppeld met ...` gaat.

### 3. Volledige synchronisatie

1. Meld aan op RuneScape.
2. Open de Grand Exchange.
3. Klik op **Opnieuw synchroniseren**.
4. Controleer in **Mijn flips** dat alle acht slots overeenkomen.
5. Controleer dat RuneLite `synchronisatie voltooid` toont.

### 4. Wijzigingen en herstel

1. Plaats een klein koopoffer en controleer de onmiddellijke synchronisatie.
2. Laat het offer gedeeltelijk vullen.
3. Rond de aankoop af en verkoop het item.
4. Laat RuneScape online, maar blokkeer tijdelijk alleen het webapp-/Worker-adres, bijvoorbeeld via het Windows `hosts`-bestand.
5. Plaats of wijzig een GE-offer en herstel daarna de toegang tot de Worker.
6. Controleer dat de lokale wachtrij automatisch wordt afgewerkt en dat de status na de back-off herstelt.
7. Laat RuneLite minstens tien minuten open en controleer heartbeats en de lokale slotcontrole iedere vijf minuten. Een ongewijzigde veiligheidssnapshot gaat maximaal eenmaal per uur naar de Worker; die hoeft binnen deze tien minuten dus niet zichtbaar te zijn.

### 5. Diagnose

Schakel **Uitgebreide logging** alleen tijdelijk in wanneer een test faalt. Controleer `client.log` op pairing, heartbeat, individuele events, volledige snapshots, retries en serververschillen. Schakel logging daarna opnieuw uit.

## Publiceren naar de eigen GitHub-repository

1. Gebruik de bestaande repository [Ishmarr/osrs-flipper-runelite-sync](https://github.com/Ishmarr/osrs-flipper-runelite-sync); controleer dat `origin` hiernaar verwijst.
2. Controleer dat pluginversie, `build.gradle`, `runelite-plugin.properties` en de README dezelfde release beschrijven.
3. Voer de volledige build en regressiesuite uit en bekijk de wijzigingen die in de commit komen. Lokale profielen, tokens, journals, testuitvoer en logs horen niet in de commit.
4. Commit en push de geteste broncode wanneer de eigenaar publicatie heeft toegestaan.
5. Controleer dat de automatische GitHub-build voor die commit slaagt en noteer de volledige commit-hash van 40 tekens.

## De bijgewerkte plugin gebruiken

1. Sluit de actieve RuneLite-testclient nadat wachtende synchronisatie is afgerond.
2. Gebruik de bijgewerkte versie van dit project.
3. Start de Gradle-taak `run`, of gebruik de bestaande lokale testclient-launcher die naar deze projectmap verwijst.
4. Controleer de pluginversie in de opstartlog en de verbindingsstatus in het zijpaneel.
5. Koppel opnieuw wanneer de status dit vraagt: bij de eenmalige tokenmigratie, een ongeldig/ingetrokken apparaat of een ander webapp-adres.

Een push naar de eigen GitHub-repository wijzigt geen reeds geopende RuneLite-client. Nieuwe code wordt bij een herstart geladen. Publicatie via de RuneLite Plugin Hub is verboden.
