# Afhandeling P3-audit

Versie 5.2.33 behandelt de vier Low-punten (17–20) uit de oorspronkelijke audit, bovenop de hersteltests voor verdwenen flips uit 5.2.32.

| Auditpunt | Opgelost gedrag | Code en regressies |
| --- | --- | --- |
| 17. Vier kaartopbouwen per paneelrefresh | `refreshSidePanel` maakt één immutable `FlipperPanelView`. Slots, overview, persoonlijke prijzen, geselecteerd item en foutstatus worden samen in één Swing-update toegepast. Elke kaartsectie wordt eenmaal opgebouwd; de volledige flipslijst en de beschikbaarheidsvelden blijven behouden. | `FlipperPanelView`, `OsrsFlipperSyncPanel`, `OsrsFlipperSyncPlugin`; `PanelUpdateRegressionTest` en bestaande callback-tot-paneeltests. |
| 18. Wiki-deduplicatie vergeet het actieve item | De deduplicatie controleert ook het item van de lopende aanvraag, totdat diens callback op de clientthread verwerkt is. Een geslaagde/verloren/ongeldige response of contextwissel geeft het juiste item weer vrij. Een latere expliciete verversing blijft werken. | `OsrsFlipperSyncPlugin`, `WikiMarketPriceRequestTest`: requestaantallen, andere items, foutantwoorden, accountwissel en shutdown/herstart. |
| 19. Verschillende timers na partial fill | Sidebar en overlay krijgen beide een `GeSlotTimerView` uit dezelfde snapshotfunctie. Actieve offers gebruiken de laatste timerreset, terminale offers de totale orderduur. De oorspronkelijke `startedAt` blijft beschikbaar voor voorraadkoppeling en synchronisatie. | `FlipperOfferView`, `OsrsFlipperSyncPanel`, `OsrsFlipperSyncPlugin`; `GeSlotTimerMatchingTest`, `GeSlotTimerViewTest`. |
| 20. Ongebruikte administratie en grote centrale klasse | De ongebruikte lokale sessievoorraad en statistiekberekening zijn verwijderd. De bestaande belasting-, winst- en break-evenfuncties zijn ongewijzigd ondergebracht in `GeTax`. Het Worker-overviewcontract en de modelconversie staan in `WorkerOverviewResponse` en zijn rechtstreeks testbaar. | `GeTax`, `WorkerOverviewResponse`, `OsrsFlipperSyncPlugin`; `GeTaxTest`, `PeriodStatsAttributionTest`, `OverviewFailureRegressionTest` en de volledige asynchrone overviewregressies. |

De herstructurering is beperkt tot deze verantwoordelijkheden. Persoonlijke handelsgeschiedenis, journals, cashopdrachten, flipcycli, prijsproeven en accountstatistieken blijven via hun bestaande opslag en Worker-contracten lopen. De lokale sessiestatistieken hadden geen productieconsumer; de tests voor die verwijderde administratie zijn vervangen door directe tests van de behouden prijsberekeningen. De volledige catalogus met vrijgestelde item-ID's en de bestaande afronding zijn behouden.

## Cloudflare Free en publicatie

Dit zijn lokale wijzigingen voor de twee gebruikers. Er zijn geen nieuwe cloudservices, Worker-wijzigingen, D1-queries of periodieke aanvragen. De Wiki-deduplicatie vermindert rechtstreeks Wiki-verkeer. Worker-serialisatie, GE-/cashprioriteiten, snapshotdelen, caches en backoff blijven behouden.

Publicatie verloopt uitsluitend via de eigen GitHub-repository. De RuneLite Plugin Hub is verboden. Een actieve RuneLite-client moet eenmaal herstarten om de nieuwe code te laden; de bestaande koppeling blijft bruikbaar.

## Verificatie

Resultaat op 5 september 2026: **387 tests in 47 suites, 0 failures, 0 errors en 0 skipped**. De volledige offline build van 5.2.33 is geslaagd met JDK 26.0.1, Gradle 9.6.0 en de vastgelegde RuneLite-versie 1.12.37. `git diff --check` slaagt; de Worker-repository is ongewijzigd. De gebouwde JAR bevat de nieuwe klassen en geen oude `SessionStatsTracker` meer.

De geïsoleerde suite omvat ook alle 5.2.32-scenario's voor complete/focused overviews, vertraagde antwoorden, oude/lege/ongeldige marktdata, automatisch herstel, requestaantallen en accountwissels. Nieuwe regressies meten werkelijke kaarttoevoegingen/-verwijderingen en Wiki-callbacks, en vergelijken sidebarlabels met overlaytimers. De automatische GitHub-controle draait dezelfde suite met JDK 17. Tests gebruiken geen echte tokens, gebruikersprofielen, client.log of productiehandelsmutaties.

Bij review zijn de verplaatste overviewcode en GE-taxmethoden programmatisch vergeleken met de vorige versie: de inhoud is gelijk, afgezien van klassennesting, encapsulatie en de gelijkwaardige focuscontrole. De bestaande JDK 26-waarschuwing over de door RuneLite beheerde Gson-versie bij herstel van immutable cashopdrachten blijft aanwezig; dependencies zijn voor deze opruiming niet gewijzigd.
