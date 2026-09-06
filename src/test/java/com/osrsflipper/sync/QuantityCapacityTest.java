package com.osrsflipper.sync;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuantityCapacityTest
{
    private static RuneliteOverviewView.Opportunity scanner(QuantityCapacity capacity, int used)
    {
        return new RuneliteOverviewView.Opportunity(6737, "Berserker ring", "focus",
            3_900_000, 3_913_491, 3_913_492, 3_899_999, 0, 0, 0, 0, 0,
            100, 0, 8, used, 8 - used, capacity, "");
    }

    private static RuneliteOverviewView.Opportunity resolve(QuantityCapacity capacity, int used, long cash)
    {
        return SelectedGeOpportunityResolver.resolve(FocusedGeItemResolver.EditorContext.NEW_SETUP,
            6737, "Berserker ring", "buy", scanner(capacity, used),
            new MarketPriceView(6737, 3_913_492, 3_804_267, 200, 200, 200),
            null, null, null, cash).opportunity;
    }

    @Test
    public void newWikiPricesReplaceTheOldZeroUsingActualCashLimitAndHourlyVolumes()
    {
        QuantityCapacity capacity = new QuantityCapacity(0, 34, 35, 3_942_506);
        RuneliteOverviewView.Opportunity updated = resolve(capacity, 0, 100_000_000);
        assertEquals(8, updated.maximumQuantity);
        assertEquals(247_632, updated.maximumCycleProfit);
        assertEquals(247_632, OsrsFlipperSyncPanel.displayedCycleProfit(updated, 3_804_268, 3_913_491));
        assertEquals("8", OsrsFlipperSyncPanel.quantityText(updated));
        assertEquals("", updated.quantityReason);
        assertEquals(2, resolve(capacity, 0, 2L * 3_804_268).maximumQuantity);
        assertEquals(1, resolve(new QuantityCapacity(100_000_000, 34, 0.75, 3_942_506), 0, 100_000_000).maximumQuantity);
    }

    @Test
    public void trueZeroExplainsTheSpecificConstraintInsteadOfForcingOneOrEight()
    {
        QuantityCapacity capacity = new QuantityCapacity(100_000_000, 34, 35, 3_942_506);
        assertEquals("Onvoldoende beschikbare cash", resolve(capacity, 0, 3_804_267).quantityReason);
        assertEquals("Buy limit volledig gebruikt", resolve(capacity, 8, 100_000_000).quantityReason);
        assertEquals("Onvoldoende recent handelsvolume",
            resolve(new QuantityCapacity(100_000_000, 0, 35, 3_942_506), 0, 100_000_000).quantityReason);
        assertEquals("Prijs boven GE-richtprijs",
            resolve(new QuantityCapacity(100_000_000, 34, 35, 3_800_000), 0, 100_000_000).quantityReason);
        assertEquals(0, resolve(capacity, 8, 100_000_000).maximumQuantity);
        assertEquals(0, resolve(capacity, 0, -100).maximumQuantity);
    }

    @Test
    public void missingCapacityAfterPriceChangeIsUnknownAndInvalidInputsAreNeverExecutable()
    {
        RuneliteOverviewView.Opportunity legacy = resolve(null, 0, 100_000_000);
        assertFalse(legacy.hasQuantity());
        assertEquals("Niet beschikbaar", OsrsFlipperSyncPanel.quantityText(legacy));
        assertEquals("Alleen prijsadvies", OsrsFlipperSyncPanel.cycleProfitText(legacy, 0));
        assertFalse(resolve(new QuantityCapacity(100_000_000, Double.NaN, 35, 3_942_506), 0, 100_000_000).hasQuantity());
        assertFalse(resolve(new QuantityCapacity(100_000_000, 34, 35, 0), 0, 100_000_000).hasQuantity());
    }
}
