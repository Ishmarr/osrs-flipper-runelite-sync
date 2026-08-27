package com.osrsflipper.sync;

import java.util.Locale;
import net.runelite.api.GrandExchangeOfferState;

final class FocusedGeItemResolver
{
    private FocusedGeItemResolver()
    {
    }

    static int resolve(
        boolean setupVisible,
        int setupItemId,
        int searchedItemId,
        boolean detailsVisible,
        int detailsItemId,
        int selectedOfferItemId)
    {
        if (setupVisible && setupItemId > 0)
        {
            return setupItemId;
        }
        if (setupVisible && searchedItemId > 0)
        {
            return searchedItemId;
        }
        if (detailsVisible && selectedOfferItemId > 0)
        {
            return selectedOfferItemId;
        }
        if (detailsVisible && detailsItemId > 0)
        {
            return detailsItemId;
        }
        return 0;
    }

    static int selectedOfferIndex(int selectedSlot, int offerCount)
    {
        int index = selectedSlot - 1;
        return index >= 0 && index < offerCount ? index : -1;
    }

    static String resolveSide(
        boolean setupVisible,
        String setupText,
        boolean detailsVisible,
        GrandExchangeOfferState selectedOfferState)
    {
        if (setupVisible)
        {
            String normalized = setupText == null
                ? ""
                : setupText.toLowerCase(Locale.ROOT);
            if (normalized.contains("buy offer"))
            {
                return "buy";
            }
            if (normalized.contains("sell offer"))
            {
                return "sell";
            }
        }
        if (!detailsVisible || selectedOfferState == null)
        {
            return "";
        }
        switch (selectedOfferState)
        {
            case BUYING:
            case BOUGHT:
            case CANCELLED_BUY:
                return "buy";
            case SELLING:
            case SOLD:
            case CANCELLED_SELL:
                return "sell";
            default:
                return "";
        }
    }
}
