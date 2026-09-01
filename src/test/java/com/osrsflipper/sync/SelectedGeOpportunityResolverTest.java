package com.osrsflipper.sync;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SelectedGeOpportunityResolverTest
{
    @Test
    public void newSellUsesTheSameLiveWikiPriceForCardAndChat()
    {
        SelectedGeOpportunityResolver.Resolution resolved = resolveNewSell(
            new MarketPriceView(101, 656_461, 651_000, 20, 21, 22));

        assertEquals(656_461, resolved.opportunity.instantBuy);
        assertEquals(656_460, resolved.price("sell"));
        assertEquals(resolved.price("sell"),
            OsrsFlipperSyncPanel.displayedSellPrice(resolved.opportunity, null));
    }

    @Test
    public void newSetupIgnoresAStaleActiveOfferFromTheSelectedSlot()
    {
        FlipperOfferView stale = activeSell(640_000, 700_000, 654_689, 650_000);
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP,
                101,
                "Dragon crossbow",
                "sell",
                scanner(),
                new MarketPriceView(101, 656_461, 651_000, 20, 21, 22),
                null,
                stale);

        assertEquals(656_460, resolved.price("sell"));
        assertEquals("selected_setup", resolved.opportunity.ranking);
    }

    @Test
    public void selectedBuyCardKeepsTheSameEffectiveBuyLimitAsTheQuantityEditor()
    {
        RuneliteOverviewView.Opportunity scanner = new RuneliteOverviewView.Opportunity(
            101,
            "Dragon crossbow",
            "cycle_profit",
            650_001,
            654_688,
            654_689,
            650_000,
            100,
            10_000,
            250,
            50_000,
            100_000,
            10,
            100,
            70,
            30);
        RuneliteOverviewView overview = new RuneliteOverviewView(
            Collections.singletonList(scanner),
            Collections.emptyList(),
            null,
            null,
            null,
            10);

        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP,
                101,
                "Dragon crossbow",
                "buy",
                scanner,
                new MarketPriceView(101, 656_461, 651_000, 20, 21, 22),
                null,
                null);

        assertTrue(resolved.opportunity.hasBuyLimit());
        assertEquals(30, resolved.opportunity.effectiveMaximumQuantity());
        assertEquals(overview.maximumQuantityForItem(101),
            resolved.opportunity.effectiveMaximumQuantity());
        assertEquals("70 / 100", OsrsFlipperSyncPanel.buyLimitUsage(resolved.opportunity));
        assertEquals("30", OsrsFlipperSyncPanel.buyLimitRemaining(resolved.opportunity));
    }

    @Test
    public void newSetupOutsideScannerRulesUsesLiveWikiPricesAndLastPriceTests()
    {
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP,
                101,
                "Dragon arrow(p++)",
                "buy",
                null,
                new MarketPriceView(101, 656_461, 651_000, 20, 21, 22),
                new LastTradePriceView(101, 660_000, 652_000, 30, 31),
                activeSell(640_000, 700_000, 654_689, 650_000));

        assertEquals("Dragon arrow(p++)", resolved.opportunity.itemName);
        assertEquals("selected_setup", resolved.opportunity.ranking);
        assertEquals(656_461, resolved.opportunity.instantBuy);
        assertEquals(651_000, resolved.opportunity.instantSell);
        assertEquals(652_001, resolved.price("buy"));
        assertEquals(659_999, resolved.price("sell"));
        assertEquals(0, resolved.opportunity.maximumQuantity);
        assertEquals(0, resolved.opportunity.maximumCycleProfit);
    }

    @Test
    public void newSetupWithoutScannerOrWikiPricesRemainsEmpty()
    {
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP,
                101,
                "Dragon arrow(p++)",
                "buy",
                null,
                null,
                new LastTradePriceView(101, 660_000, 652_000, 30, 31),
                null);

        assertNull(resolved.opportunity);
        assertEquals(0, resolved.price("buy"));
    }

    @Test
    public void exactExistingOfferKeepsItsFrozenPlan()
    {
        FlipperOfferView active = activeSell(640_000, 700_000, 800_000, 600_000);
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
                101,
                "Dragon crossbow",
                "sell",
                scanner(),
                new MarketPriceView(101, 900_000, 500_000, 20, 21, 22),
                null,
                active);

        assertEquals("active_sell", resolved.opportunity.ranking);
        assertEquals(640_000, resolved.opportunity.buyPrice);
        assertEquals(700_000, resolved.price("sell"));
        assertEquals(900_000, resolved.opportunity.instantBuy);
    }

    @Test
    public void lowerWikiRefreshKeepsTheRaisedSellTargetAndFrozenBuyFloor()
    {
        FlipperOfferView active = activeBuy(934, 1_292, 1_293, 934, 953);
        SelectedGeOpportunityResolver.Resolution first =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
                101,
                "Lassar teleport",
                "buy",
                scanner(),
                new MarketPriceView(101, 1_293, 934, 20, 21, 22),
                null,
                active);
        SelectedGeOpportunityResolver.Resolution refreshed =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
                101,
                "Lassar teleport",
                "buy",
                scanner(),
                new MarketPriceView(101, 1_245, 934, 30, 31, 32),
                null,
                active);

        assertEquals(1_293, first.opportunity.instantBuy);
        assertEquals(1_245, refreshed.opportunity.instantBuy);
        assertEquals(934, refreshed.opportunity.instantSell);
        assertEquals(934, refreshed.opportunity.buyPrice);
        assertEquals(1_292, refreshed.opportunity.sellPrice);
        assertEquals(953, refreshed.opportunity.lowestSellPrice);
    }

    @Test
    public void existingOfferKeepsFrozenLowestPriceWhileNewSetupHasNoStoredFloor()
    {
        FlipperOfferView active = new FlipperOfferView(
            2, 101, "Dragon crossbow", "sell", 700_000,
            100, 0, "active", 1, 0,
            640_000, 700_000, 800_000, 600_000, 652_000);
        SelectedGeOpportunityResolver.Resolution existing =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
                101,
                "Dragon crossbow",
                "sell",
                scanner(),
                new MarketPriceView(101, 900_000, 500_000, 20, 21, 22),
                null,
                active);
        SelectedGeOpportunityResolver.Resolution setup = resolveNewSell(
            new MarketPriceView(101, 900_000, 500_000, 20, 21, 22));

        assertEquals(652_000, existing.opportunity.lowestSellPrice);
        assertEquals(0, setup.opportunity.lowestSellPrice);
    }

    @Test
    public void sellSetupAfterCollectKeepsTheBuyCycleFrozenWhileWikiMoves()
    {
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP,
                3004,
                "Snapdragon potion (unf)",
                "sell",
                new RuneliteOverviewView.Opportunity(
                    3004, "Snapdragon potion (unf)", "cycle_profit",
                    7_841, 7_888, 7_860, 7_840,
                    5_803, 0, 5_803, 0, 0, 30),
                new MarketPriceView(3004, 7_860, 7_840, 30, 30, 30),
                new LastTradePriceView(3004, 7_889, 7_630, 20, 21),
                null,
                openSnapdragonCycle());

        assertEquals("open_flip_cycle", resolved.opportunity.ranking);
        assertEquals(7_631, resolved.opportunity.buyPrice);
        assertEquals(7_888, resolved.opportunity.sellPrice);
        assertEquals(7_786, resolved.opportunity.lowestSellPrice);
        assertEquals(5_803, resolved.opportunity.maximumQuantity);
        assertEquals(7_860, resolved.opportunity.instantBuy);
        assertEquals(7_840, resolved.opportunity.instantSell);
        assertEquals(7_631,
            OsrsFlipperSyncPanel.displayedBuyPrice(resolved.opportunity,
                new LastTradePriceView(3004, 8_100, 8_000, 40, 41)));
    }

    @Test
    public void aNewBuySetupDoesNotInheritAnOlderOpenSellCycle()
    {
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP,
                3004,
                "Snapdragon potion (unf)",
                "buy",
                null,
                new MarketPriceView(3004, 7_860, 7_840, 30, 30, 30),
                new LastTradePriceView(3004, 7_889, 7_700, 20, 21),
                null,
                openSnapdragonCycle());

        assertEquals("selected_setup", resolved.opportunity.ranking);
        assertEquals(7_701, resolved.opportunity.buyPrice);
        assertEquals(0, resolved.opportunity.lowestSellPrice);
    }

    @Test
    public void aNewBuyDoesNotInheritAnotherOpenBuyForTheSameItem()
    {
        FlipperOfferView openBuy = new FlipperOfferView(
            1, 29455, "Moonlight antler bolts", "buy", 229,
            10_999, 0, "cycle_open_buy", 100, 0,
            229, 244, 245, 245, 234);
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.NEW_SETUP,
                29455,
                "Moonlight antler bolts",
                "buy",
                null,
                new MarketPriceView(29455, 260, 250, 30, 30, 30),
                new LastTradePriceView(29455, 245, 240, 20, 21),
                null,
                openBuy);

        assertEquals("selected_setup", resolved.opportunity.ranking);
        assertEquals(241, resolved.opportunity.buyPrice);
        assertEquals(244, resolved.opportunity.sellPrice);
        assertEquals(0, resolved.opportunity.lowestSellPrice);
    }

    @Test
    public void anExactSellOfferTakesPriorityOverTheOpenCycleFallback()
    {
        FlipperOfferView exact = new FlipperOfferView(
            2, 3004, "Snapdragon potion (unf)", "sell", 7_900,
            5_803, 0, "active", 200, 0,
            7_631, 7_900, 7_860, 7_840, 7_786);
        SelectedGeOpportunityResolver.Resolution resolved =
            SelectedGeOpportunityResolver.resolve(
                FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
                3004,
                "Snapdragon potion (unf)",
                "sell",
                null,
                new MarketPriceView(3004, 8_000, 7_700, 30, 30, 30),
                null,
                exact,
                openSnapdragonCycle());

        assertEquals("active_sell", resolved.opportunity.ranking);
        assertEquals(7_631, resolved.opportunity.buyPrice);
        assertEquals(7_900, resolved.opportunity.sellPrice);
        assertEquals(7_786, resolved.opportunity.lowestSellPrice);
    }

    @Test
    public void liveMarketRefreshMovesCardAndChatTogether()
    {
        SelectedGeOpportunityResolver.Resolution first = resolveNewSell(
            new MarketPriceView(101, 656_461, 651_000, 20, 21, 22));
        SelectedGeOpportunityResolver.Resolution refreshed = resolveNewSell(
            new MarketPriceView(101, 660_001, 652_000, 30, 31, 32));

        assertEquals(656_460, first.price("sell"));
        assertEquals(660_000, refreshed.price("sell"));
        assertEquals(refreshed.price("sell"),
            OsrsFlipperSyncPanel.displayedSellPrice(refreshed.opportunity, null));
    }

    @Test
    public void onlyAnExistingDetailsTransitionMayKeepTheFrozenContext()
    {
        assertEquals(FocusedGeItemResolver.EditorContext.NEW_SETUP,
            FocusedGeItemResolver.editorContext(
                true,
                false,
                FocusedGeItemResolver.EditorContext.NONE,
                2,
                0,
                true));
        assertEquals(FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
            FocusedGeItemResolver.editorContext(
                false,
                true,
                FocusedGeItemResolver.EditorContext.NONE,
                2,
                0,
                true));
        assertEquals(FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
            FocusedGeItemResolver.editorContext(
                true,
                false,
                FocusedGeItemResolver.EditorContext.EXISTING_OFFER,
                2,
                2,
                true));
    }

    private static SelectedGeOpportunityResolver.Resolution resolveNewSell(MarketPriceView market)
    {
        return SelectedGeOpportunityResolver.resolve(
            FocusedGeItemResolver.EditorContext.NEW_SETUP,
            101,
            "Dragon crossbow",
            "sell",
            scanner(),
            market,
            null,
            null);
    }

    private static RuneliteOverviewView.Opportunity scanner()
    {
        return new RuneliteOverviewView.Opportunity(
            101,
            "Dragon crossbow",
            "cycle_profit",
            650_001,
            654_688,
            654_689,
            650_000,
            100,
            10_000,
            100,
            50_000,
            100_000,
            10);
    }

    private static FlipperOfferView activeSell(
        int buyPrice,
        int sellPrice,
        int instantBuy,
        int instantSell)
    {
        return new FlipperOfferView(
            2,
            101,
            "Dragon crossbow",
            "sell",
            sellPrice,
            100,
            0,
            "active",
            1,
            0,
            buyPrice,
            sellPrice,
            instantBuy,
            instantSell);
    }

    private static FlipperOfferView activeBuy(
        int buyPrice,
        int sellPrice,
        int instantBuy,
        int instantSell,
        int lowestSellPrice)
    {
        return new FlipperOfferView(
            2,
            101,
            "Lassar teleport",
            "buy",
            buyPrice,
            100,
            0,
            "active",
            1,
            0,
            buyPrice,
            sellPrice,
            instantBuy,
            instantSell,
            lowestSellPrice);
    }

    private static FlipperOfferView openSnapdragonCycle()
    {
        return new FlipperOfferView(
            1,
            3004,
            "Snapdragon potion (unf)",
            "sell",
            7_888,
            5_803,
            0,
            "cycle_pending_sell",
            100,
            0,
            7_631,
            7_888,
            7_860,
            7_840,
            7_786);
    }
}
