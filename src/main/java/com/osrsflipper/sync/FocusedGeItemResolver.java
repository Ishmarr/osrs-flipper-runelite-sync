package com.osrsflipper.sync;

final class FocusedGeItemResolver
{
    private FocusedGeItemResolver()
    {
    }

    static int resolve(
        boolean setupVisible,
        int setupItemId,
        boolean detailsVisible,
        int detailsItemId)
    {
        if (setupVisible && setupItemId > 0)
        {
            return setupItemId;
        }
        if (detailsVisible && detailsItemId > 0)
        {
            return detailsItemId;
        }
        return 0;
    }
}
