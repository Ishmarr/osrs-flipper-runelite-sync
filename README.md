# OSRS Flipper Sync v5.2.14

RuneLite Plugin Hub-versie van de veilige koppeling tussen RuneLite en de OSRS Flip Tracker-webapp.

## Functies

- synchroniseert alle acht Grand Exchange-slots;
- stuurt wijzigingen onmiddellijk door;
- bewaart tijdelijk niet-verzonden gebeurtenissen lokaal en probeert ze opnieuw;
- stuurt periodieke volledige snapshots en heartbeats;
- vergelijkt de lokale toestand met de server en herstelt verschillen automatisch;
- biedt een compact RuneLite-zijpaneel met **Slots**, **Flips**, **Stats** en **Sync**;
- toont alle actuele GE-slots met itemicoon, koop/verkoopzijde, voortgang, prijs en live timer;
- bewaart bij de start van een geadviseerde kooporder het toenmalige verkoopdoel in het lokale slot;
- toont uitsluitend de vijf grootste flipwaardes vanaf 100.000 GP per volledige koop-verkoopcyclus;
- toont per kans advieskoop, adviesverkoop, het relevante aantal en de actuele Wiki instabuy en instasell;
- haalt winst, ROI, GP/u, GE-tax, handelsvolume en afgeronde flips voor vandaag, deze maand en totaal uit de webapp;
- toont per periode welke items de gerealiseerde winst en het verlies hebben veroorzaakt;
- laat die itemopbrengsten sorteren op **Winst**, **Verlies** of **Totaal**;
- deelt geldige 1x1-prijstesten en de cashstack accountbreed met alle gekoppelde pc's;
- laat de beschikbare cashstack rechtstreeks in RuneLite instellen;
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

- **Prijzen** heet voortaan **Kansen** en gebruikt exact dezelfde persoonlijke berekening als de webscanner;
- de top vijf verwachte winsten gebruikt het verwachte aantal en vereist strikt meer dan 100.000 GP verwachte winst;
- de top vijf uurkansen toont het cash-, buy-limit- en snelheidsbegrensde aantal, GP/u en cycluswinst;
- adviesprijzen en actuele instaprijzen staan in smalle verticale kaarten die binnen de RuneLite-zijbalk blijven;
- een koopslot bevriest het verkoopdoel dat gold toen de order werd gestart;
- **Stats** heeft een keuze tussen vandaag, deze maand en totaal en gebruikt de canonieke websitegegevens;
- gerealiseerde deelwinst verschijnt al tijdens een nog lopende verkoop, terwijl **Totaal flips** uitsluitend afgeronde flips telt;
- de tabbladen, statistiekwaarden en itemopbrengsten gebruiken grotere, leesbare tekst;
- kansen en statistieken verversen normaal maximaal eens per vijf minuten; de knop in **Kansen** forceert een actuele herberekening.

## Nieuw in v5.2.0

- **Kansen** heet **Flips** en toont alleen nog de top vijf van maximale winst per volledige cyclusuur;
- items die al in een actief GE-slot staan verdwijnen direct uit de normale lijst;
- bij een geopend GE-item toont **Flips** tijdelijk alleen dat item met een vers scanneradvies;
- een GE-actie veroorzaakt na de slotsync een gerichte extra refresh, zodat prijzen, cash en buy limits aansluiten op de website;
- de dubbele scrollcontainer is verwijderd; elk tabblad heeft nu één werkende verticale scrollbar.

## Oplossing in v5.2.1

- een leeg geopend GE-slot activeert geen geselecteerde-itemmodus meer en laat de gewone top vijf in **Flips** staan;
- alleen het actuele item uit een zichtbaar koop-, verkoop- of detailscherm mag de lijst tijdelijk op één item focussen; bij een koopselectie gebruikt de plugin RuneLites actuele zoekitem omdat het itemicoon daar nog leeg kan zijn;
- een geopend bestaand offer leest het item rechtstreeks uit RuneLites actuele geselecteerde GE-slot en controleert alle detailswidgets;
- verouderde RuneScape-zoek- en laatste-offervariabelen kunnen daardoor geen oud item meer als geselecteerd tonen.

## Oplossing in v5.2.2

- RuneLites één-gebaseerde geselecteerde GE-slot wordt expliciet naar de nul-gebaseerde offer-array vertaald, inclusief de grensslots 1 en 8;
- het offer uit het geselecteerde slot krijgt voorrang op mogelijk verouderde itemwaarden in detailswidgets;
- een bestaand offer toont daardoor altijd het item uit het werkelijk geopende slot, terwijl een leeg slot de gewone lijst behoudt;
- de Worker levert het gerichte item als informatieve detailrij, ook wanneer het niet in de actuele scannerselectie valt.

## Oplossing in v5.2.3

- `Last buy price` en `Last sell price` worden alleen nog samen gepubliceerd na een volledige automatische 1×1-prijstest;
- gewone flips, deelvullingen en losse één-itemorders kunnen de testprijzen niet meer overschrijven;
- de koop moet binnen 30 seconden gevolgd worden door een lagere verkoopprijs, gelijk aan de classificatie van de webapp;
- oude lokaal bewaarde prijzen uit gewone fills worden bij de upgrade niet opnieuw ingeladen.

## Nieuw in v5.2.4

- de Flips-tab rangschikt kandidaten op de grootste totale flipwaarde in plaats van op GP per uur;
- alleen flipwaardes van minstens 100.000 GP verschijnen in de normale top;
- flipwaarde is de grote groene hoofdwaarde op iedere kaart;
- winst per uur blijft als aanvullende detailregel zichtbaar.

## Nieuw in v5.2.5

- **Stats** heeft een filter voor **Meeste winst** en **Meeste verlies** en telt een flip zodra minstens één item werkelijk verkocht is;
- geldige automatische 1x1-prijstesten worden uit de webapp opgehaald en op iedere gekoppelde pc als dezelfde prijsbron gebruikt;
- prijsadvies gebruikt eerst de accountbrede 1x1-prijstest en valt zonder test terug op Wiki instasell plus één voor kopen en Wiki instabuy min één voor verkopen;
- de drie prijsparen in **Flips** hebben een vast kleurpatroon: goud voor advies, blauw voor actuele Wiki-prijzen en paars voor de laatste 1x1-prijstest;
- nieuwe GE-orders bewaren de Wiki instabuy en instasell van het startmoment in de gedeelde slottoestand;
- de timer stopt zodra een koop- of verkoopslot volledig gevuld is;
- **Stats** bevat een accountbrede cashstackeditor; latere gesynchroniseerde GE-fills verhogen of verlagen hetzelfde serversaldo automatisch.

## Oplossing in v5.2.6

- De actuele blauwe marktprijsregels heten expliciet **Wiki instabuy** en **Wiki instasell**, zodat ze niet met de advies- of 1x1-testprijzen kunnen worden verward.
- **Koop** gebruikt de hoogste beschikbare verkoopreferentie van **Wiki instasell** en **Last sell price**, plus één GP; **Verkoop** behoudt de bestaande prijsregel.
- De vrije cashstack staat groot en goud naast het OSRS-munticoon; het invoerveld eronder blijft beschikbaar om het accountbrede saldo aan te passen.
- De aanvullende regel **Winst per uur** is uit de Flip-kaarten verwijderd, zodat flipwaarde en de drie relevante prijsparen centraal staan.
- In een geopend koopvenster worden **Koop**, **Wiki instasell** en **Last sell price** groter en vet weergegeven; in een verkoopvenster krijgen **Verkoop**, **Wiki instabuy** en **Last buy price** die nadruk.

## Oplossing in v5.2.7

- De cashstackeditor heeft een groter, vet en goud invoerveld; de afzonderlijke regel met gereserveerde cash is verwijderd.
- Een geopend bestaand GE-offer blijft als geselecteerde flip zichtbaar via de lokale slotgegevens, ook wanneer het item niet meer in de actuele scannerselectie voorkomt.
- Handmatige en GE-gestuurde overzichtsrefreshes omzeilen de servercache niet meer; actuele live prijzen blijven vernieuwen zonder dezelfde D1-statistieken herhaald breed te lezen.

## Oplossingen in v5.2.8

- **Verkoop** gebruikt voortaan de hoogste beschikbare koopreferentie van **Wiki instabuy** en **Last buy price**, min één GP; **Koop** blijft de hoogste verkoopreferentie plus één GP gebruiken.
- Het prijsinvoervenster achter de GE-knop met drie puntjes toont een eigen goudkleurige, klikbare koop- of verkoopprijs uit exact dezelfde centrale prijsregels als de Flip-kaart.
- **Lowest price** toont bij een geselecteerde flip de minimale bruto verkoopprijs waarmee de aankoop na GE-tax nog net break-even blijft.
- De uitleg onder de itemopbrengsten is verwijderd en de cashstackopslag is een duidelijk contrasterende primaire knop geworden.

## Nieuw in v5.2.9

- Het hoeveelheidsvenster achter de linker GE-knop met drie puntjes toont een eigen goudkleurige, klikbare regel met het aanbevolen aantal uit de Flip-kaart.
- Dat aantal gebruikt exact de bestaande scannergrenzen voor vrije cash, resterende buy limit en maximaal één uur koopvolume.
- Een klik op de regel vult het aanbevolen aantal meteen in als GE-hoeveelheid.
- Een gestart koopoffer bevriest de getoonde koopprijs en bewaart de Wiki-startprijzen intern; de afzonderlijk getoonde Wiki-regels blijven live verversen.
- Het verkoopdoel begint bij de beste prijs van het startmoment en mag tijdens hetzelfde offer uitsluitend omhoog, nooit omlaag.
- De eigen prijsregel kiest automatisch de hoogste vrije regel in het GE-invoervenster en overlapt daardoor niet onnodig met andere pluginregels.

## Nieuw in v5.2.10

- Iedere kaart in **Flips** toont onderaan **Winst/item** op basis van de getoonde koop- en verkoopprijs.
- De berekening trekt de officiële 2% GE-tax af, inclusief dezelfde minimumprijs-, bond- en taxcapregels als de webapp.
- Een positieve of break-even waarde is groen; verlies per item is rood.
- De chathelper voor de GE-prijsknop gebruikt hetzelfde werkelijk geopende item als de Flip-kaart; een verouderd RuneLite-zoekitem kan de koopprijs niet meer door een prijs van een ander item vervangen.
- De live en gestopte timers in **Slots** zijn groter en vet, zodat ook langere looptijden in de smalle zijbalk goed leesbaar blijven.
- Bij actieve offers blijven **Koop** en **Verkoop** aan het oorspronkelijke flipplan gekoppeld; alleen een beter verkoopdoel mag **Verkoop** verhogen. De afzonderlijke Wiki instabuy/instasell-regels blijven ondertussen live verversen.
- Na afronding of annulering van een echte flip verwerkt de plugin de accountbrede server-reset van de oude 1x1-prijstest, zodat die prijzen op geen enkele gekoppelde pc terugkeren. Een latere nieuwe 1x1-test activeert ze opnieuw.

## Oplossingen in v5.2.11

- Een verkoopoffer koppelt zijn bevroren koopprijs nu deterministisch aan de nieuwste passende, nog niet gebruikte kooporder van hetzelfde item en aantal, ook wanneer koop en verkoop in verschillende GE-slots staan.
- Een oude kooporder uit hetzelfde slot krijgt geen kunstmatige voorrang op een recentere passende cross-slotkoop; offer-ID's voorkomen dat één aankoopplan dubbel wordt gebruikt.
- Een prijswijziging behoudt hetzelfde flipplan: de echte buy-orderprijs mag worden bijgewerkt, terwijl het verkoopdoel uitsluitend omhoog kan.
- De GE-prijshelper gebruikt bij een bestaand offer exact het geselecteerde slot; bij een nieuw offer blijft hij de nieuwste live Wiki-prijs gebruiken.
- De grotere, vetgedrukte slottimer is vastgelegd in een regressietest.

## Oplossingen in v5.2.12

- Een nieuw GE-setupscherm en het bewerken van een exact bestaand offer hebben nu een expliciet gescheiden context; een achtergebleven geselecteerd-slotnummer kan daardoor geen oud bevroren plan in een nieuw offer lekken.
- De geselecteerde Flip-kaart en de klikbare prijsregel in het GE-invoervenster gebruiken dezelfde centrale resolutie en tonen daardoor altijd dezelfde koop- of verkoopprijs.
- Bij een nieuw offer worden de scannergegevens direct gecombineerd met de meest recente Wiki instabuy en instasell uit de lokale live-prijscache.
- Bij een werkelijk geopend bestaand offer blijven Koop en Verkoop uit exact dat slot bevroren, terwijl de afzonderlijke Wiki-prijzen live blijven verversen.

## Oplossingen in v5.2.13

- `Lowest price` wordt bij het eerste koopoffer één keer inclusief GE-tax berekend.
- Die vloerprijs blijft tijdens partial fills, repricing, Wiki-updates en de gekoppelde verkoop onveranderd.
- De vloerprijs synchroniseert accountbreed via de Worker; een verkoop zonder betrouwbare koopbasis toont geen live Wiki-schatting als vloer.
- De itemstatistieken gebruiken de duidelijke filters `Winst`, `Verlies` en `Totaal`; `Totaal` rangschikt alle niet-nul opbrengsten van hoogste winst naar grootste verlies.

## Oplossingen in v5.2.14

- Een gepubliceerde automatische 1x1-prijstest krijgt een geldigheid van tien minuten.
- Zodra die termijn is verstreken en lokaal geen koop- of verkooporder voor het item openstaat, vraagt RuneLite onmiddellijk de accountbrede status op; ook een lopende 1x1-test stelt die controle uit.
- Alleen de authoritatieve Worker-reset wist de prijzen blijvend; een echte flip die op een andere gekoppelde pc nog openstaat blijft de prijstest daardoor beschermen.
- De ontvangen tombstone wordt lokaal bewaard, zodat oude serverdata of een herstart de vervallen prijzen niet opnieuw zichtbaar maken.
- Een geopend nieuw GE-item blijft met zijn RuneLite-itemnaam en actuele Wiki-prijzen zichtbaar wanneer de Worker tijdelijk geen scannerkans levert; zonder scanner worden geen hoeveelheden of winsten verzonnen.
- De blauwe Wiki-prijzen van actieve slots en het gefocuste item worden periodiek opnieuw opgehaald en de gedeelde HTTP-cache wordt daarbij herbevestigd; de bevroren waarden voor **Koop**, **Verkoop** en **Lowest price** blijven onveranderd.
- Ook een offer met status `pending` beschermt de bestaande 1x1-prijstest tegen verval.

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
