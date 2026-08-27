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
        int detailsItemId)
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
        return 0;
    }
}
