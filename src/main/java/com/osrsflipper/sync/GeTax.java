package com.osrsflipper.sync;

/** Local GE tax and price arithmetic; no session inventory or statistics state. */
final class GeTax
{
    private GeTax() {}

    static int calculateTaxPerItem(int sellPrice, int itemId)
    {
        if (sellPrice < 50 || isTaxExempt(itemId))
        {
            return 0;
        }
        return Math.min(sellPrice / 50, 5_000_000);
    }

    /**
     * Tradeable item IDs; never infer an exemption from a display name.
     * Rules: Jagex Grand Exchange Tax & Item Sink (9 Dec 2021), and
     * Yama CAs & More! (29 May 2025). Item list cross-checked against the
     * OSRS Wiki Grand Exchange exemption list and RuneLite ItemID (1.12.37).
     * https://secure.runescape.com/m=news/grand-exchange-tax--item-sink?oldschool=1
     * https://secure.runescape.com/m=news/yama-cas--more?oldschool=1
     * https://oldschool.runescape.wiki/w/Grand_Exchange#Exempt_from_tax
     */
    private static boolean isTaxExempt(int itemId)
    {
        switch (itemId)
        {
            case 13190: // Old school bond
            case 233: // Pestle and mortar
            case 952: // Spade
            case 1733: // Needle
            case 1735: // Shears
            case 1755: // Chisel
            case 1785: // Glassblowing pipe
            case 2347: // Hammer
            case 5325: // Gardening trowel
            case 5329: // Secateurs
            case 5331: // Watering can (0)
            case 5341: // Rake
            case 5343: // Seed dibber
            case 8794: // Saw
            case 3008: // Energy potion (4)
            case 3010: // Energy potion (3)
            case 3012: // Energy potion (2)
            case 3014: // Energy potion (1)
            case 558: // Mind rune
            case 806: // Bronze dart
            case 807: // Iron dart
            case 808: // Steel dart
            case 882: // Bronze arrow
            case 884: // Iron arrow
            case 886: // Steel arrow
            case 315: // Shrimps
            case 329: // Salmon
            case 347: // Herring
            case 351: // Pike
            case 355: // Mackerel
            case 361: // Tuna
            case 365: // Bass
            case 379: // Lobster
            case 1891: // Cake
            case 2140: // Cooked chicken
            case 2142: // Cooked meat
            case 2309: // Bread
            case 2327: // Meat pie
            case 2552: // Ring of dueling (8)
            case 3853: // Games necklace (8)
            case 8007: // Varrock teleport
            case 8008: // Lumbridge teleport
            case 8009: // Falador teleport
            case 8010: // Camelot teleport
            case 8011: // Ardougne teleport
            case 8013: // Teleport to house
            case 28790: // Kourend castle teleport
            case 28824: // Civitas illa fortis teleport
                return true;
            default:
                return false;
        }
    }

    static long calculateProfitPerItem(int buyPrice, int sellPrice, int itemId)
    {
        if (buyPrice <= 0 || sellPrice <= 0)
        {
            return 0L;
        }
        return (long) sellPrice - buyPrice - calculateTaxPerItem(sellPrice, itemId);
    }

    static int calculateLowestBreakEvenSellPrice(int buyPrice, int itemId)
    {
        if (buyPrice <= 0)
        {
            return 0;
        }

        long low = buyPrice;
        long high = Math.min((long) Integer.MAX_VALUE, (long) buyPrice + 5_000_000L);
        if (high - calculateTaxPerItem((int) high, itemId) < buyPrice)
        {
            return 0;
        }

        while (low < high)
        {
            long middle = low + ((high - low) / 2L);
            long netSellPrice = middle - calculateTaxPerItem((int) middle, itemId);
            if (netSellPrice >= buyPrice)
            {
                high = middle;
            }
            else
            {
                low = middle + 1L;
            }
        }
        return (int) low;
    }
}
