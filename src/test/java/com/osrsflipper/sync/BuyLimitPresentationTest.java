package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BuyLimitPresentationTest
{
    @Test
    public void showsUsedOfficialAndFreeBuyLimitInDutch()
    {
        RuneliteOverviewView.Opportunity opportunity = opportunity(
            250, 10_000, 2_750, 7_250);

        assertEquals("2 750 / 10 000",
            OsrsFlipperSyncPanel.buyLimitUsage(opportunity));
        assertEquals("7 250",
            OsrsFlipperSyncPanel.buyLimitRemaining(opportunity));
    }

    @Test
    public void adjustedCycleProfitUsesTheEffectiveQuantityShownAsAantal()
    {
        RuneliteOverviewView.Opportunity opportunity = opportunity(
            250, 100, 70, 30);
        long profitPerItem = SessionStatsTracker.calculateProfitPerItem(
            opportunity.buyPrice,
            opportunity.sellPrice,
            opportunity.itemId);

        assertEquals(30, opportunity.effectiveMaximumQuantity());
        assertEquals(profitPerItem * 30,
            OsrsFlipperSyncPanel.displayedCycleProfit(
                opportunity,
                opportunity.buyPrice,
                opportunity.sellPrice));
    }

    @Test
    public void oldOpportunityRowsDoNotInventBuyLimitStatus()
    {
        RuneliteOverviewView.Opportunity opportunity = new RuneliteOverviewView.Opportunity(
            202,
            "Test item",
            "active_buy",
            2_250,
            2_500,
            2_501,
            2_249,
            10,
            100_001,
            20,
            200_000,
            50_000,
            1234);

        assertEquals(20, opportunity.effectiveMaximumQuantity());
        assertEquals("", OsrsFlipperSyncPanel.buyLimitUsage(opportunity));
        assertEquals("", OsrsFlipperSyncPanel.buyLimitRemaining(opportunity));
    }

    private static RuneliteOverviewView.Opportunity opportunity(
        int maximumQuantity,
        int officialBuyLimit,
        int usedBuyLimit,
        int remainingBuyLimit)
    {
        return new RuneliteOverviewView.Opportunity(
            202,
            "Test item",
            "expected",
            2_250,
            2_500,
            2_501,
            2_249,
            10,
            100_001,
            maximumQuantity,
            200_000,
            50_000,
            1234,
            officialBuyLimit,
            usedBuyLimit,
            remainingBuyLimit);
    }
}
