package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One immutable client-thread snapshot for one Swing update. */
final class FlipperPanelView
{
    final List<FlipperOfferView> offers;
    final RuneliteOverviewView overview;
    final Map<Integer, LastTradePriceView> lastTradePrices;
    final int focusedItemId;
    final String focusedSide;
    final RuneliteOverviewView.Opportunity focusedOpportunity;
    final String healthText;

    FlipperPanelView(
        List<FlipperOfferView> offers,
        RuneliteOverviewView overview,
        Map<Integer, LastTradePriceView> lastTradePrices,
        int focusedItemId,
        String focusedSide,
        RuneliteOverviewView.Opportunity focusedOpportunity,
        String healthText)
    {
        List<FlipperOfferView> sortedOffers = new ArrayList<>(offers == null
            ? Collections.emptyList() : offers);
        sortedOffers.sort(Comparator.comparingInt(value -> value.slotNumber));
        this.offers = Collections.unmodifiableList(sortedOffers);
        this.overview = overview == null ? RuneliteOverviewView.empty() : overview;
        this.lastTradePrices = Collections.unmodifiableMap(new LinkedHashMap<>(lastTradePrices == null
            ? Collections.emptyMap() : lastTradePrices));
        this.focusedItemId = Math.max(0, focusedItemId);
        this.focusedSide = this.focusedItemId > 0 && ("buy".equals(focusedSide) || "sell".equals(focusedSide))
            ? focusedSide : "";
        this.focusedOpportunity = focusedOpportunity != null && focusedOpportunity.itemId == this.focusedItemId
            ? focusedOpportunity : null;
        this.healthText = healthText == null ? "" : healthText;
    }
}
