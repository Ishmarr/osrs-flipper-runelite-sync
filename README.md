# OSRS Flipper Sync v5.2.27

RuneLite-plugin voor de veilige koppeling tussen RuneLite en de OSRS Flip Tracker-webapp.

## 5.2.27

- Een aanbevolen aantal dat tijdens een geopend GE-invoervenster nul of onbekend wordt, verdwijnt onmiddellijk en is niet meer aanklikbaar. Een later geldig aantal of prijsadvies maakt dezelfde hulpregel weer zichtbaar.
- De hoeveelheidsknop controleert bij iedere klik opnieuw het huidige item, de koopcontext en het nieuwste aantal, zodat een vertraagde klik geen verouderde hoeveelheid overneemt.
- Handmatige invoer, widgets van andere plugins, lopende offers, vastgelegde prijsadviezen en synchronisatie blijven ongemoeid.

## 5.2.26

- Ontbrekende persoonlijke aantallen worden als `Niet beschikbaar` weergegeven; een echte nul (bijvoorbeeld een opgebruikte GE-buy limit) blijft nul. Wiki-prijsadvies blijft bruikbaar zonder een misleidende totale flipwinst van 0 GP.
- Time-outs, serverfouten en onvolledige antwoorden verschijnen blijvend boven alle tabs, met de laatste succesvolle update en het aantal wachtende GE-events. Overviews, GE-updates, slotcontroles en verbindingscontroles herstellen onafhankelijk.
- Een ongeldig overviewantwoord vervangt de laatste goede prijzen, statistieken en cashstack niet. Automatische retries gebruiken 15–300 seconden backoff; handmatige/focusrefreshes kunnen die niet omzeilen. GE-delta's en herstel-snapshots krijgen voorrang.
- Een onvolledig GE-antwoord houdt het event veilig in de wachtrij. Een volledig, inhoudelijk geweigerd event wordt daarentegen niet eindeloos opnieuw verstuurd: het maakt plaats voor de gezaghebbende slotreconciliatie.
- De snapshotoptimalisatie, live Wiki-prijzen, buy limits, slottimers en vijfminuten-prijstestreset blijven behouden.

## 5.2.25

- Bij login wordt nog maar één actuele volledige slotsnapshot verstuurd; een tijdens accountload klaargezette snapshot wacht eerst op de RuneLite-reconciliatie.
- Het openen van de Grand Exchange verstuurt alleen een snapshot wanneer de slotinhoud werkelijk gewijzigd is.
- De lokale vijfminutencontrole en onmiddellijke delta-events blijven behouden, maar de periodieke ongewijzigde veiligheidssnapshot gaat naar maximaal één per uur.
- Een complete, conflictvrije snapshotrespons wordt direct als servercontrole gebruikt, zodat daarna geen dubbele `/state`-aanvraag meer nodig is. Onvolledige of conflicterende antwoorden behouden de veilige fallback.
- Een volle lokale eventwachtrij forceert een herstelsnapshot, zodat de lagere requestfrequentie geen GE-mutatie kan verliezen.

## 5.2.24

- De timer naast `Buy` en `Sell` is één punt kleiner, zodat hij ook in compacte GE-slots netjes binnen het vak past.
- Iedere echte gedeeltelijke koop- of verkoopfill zet de actieve slottimer terug op `00:00:00`; gewone refreshes, prijsadviesupdates en repricing zonder nieuwe fill doen dat niet.
- Een volledig gekocht of verkocht offer toont vast de totale duur vanaf de oorspronkelijke offerstart tot de laatste fill, in plaats van op nul te eindigen.
- Fill-resets en hun high-watermark worden lokaal per account bewaard, zodat een RuneLite-herstart of tijdelijke terugval van het waargenomen aantal geen foutieve timerreset veroorzaakt.

## 5.2.23

- `Aantal` houdt rekening met de officiële vieruurs-buy limit, reeds gekochte stuks en nog open koopofferrestanten; de kaart toont hoeveel van de limiet bezet en nog vrij is.
- Bevestigde koopupdates vragen een gezaghebbende limietstatus op. Verkoop-only syncs blijven de bestaande lichte overviewcache gebruiken.
- Alle acht Grand Exchange-slots tonen naast `Buy` of `Sell` een klikvrije `HH:MM:SS`-timer. Actieve offers lopen live door en voltooide of geannuleerde offers bevriezen op hun eindtijd totdat het slot wordt leeggemaakt.
- De overlay cachet widgetlabels en timertekst defensief, zodat de GE-weergave ook met acht bezette slots licht blijft.

## 5.2.22

- `Last buy price - 1 GP` blijft het oorspronkelijke verkoopdoel, maar een hogere actuele Wiki instabuy mag `Verkoop` tijdens dezelfde flip verhogen naar `Wiki instabuy - 1 GP`.
- Na de start kan een latere `Last buy price` de lopende flip niet meer herschrijven; uitsluitend `Wiki instabuy - 1 GP` is dan nog een automatische verhogingskandidaat.
- `Verkoop` is raise-only: een gelijke of lagere Wiki instabuy en een latere marktdaling kunnen het vastgelegde doel nooit verlagen.
- Slotweergave, bewaarde flipcyclus en accountsynchronisatie houden hetzelfde hoogste verkoopdoel vast. `Koop` en `Lowest price` blijven bevroren en gesloten cycli worden niet aangepast.

## 5.2.21

- Een item blijft persoonlijk prijsadvies gebruiken zolang het in minstens één GE-slot zichtbaar is, ook wanneer het offer voltooid of geannuleerd is.
- Zodra het item 300 seconden onafgebroken uit alle GE-slots verdwenen is, vraagt RuneLite onmiddellijk een gezaghebbende controle aan de Worker; korte collect-, slotwissel- en repricinggaten resetten de prijsbron niet.
- De afwezigheidsdeadline blijft per account en item bewaard bij een herstart. RuneLite wist zelf geen prijzen wanneer de Worker onbereikbaar is.
- Na een nieuwe Worker-tombstone verdwijnen alleen open flipplannen die aantoonbaar ouder zijn dan de reset. Een vertraagde response kan een inmiddels nieuw gestarte Wiki-cyclus dus niet verwijderen.
- Overviewresponses zijn aan zowel account als requestgeneratie gebonden; een vertraagde response van een vorig account kan de huidige lokale boeken niet wijzigen.
- RuneLite stuurt de lokaal gevolgde prijs- en cyclusitems mee, zodat hun tombstone ook buiten de algemene serverlimiet altijd wordt teruggeleverd.

## 5.2.20

- Een geldige persoonlijke `Last sell price` bepaalt de nieuwe kooplimiet; Wiki instasell wordt alleen gebruikt wanneer die persoonlijke prijs ontbreekt. Voor verkoop geldt hetzelfde met `Last buy price` en Wiki instabuy.
- Koopprijs en break-evenvloer worden lokaal en accountgebonden aan een persistente flipcyclus gekoppeld in plaats van aan één GE-slot. Ze blijven daardoor gelijk tijdens buy, collect, sell-setup, repricing, gedeeltelijke verkopen en de uiteindelijke sell.
- Meerdere verkoopoffers reserveren uitsluitend de nog onverkochte hoeveelheid van dezelfde buy en kunnen de cyclus niet dubbel gebruiken.
- De blauwe Wiki-prijzen blijven live bewegen zonder het bevroren flipplan te overschrijven.

## 5.2.19

- Herstelt de prijsrichting op alle gewone top-flipkaarten: instant sell ankert de kooporder en instant buy ankert de verkooporder.
- Berekent de getoonde totale flipwinst opnieuw uit dezelfde koopprijs, verkoopprijs, tax en hoeveelheid als `Winst/item`.
- Bestaande actieve offers en geselecteerde nieuwe setups behouden hun afzonderlijk vastgelegde planprijzen.

## 5.2.18

- Herstelt de koop- en verkoopvoorstellen op flipkaarten: een persoonlijke instant-sellprijs ankert de limit-kooporder en een persoonlijke instant-buyprijs ankert de limit-verkooporder.
- Een bestaande offerinstantie behoudt nog steeds haar bevroren koop- en verkoopplan.

## Functies

- synchroniseert alle acht Grand Exchange-slots;
- stuurt wijzigingen onmiddellijk door;
- bewaart tijdelijk niet-verzonden gebeurtenissen lokaal en probeert ze opnieuw;
- controleert de slots lokaal iedere vijf minuten, stuurt wijzigingen onmiddellijk en gebruikt maximaal ieder uur een ongewijzigde veiligheidssnapshot;
- vergelijkt de lokale toestand met de server en herstelt verschillen automatisch;
- biedt een compact RuneLite-zijpaneel met **Slots**, **Flips**, **Stats** en **Sync**;
- toont alle actuele GE-slots met itemicoon, koop/verkoopzijde, voortgang, prijs en live timer;
- bewaart bij de start van een geadviseerde kooporder het koopplan in een accountgebonden flipcyclus die onafhankelijk is van het gebruikte GE-slot;
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

## Oplossing in v5.2.15

- De plugin kan opnieuw normaal worden ingeschakeld: de eerste zijpaneelrefresh vraagt niet langer vanaf de Swing-thread om RuneLite-itemdefinities.
- De naam van een geselecteerd GE-item wordt op de RuneLite-clientthread vastgelegd en daarna alleen uit die veilige cache gerenderd.
- Een lege selectie bij het opstarten wordt direct afgehandeld zonder itemlookup.

## Oplossing in v5.2.16

- De normale Flip-lijst wordt ongeveer iedere minuut vernieuwd, zodat Wiki-prijzen niet meer tot vijf minuten achterlopen.
- De vernieuwknop vraagt expliciet de nieuwste Wiki-marktdata op; gewone periodieke refreshes blijven de lichte Worker-cache gebruiken.
- Een klik tijdens een lopende refresh blijft bewaard en wordt direct daarna als verse marktrefresh uitgevoerd.
- De langere caches voor persoonlijke statistieken, cash, prijstests en buy limits blijven behouden, zodat de snellere marktrefresh geen brede D1-reads veroorzaakt.

## Oplossing in v5.2.17

- `Modify offer` houdt de oorspronkelijke, bij aankoop bevroren `Lowest price` vast.
- Ook wanneer een gedeeltelijk gevuld offer alleen met de resterende hoeveelheid opnieuw wordt geplaatst, blijft dezelfde vloerprijs en koopkoppeling behouden.
- Meerdere opeenvolgende prijswijzigingen houden exact dezelfde oorspronkelijke vloerprijs vast.
- Een normaal afgerond, geleegd of nieuw offer erft nooit de vloerprijs van een vorige cyclus.
- Een snelle automatische 1x1-test bewaart de werkelijk gevulde koop- en verkoopprijs ook wanneer de test gelijk of toevallig winstgevend eindigt.
- Snelle 1x1-fills in verschillende GE-slots gebruiken dezelfde fysieke waarnemingsklok; een volledig gevulde `CANCELLED`-race en een veilig gemiste runtime-fill worden eveneens herkend.
- Deze release liet het zijpaneel persoonlijke fillprijzen tijdelijk aan de verkeerde voorstelzijde tonen; v5.2.19 herstelt de koppeling naar `Last sell price` voor Koop en `Last buy price` voor Verkoop.

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
