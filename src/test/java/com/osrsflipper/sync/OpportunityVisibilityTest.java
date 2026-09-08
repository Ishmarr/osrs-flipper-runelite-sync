package com.osrsflipper.sync;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OpportunityVisibilityTest
{
    @Test
    public void hidesEveryItemThatAlreadyOccupiesAGeSlot()
    {
        RuneliteOverviewView.Opportunity first = opportunity(101);
        RuneliteOverviewView.Opportunity second = opportunity(202);
        FlipperOfferView activeBuy = new FlipperOfferView(
            1, 101, "Item 101", "buy", 100, 10, 0,
            "active", 1, 0, 100, 110, 111, 99);

        List<RuneliteOverviewView.Opportunity> visible =
            OsrsFlipperSyncPanel.visibleCycleOpportunities(
                Arrays.asList(first, second),
                Collections.singletonList(activeBuy));

        assertEquals(1, visible.size());
        assertEquals(202, visible.get(0).itemId);
    }

    @Test
    public void latestFungusLossIsRemovedBeforeRankingWithoutDeletingTheCandidate()
    {
        RuneliteOverviewView.Opportunity fungus = candidate(2970, 192, 211, 13_000, 13_000);
        List<RuneliteOverviewView.Opportunity> candidates = Collections.singletonList(fungus);
        assertEquals(1, rank(candidates, Collections.emptyMap(), Collections.emptyMap(), 10_000_000L).size());

        Map<Integer, MarketPriceView> prices = Collections.singletonMap(2970,
            new MarketPriceView(2970, 192, 191, 2000, 2000, 2000));
        assertTrue(rank(candidates, prices, Collections.emptyMap(), 10_000_000L).isEmpty());
        assertEquals("Filtering must preserve the source for selection and recovery", 13_000,
            candidates.get(0).maximumQuantity);
        assertEquals(211, candidates.get(0).sellPrice);
    }

    @Test
    public void measuredOneItemTradeCanRemoveAStillProfitableWikiCandidate()
    {
        RuneliteOverviewView.Opportunity ore = candidate(451, 9764, 10499, 4500, 4500);
        LastTradePriceView loss = new LastTradePriceView(451, 9816, 9783, 2000, 2001);
        assertTrue(rank(Collections.singletonList(ore), Collections.emptyMap(),
            Collections.singletonMap(451, loss), 100_000_000L).isEmpty());
    }

    @Test
    public void appliesTheHundredThousandFloorToTheActualExecutableQuantity()
    {
        // 205 sell - 101 buy - 4 tax = 100 GP per item.
        RuneliteOverviewView.Opportunity exact = candidate(101, 101, 205, 1000, 1000);
        assertEquals(100_000, rank(Collections.singletonList(exact), Collections.emptyMap(),
            Collections.emptyMap(), 101_000L).get(0).maximumCycleProfit);
        assertTrue("Cash can lower a previously qualifying candidate below the minimum",
            rank(Collections.singletonList(exact), Collections.emptyMap(),
                Collections.emptyMap(), 100_999L).isEmpty());
        assertTrue("A depleted limit must not inherit the cached scanner profit",
            rank(Collections.singletonList(candidate(202, 101, 205, 1000, 0)),
                Collections.emptyMap(), Collections.emptyMap(), 10_000_000L).isEmpty());
        assertTrue(rank(Collections.singletonList(exact), Collections.emptyMap(),
            Collections.emptyMap(), 0L).isEmpty());
    }

    @Test
    public void ranksByResolvedProfitBeforeTakingFiveAndKeepsStableTies()
    {
        List<RuneliteOverviewView.Opportunity> candidates = Arrays.asList(
            candidate(101, 101, 501, 1000, 1000),
            candidate(202, 101, 401, 1000, 1000),
            candidate(303, 101, 401, 1000, 1000),
            candidate(404, 101, 301, 1000, 1000),
            candidate(505, 101, 301, 1000, 1000),
            candidate(606, 101, 601, 1000, 1000),
            candidate(707, 101, 205, 1000, 1000));
        Map<Integer, MarketPriceView> prices = Collections.singletonMap(101,
            new MarketPriceView(101, 206, 100, 2000, 2000, 2000));
        List<RuneliteOverviewView.Opportunity> ranked = rank(candidates, prices, Collections.emptyMap(), 10_000_000L);
        assertEquals(Arrays.asList(606, 202, 303, 404, 505),
            ranked.stream().map(item -> item.itemId).collect(Collectors.toList()));
        assertEquals(101, candidates.get(0).itemId);
        assertEquals(501, candidates.get(0).sellPrice);
    }

    @Test
    public void unknownCapacityAndMissingPricesCannotBecomeRecommendations()
    {
        RuneliteOverviewView.Opportunity legacy = opportunity(101);
        Map<Integer, MarketPriceView> changed = Collections.singletonMap(101,
            new MarketPriceView(101, 100_000, 100, 2000, 2000, 2000));
        assertTrue(rank(Collections.singletonList(legacy), changed, Collections.emptyMap(), null).isEmpty());
        RuneliteOverviewView.Opportunity missingSell = new RuneliteOverviewView.Opportunity(
            202, "No sell price", "cycle_profit", 101, 0, 0, 100, 0, 0, 1000, 1_000_000, 1_000_000, 1000);
        RuneliteOverviewView.Opportunity missingBuy = new RuneliteOverviewView.Opportunity(
            303, "No buy price", "cycle_profit", 0, 205, 206, 0, 0, 0, 1000, 1_000_000, 1_000_000, 1000);
        assertTrue(rank(Arrays.asList(missingSell, missingBuy), Collections.emptyMap(),
            Collections.emptyMap(), null).isEmpty());
    }

    @Test
    public void candidateFilteringRejectsInvalidAndDuplicateItems()
    {
        RuneliteOverviewView.Opportunity first = opportunity(101);
        List<RuneliteOverviewView.Opportunity> result = OsrsFlipperSyncPanel.visibleCycleOpportunities(
            Arrays.asList(null, opportunity(0), first, first, opportunity(202)), Collections.emptyList());
        assertEquals(Arrays.asList(101, 202), result.stream().map(item -> item.itemId).collect(Collectors.toList()));
    }

    private static List<RuneliteOverviewView.Opportunity> rank(
        List<RuneliteOverviewView.Opportunity> candidates, Map<Integer, MarketPriceView> markets,
        Map<Integer, LastTradePriceView> tests, Long cash)
    {
        return OsrsFlipperSyncPanel.rankedCycleOpportunities(candidates, markets, tests, cash);
    }

    private static RuneliteOverviewView.Opportunity candidate(int itemId, int buy, int sell, int limit, int remaining)
    {
        return new RuneliteOverviewView.Opportunity(
            itemId, "Item " + itemId, "cycle_profit", buy, sell, sell + 1, buy - 1,
            0, 0, limit, 10_000_000, 10_000_000, 1000, 0, limit, limit - remaining, remaining,
            new QuantityCapacity(100_000_000L, 20_000, 20_000, 100_000), "");
    }

    private static RuneliteOverviewView.Opportunity opportunity(int itemId)
    {
        return new RuneliteOverviewView.Opportunity(
            itemId,
            "Item " + itemId,
            "hourly",
            100,
            110,
            111,
            99,
            0,
            0,
            10,
            5_000,
            1_000,
            1234);
    }
}
