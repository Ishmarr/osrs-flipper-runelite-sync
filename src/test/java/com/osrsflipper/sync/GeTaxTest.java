package com.osrsflipper.sync;

import org.junit.Test;

import static org.junit.Assert.*;

public class GeTaxTest
{
    @Test
    public void geTaxUsesTwoPercentRoundedDownAndThePerItemCap()
    {
        assertEquals(0, GeTax.calculateTaxPerItem(49, 4151));
        assertEquals(1, GeTax.calculateTaxPerItem(50, 4151));
        assertEquals(20, GeTax.calculateTaxPerItem(1_049, 4151));
        assertEquals(4_999_999, GeTax.calculateTaxPerItem(249_999_999, 4151));
        assertEquals(5_000_000, GeTax.calculateTaxPerItem(250_000_000, 4151));
        assertEquals(5_000_000, GeTax.calculateTaxPerItem(Integer.MAX_VALUE, 4151));
        assertEquals(0, GeTax.calculateTaxPerItem(15_000_000, 13190));
    }

    @Test
    public void profitPerItemUsesTheDisplayedPricesAndGeTax()
    {
        assertEquals(176L,
            GeTax.calculateProfitPerItem(1_000, 1_200, 4151));
        assertEquals(-20L,
            GeTax.calculateProfitPerItem(1_000, 1_000, 4151));
        assertEquals(200L,
            GeTax.calculateProfitPerItem(1_000, 1_200, 13190));
        assertEquals(0L,
            GeTax.calculateProfitPerItem(0, 1_200, 4151));
    }

    @Test
    public void lowestSellPriceBreaksEvenAfterGeTax()
    {
        assertEquals(49, GeTax.calculateLowestBreakEvenSellPrice(49, 4151));
        assertEquals(51, GeTax.calculateLowestBreakEvenSellPrice(50, 4151));
        assertEquals(1_020, GeTax.calculateLowestBreakEvenSellPrice(1_000, 4151));
        assertEquals(15_000_000,
            GeTax.calculateLowestBreakEvenSellPrice(15_000_000, 13190));
        assertEquals(0, GeTax.calculateLowestBreakEvenSellPrice(0, 4151));
    }

    @Test
    public void hammerHasNoTaxOrArtificialBreakEvenPremium()
    {
        assertEquals(0, GeTax.calculateTaxPerItem(1_200, 2347));
        assertEquals(200, GeTax.calculateProfitPerItem(1_000, 1_200, 2347));
        assertEquals(1_000, GeTax.calculateLowestBreakEvenSellPrice(1_000, 2347));
        assertEquals(Integer.MAX_VALUE,
            GeTax.calculateLowestBreakEvenSellPrice(Integer.MAX_VALUE, 2347));
    }

    @Test
    public void breakEvenIsTheLowestSufficientPriceAcrossRoundingAndCapBoundaries()
    {
        for (int itemId : new int[] {4151, 2347, 13190})
        {
            for (int cost : new int[] {1, 49, 50, 51, 98, 99, 1_000,
                244_999_999, 245_000_000, 245_000_001,
                Integer.MAX_VALUE - 5_000_000, Integer.MAX_VALUE})
            {
                int floor = GeTax.calculateLowestBreakEvenSellPrice(cost, itemId);
                if (floor == 0)
                {
                    assertTrue((long) Integer.MAX_VALUE -
                        GeTax.calculateTaxPerItem(Integer.MAX_VALUE, itemId) < cost);
                }
                else
                {
                    assertTrue(GeTax.calculateProfitPerItem(cost, floor, itemId) >= 0);
                    assertTrue((long) floor - 1 -
                        GeTax.calculateTaxPerItem(floor - 1, itemId) < cost);
                }
            }
        }
    }

    @Test
    public void utilityExemptionsIncludeAllPotionDosesAndDoNotExtendToSimilarItems()
    {
        for (int itemId : new int[] {233, 952, 1733, 1735, 1755, 1785, 2347,
            5325, 5329, 5331, 5341, 5343, 8794, 13190, 3008, 3010, 3012, 3014,
            558, 806, 807, 808, 882, 884, 886, 315, 329, 347, 351, 355, 361,
            365, 379, 1891, 2140, 2142, 2309, 2327, 2552, 3853, 8007, 8008,
            8009, 8010, 8011, 8013, 28790, 28824})
        {
            assertEquals("Exempt item " + itemId, 0,
                GeTax.calculateTaxPerItem(1_000, itemId));
        }
        // Shark, trout, super energy, rune arrow and Watchtower teleport are
        // not exempt merely because another food, potion, ammo or tablet is.
        for (int itemId : new int[] {385, 333, 3016, 892, 8012, 0, -1})
        {
            assertEquals("Taxable item " + itemId, 20,
                GeTax.calculateTaxPerItem(1_000, itemId));
        }
    }

}
