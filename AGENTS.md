# Harde projectvoorwaarden

- Dien NOOIT iets voor dit project in bij de RuneLite Plugin Hub. Maak of wijzig geen Plugin Hub-pull requests, branches, manifests of publicatieaanvragen. De eigenaar heeft dit expliciet verboden.
- Een verzoek om te deployen of publiceren geeft geen toestemming voor Plugin Hub-publicatie. Gebruik uitsluitend de door de eigenaar toegestane bestemming.
- Cloudflare moet op het Free-plan blijven. Dit project wordt door twee mensen gebruikt; ontwerp wijzigingen binnen die randvoorwaarde.
- Test vóór publicatie de volledige overview-route van request en asynchrone callback tot het Flips-paneel: volledige lijst, itemselectie en sluiten, vertraagde antwoorden, lege/oude/ongeldige marktdata en automatisch herstel. Controleer ook requestaantallen en accountwissels. Voer de regressiesuite uit in een geïsoleerd RuneLite-testprofiel; tests mogen het echte profiel, tokens of client.log niet gebruiken.
