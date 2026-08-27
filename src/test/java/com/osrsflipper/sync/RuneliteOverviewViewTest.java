package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class RuneliteOverviewViewTest
{
    @Test
    public void findsTheFrozenSaleTargetFromEitherRanking()
    {
        RuneliteOverviewView.Opportunity expected = opportunity(101, 1_250, 1_500);
        RuneliteOverviewView.Opportunity hourly = opportunity(202, 2_250, 2_500);
        RuneliteOverviewView view = new RuneliteOverviewView(
            Collections.singletonList(expected),
            Arrays.asList(expected, hourly),
            null,
            null,
            null,
            1234);

        assertSame(expected, view.opportunityForItem(101));
        assertSame(hourly, view.opportunityForItem(202));
        assertNull(view.opportunityForItem(303));
        assertEquals(1_500, view.opportunityForItem(101).sellPrice);
    }

    @Test
    public void focusedOpportunityWinsOverCachedRankings()
    {
        RuneliteOverviewView.Opportunity cached = opportunity(202, 2_250, 2_500);
        RuneliteOverviewView.Opportunity focused = opportunity(202, 2_300, 2_650);
        RuneliteOverviewView view = new RuneliteOverviewView(
            Collections.emptyList(),
            Collections.singletonList(cached),
            focused,
            null,
            null,
            null,
            1234);

        assertSame(focused, view.opportunityForItem(202));
        assertEquals(2_650, view.opportunityForItem(202).sellPrice);
    }

    @Test
    public void quantityEditorUsesTheSameCashLimitAndHourlyBoundedQuantityAsTheCard()
    {
        RuneliteOverviewView.Opportunity opportunity = opportunity(202, 2_250, 2_500);
        RuneliteOverviewView view = new RuneliteOverviewView(
            Collections.emptyList(),
            Collections.singletonList(opportunity),
            null,
            null,
            null,
            1234);

        assertEquals(20, view.maximumQuantityForItem(202));
        assertEquals(0, view.maximumQuantityForItem(303));
    }

    @Test
    public void selectsTodayMonthAndTotalWithoutSharingMutableLists()
    {
        RuneliteOverviewView.PeriodStats today = new RuneliteOverviewView.PeriodStats(1, 2, 3, 4, 5, 6);
        RuneliteOverviewView.PeriodStats month = new RuneliteOverviewView.PeriodStats(10, 20, 30, 40, 50, 60);
        RuneliteOverviewView.PeriodStats total = new RuneliteOverviewView.PeriodStats(100, 200, 300, 400, 500, 600);
        RuneliteOverviewView view = new RuneliteOverviewView(
            Collections.emptyList(), Collections.emptyList(), today, month, total, 99);

        assertSame(today, view.statsFor("today"));
        assertSame(month, view.statsFor("month"));
        assertSame(total, view.statsFor("total"));
        assertSame(today, view.statsFor("unknown"));
    }

    @Test
    public void periodItemProfitsAreImmutableAndKeepLosses()
    {
        List<RuneliteOverviewView.PeriodItem> items = new ArrayList<>();
        items.add(new RuneliteOverviewView.PeriodItem(573, "Air orb", -120_978, 1));
        RuneliteOverviewView.PeriodStats stats = new RuneliteOverviewView.PeriodStats(
            -120_978, -1, -10, 100, 1_000, 1, items);
        items.clear();

        assertEquals(1, stats.items.size());
        assertEquals(-120_978, stats.items.get(0).realizedProfit);
        assertThrows(UnsupportedOperationException.class, () -> stats.items.clear());
    }

    @Test
    public void carriesAccountwidePriceTestsAndCash()
    {
        LastTradePriceView price = new LastTradePriceView(573, 2_000, 1_800, 10, 11);
        RuneliteOverviewView.CashBalance cash = new RuneliteOverviewView.CashBalance(
            1_000_000, 250_000, 1_250_000, 20);
        RuneliteOverviewView view = new RuneliteOverviewView(
            Collections.emptyList(), Collections.emptyList(), null,
            null, null, null,
            Collections.singletonList(price), cash, 99);

        assertEquals(2_000, view.priceTests.get(0).lastBuyPrice);
        assertEquals(1_000_000, view.cash.available);
        assertEquals(1_250_000, view.cash.total);
        assertThrows(UnsupportedOperationException.class, () -> view.priceTests.clear());
    }

    private static RuneliteOverviewView.Opportunity opportunity(int itemId, int buyPrice, int sellPrice)
    {
        return new RuneliteOverviewView.Opportunity(
            itemId,
            "Test item " + itemId,
            "expected",
            buyPrice,
            sellPrice,
            sellPrice + 1,
            buyPrice - 1,
            10,
            100_001,
            20,
            200_000,
            50_000,
            1234);
    }
}
