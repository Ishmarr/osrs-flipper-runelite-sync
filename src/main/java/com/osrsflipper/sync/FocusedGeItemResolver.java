package com.osrsflipper.sync;

import java.util.Locale;
import java.util.List;
import net.runelite.api.GrandExchangeOfferState;

final class FocusedGeItemResolver
{
    enum EditorContext
    {
        NONE,
        NEW_SETUP,
        EXISTING_OFFER
    }

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

    static EditorContext editorContext(
        boolean setupVisible,
        boolean detailsVisible,
        EditorContext previousContext,
        int selectedSlot,
        int previousExistingSlot,
        boolean exactSelectedOffer)
    {
        if (detailsVisible)
        {
            return exactSelectedOffer ? EditorContext.EXISTING_OFFER : EditorContext.NONE;
        }
        if (!setupVisible)
        {
            return EditorContext.NONE;
        }
        return previousContext == EditorContext.EXISTING_OFFER &&
            selectedSlot > 0 && selectedSlot == previousExistingSlot && exactSelectedOffer
            ? EditorContext.EXISTING_OFFER
            : EditorContext.NEW_SETUP;
    }

    static int priceEditorItemId(int setupItemId, int focusedItemId, int searchedItemId)
    {
        if (setupItemId > 0)
        {
            return setupItemId;
        }
        if (focusedItemId > 0)
        {
            return focusedItemId;
        }
        return Math.max(0, searchedItemId);
    }

    static FlipperOfferView exactSelectedOffer(
        int selectedSlot,
        int selectedOfferItemId,
        GrandExchangeOfferState selectedOfferState,
        int editorItemId,
        String editorSide,
        List<FlipperOfferView> candidates)
    {
        if (selectedSlot <= 0 || selectedOfferState == null ||
            selectedOfferState == GrandExchangeOfferState.EMPTY ||
            selectedOfferItemId <= 0 || selectedOfferItemId != editorItemId)
        {
            return null;
        }
        String selectedSide = resolveSide(false, "", true, selectedOfferState);
        if (selectedSide.isEmpty() || !selectedSide.equals(editorSide) || candidates == null)
        {
            return null;
        }
        for (FlipperOfferView candidate : candidates)
        {
            if (candidate != null && candidate.slotNumber == selectedSlot &&
                candidate.itemId == editorItemId && selectedSide.equals(candidate.side) &&
                !"empty".equals(candidate.status))
            {
                return candidate;
            }
        }
        return null;
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
