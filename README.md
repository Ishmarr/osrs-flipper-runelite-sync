# OSRS Flipper Sync v5.1.0

RuneLite Plugin Hub-versie van de veilige koppeling tussen RuneLite en de OSRS Flip Tracker-webapp.

## Functies

- synchroniseert alle acht Grand Exchange-slots;
- stuurt wijzigingen onmiddellijk door;
- bewaart tijdelijk niet-verzonden gebeurtenissen lokaal en probeert ze opnieuw;
- stuurt periodieke volledige snapshots en heartbeats;
- vergelijkt de lokale toestand met de server en herstelt verschillen automatisch;
- biedt een RuneLite-zijpaneel met **Apparaat koppelen**, **Opnieuw synchroniseren** en **Webapp openen**;
- toont alle actuele GE-slots met itemicoon, koop/verkoopzijde, voortgang, prijs en live timer;
- toont voor actieve items de actuele Wiki instant-buy- en instant-sellprijs rechtstreeks uit de officiële prijsfeed, zonder D1-reads;
- houdt lokaal de winst, ROI, GE-tax, handelsvolume en winst per uur bij voor koop/verkoopfills die in de huidige RuneLite-sessie zijn gezien;
- toont de actuele verbindingsstatus;
- bevat optionele uitgebreide diagnose­logging.

## Oplossingen in v5.0.1

- een handmatige volledige synchronisatie houdt nu expliciet bij dat RuneLite op afronding wacht;
- na bevestiging door de Worker of na een geslaagde servercontrole verschijnt **Synchronisatie voltooid**;
- tijdelijke netwerk- en serverfouten tonen nu een duidelijke retry-status in plaats van blijvend **Volledige synchronisatie gestart...**;
- het Plugin Hub- en zijpaneelicoon gebruikt nu exact het app-icoon van OSRS Flip Scanner.

## Oplossingen in v5.0.2

- alle Worker-aanvragen worden door de plugin geserialiseerd: nooit meerdere status-, heartbeat-, event-, snapshot- of servercontroles tegelijk;
- HTTP 503, 429, time-outs en netwerkfouten krijgen een gedeelde exponentiële back-off van 10, 20, 40, 80 seconden tot maximaal vijf minuten;
- serverstatuscontroles worden niet langer iedere game tick opnieuw verstuurd;
- GE-events en snapshots blijven lokaal bewaard en krijgen voorrang op heartbeats en gewone statuscontroles;
- een statuscontrole overschrijft de tekst van een lopende handmatige synchronisatie niet meer.


## Oplossing in v5.0.3

- de Gradle 9-build faalt niet meer wanneer `src/test` alleen de RuneLite-ontwikkelstarter bevat en geen JUnit-tests;
- de lokale `build`-taak kan daardoor normaal afronden, terwijl de bestaande `run`-taak ongewijzigd blijft werken.

## Nieuw in v5.1.0

- het zijpaneel heeft afzonderlijke tabs voor **Slots**, **Prijzen**, **Stats** en **Sync**;
- slotkaarten worden rechtstreeks uit RuneLite bijgewerkt en veroorzaken geen extra D1-reads;
- marktprijzen komen maximaal eens per vijf minuten per actief item rechtstreeks uit de officiële Wiki-prijsfeed;
- sessiewinst gebruikt alleen werkelijk waargenomen fillverschillen en koppelt verkopen FIFO aan aankopen van hetzelfde item;
- verkopen waarvan de aankoop vóór het openen van de plugin lag, worden bewust niet als verzonnen winst meegerekend;
- gedeeltelijke fills en herhaalde RuneLite-events worden niet dubbel geteld.

## Configuratie

De zichtbare pluginconfig bevat uitsluitend:

- het HTTPS-adres van de webapp;
- de automatisch bijgewerkte verbindingsstatus;
- uitgebreide logging voor diagnose.

Er staat geen gedeelde Cloudflare-secret, Access-token, client-secret of API-key in de broncode of zichtbare configuratie.

Na een geslaagde eenmalige koppeling geeft de Worker een unieke apparaattoken uit. RuneLite bewaart die token verborgen als secret-configwaarde. De token heeft alleen toegang tot de beperkte `/runelite-api/*`-functies van het gekoppelde account en kan via de webapp worden ingetrokken.

## Apparaat koppelen

1. Open het **OSRS Flipper Sync**-zijpaneel in RuneLite.
2. Klik op **Apparaat koppelen**.
3. De webapp opent op Persoonlijke instellingen.
4. Maak daar een tijdelijke code.
5. Vul de code in het RuneLite-venster in.
6. Na koppeling verstuurt de plugin automatisch een volledige slotsnapshot zodra je bent ingelogd.

## Handmatig synchroniseren

Klik in het zijpaneel op **Opnieuw synchroniseren**. RuneLite leest alle acht GE-slots opnieuw en stuurt één gezaghebbende volledige snapshot naar de webapp.

## Privacy

De plugin verstuurt alleen gegevens die nodig zijn voor de GE-synchronisatie, waaronder slotnummer, item-ID en itemnaam, koop/verkoopzijde, prijs, aantallen, offerstatus, eventvolgorde, pluginversie en een lokaal RuneScape-accountkenmerk voor gescheiden opslag.

De plugin verstuurt geen RuneScape-wachtwoord, Jagex-inloggegevens, bankinhoud, inventaris, chatberichten of spelerspositie.

## Ontwikkeltest

1. Open deze map als Gradle-project in IntelliJ IDEA.
2. Gebruik Java 11.
3. Start de Gradle-taak `run`.
4. Test koppeling, volledige synchronisatie, netwerkherstel en periodieke reconciliatie.

## Plugin Hub-publicatie

Voor publicatie moet dit project in een publieke GitHub-repository staan. Test eerst de volledige fase-4-checklist in `FASE-4-INSTALLATIE-EN-PUBLICATIE.md`. Dien daarna in de RuneLite Plugin Hub-repository een manifestbestand in met de repository-URL en de volledige commit-hash.
