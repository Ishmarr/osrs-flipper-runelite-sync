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
        if (detailsVisible && detailsItemId > 0)
        {
            return detailsItemId;
        }
        if (detailsVisible && selectedOfferItemId > 0)
        {
            return selectedOfferItemId;
        }
        return 0;
    }
}
