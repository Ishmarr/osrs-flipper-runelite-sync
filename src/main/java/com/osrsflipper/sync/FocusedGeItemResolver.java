package com.osrsflipper.sync;

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
}
