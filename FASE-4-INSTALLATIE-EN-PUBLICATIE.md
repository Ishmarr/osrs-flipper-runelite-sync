# Fase 4 – lokale RuneLite-testclient en eigen GitHub-repository

**Harde instructie van de eigenaar: nooit iets bij de RuneLite Plugin Hub indienen.** Geen Plugin Hub-pull requests, manifestupdates of publicatieaanvragen. Een deploymentverzoek heft dit verbod niet op. Cloudflare moet Free blijven; het project wordt door twee mensen gebruikt.

## Belangrijke release-eigenschappen

- de tijdelijke koppelcode is uit de gewone pluginconfig verwijderd;
- de apparaatnaam is uit de gewone pluginconfig verwijderd;
- het webapp-adres is instelbaar en moet HTTPS gebruiken;
- de verbindingsstatus blijft zichtbaar;
- uitgebreide logging blijft optioneel;
- **Apparaat koppelen** en **Opnieuw synchroniseren** staan als echte knoppen in een eigen RuneLite-zijpaneel;
- bestaande v4-apparaattokens blijven behouden doordat configgroep en geheime tokensleutel ongewijzigd zijn;
- de handmatige synchronisatiestatus eindigt na bevestiging op **Synchronisatie voltooid**;
- het RuneLite-icoon is hetzelfde als het app-icoon;
- geserialiseerde aanvragen en exponentiële back-off voorkomen een retry-storm bij Cloudflare 503/1102;
- de Gradle 9-build ondersteunt de RuneLite-ontwikkelstarter in `src/test`, zonder die starter als JUnit-test te behandelen.

## Vereisten vóór publicatie

Gebruik de Worker-versie die bij de huidige RuneLite-release hoort en voer alle stabiliteitstesten uit met de huidige IntelliJ-testclient. Publiceer pas wanneer apparaatkoppeling, onmiddellijke wijzigingen, volledige snapshots, retries en serverreconciliatie betrouwbaar werken.

## Testvolgorde

### 1. Upgrade vanaf v4

1. Sluit de v4-testclient.
2. Open het v5-project in IntelliJ.
3. Start de Gradle-taak `run`.
4. Controleer dat de status automatisch je bestaande gekoppelde account toont.
5. Koppel alleen opnieuw wanneer de status aangeeft dat de token ongeldig of ingetrokken is.

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
7. Laat RuneLite minstens tien minuten open en controleer heartbeats en periodieke snapshots.

### 5. Diagnose

Schakel **Uitgebreide logging** alleen tijdelijk in wanneer een test faalt. Controleer `client.log` op pairing, heartbeat, individuele events, volledige snapshots, retries en serververschillen. Schakel logging daarna opnieuw uit.

## GitHub-repository voorbereiden

1. Maak een publieke repository, bijvoorbeeld `osrs-flipper-runelite-sync`.
2. Plaats alle bestanden uit deze projectmap in de repositoryroot.
3. Vul desgewenst een support- of issueslink aan in de README.
4. Controleer dat `icon.png` maximaal 48 × 72 pixels is.
5. Commit en push de geteste versie.
6. Noteer de volledige commit-hash van 40 tekens.

## De bijgewerkte plugin gebruiken

1. Sluit de actieve RuneLite-testclient nadat wachtende synchronisatie is afgerond.
2. Gebruik de bijgewerkte versie van dit project.
3. Start de Gradle-taak `run`, of gebruik de bestaande lokale testclient-launcher die naar deze projectmap verwijst.
4. Controleer de pluginversie in de opstartlog en de verbindingsstatus in het zijpaneel.
5. Koppel alleen opnieuw wanneer de bestaande koppeling ongeldig of ingetrokken is.

Een push naar de eigen GitHub-repository wijzigt geen reeds geopende RuneLite-client. Nieuwe code wordt bij een herstart geladen. Publicatie via de RuneLite Plugin Hub is verboden.
