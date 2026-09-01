package com.osrsflipper.sync;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.util.Text;

/**
 * Draws elapsed offer time next to the native Buy/Sell label in every occupied
 * Grand Exchange slot. As a canvas overlay it does not add clickable widgets or
 * otherwise change the Grand Exchange interface tree.
 */
final class GeSlotTimerOverlay extends Overlay
{
    private static final int TEXT_GAP = 4;
    private static final int SLOT_PADDING = 3;
    private static final Color FALLBACK_TIMER_COLOR = new Color(0xFF981F);

    private static final int[] SLOT_WIDGET_IDS =
    {
        InterfaceID.GeOffers.INDEX_0,
        InterfaceID.GeOffers.INDEX_1,
        InterfaceID.GeOffers.INDEX_2,
        InterfaceID.GeOffers.INDEX_3,
        InterfaceID.GeOffers.INDEX_4,
        InterfaceID.GeOffers.INDEX_5,
        InterfaceID.GeOffers.INDEX_6,
        InterfaceID.GeOffers.INDEX_7
    };

    private final Client client;
    private final OsrsFlipperSyncPlugin plugin;
    private final SlotRenderCache[] slotRenderCaches = createSlotRenderCaches();

    @Inject
    GeSlotTimerOverlay(Client client, OsrsFlipperSyncPlugin plugin)
    {
        super(plugin);
        this.client = client;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.MANUAL);
        setPriority(OverlayPriority.HIGH);
        drawAfterInterface(InterfaceID.GE_OFFERS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        GrandExchangeOffer[] liveOffers = client.getGrandExchangeOffers();
        if (liveOffers == null || liveOffers.length == 0)
        {
            return null;
        }

        Font previousFont = graphics.getFont();
        Color previousColor = graphics.getColor();
        graphics.setFont(FontManager.getRunescapeSmallFont());

        try
        {
            FontMetrics metrics = graphics.getFontMetrics();
            long nowEpochSeconds = Instant.now().getEpochSecond();
            int slotCount = Math.min(SLOT_WIDGET_IDS.length, liveOffers.length);

            for (int zeroBasedSlot = 0; zeroBasedSlot < slotCount; zeroBasedSlot++)
            {
                Widget slotWidget = client.getWidget(SLOT_WIDGET_IDS[zeroBasedSlot]);
                SlotRenderCache slotCache = slotRenderCaches[zeroBasedSlot];
                slotCache.observeRoot(slotWidget);

                GrandExchangeOffer liveOffer = liveOffers[zeroBasedSlot];
                if (liveOffer == null || liveOffer.getState() == GrandExchangeOfferState.EMPTY ||
                    !isVisible(slotWidget))
                {
                    continue;
                }

                GeSlotTimerView timer = plugin.geSlotTimerView(zeroBasedSlot, liveOffer);
                if (timer == null)
                {
                    continue;
                }

                String sideLabel = timer.sideLabel();
                Widget offerLabel = slotCache.offerLabel(slotWidget, sideLabel);
                if (offerLabel == null)
                {
                    continue;
                }

                Rectangle slotBounds = slotWidget.getBounds();
                Rectangle labelBounds = offerLabel.getBounds();
                if (!hasArea(slotBounds) || !hasArea(labelBounds))
                {
                    continue;
                }

                String timerText = slotCache.timerText(timer, nowEpochSeconds);
                Point location = timerLocation(
                    labelBounds,
                    slotBounds,
                    offerLabel.getXTextAlignment(),
                    metrics.stringWidth(sideLabel),
                    metrics.stringWidth(timerText),
                    metrics.getHeight(),
                    metrics.getAscent());

                Color timerColor = widgetTextColor(offerLabel);
                graphics.setColor(Color.BLACK);
                graphics.drawString(timerText, location.x + 1, location.y + 1);
                graphics.setColor(timerColor);
                graphics.drawString(timerText, location.x, location.y);
            }
        }
        finally
        {
            graphics.setFont(previousFont);
            graphics.setColor(previousColor);
        }

        return null;
    }

    static boolean isMatchingOfferLabelText(String widgetText, String expectedSideLabel)
    {
        if (widgetText == null || expectedSideLabel == null)
        {
            return false;
        }

        String visibleText = Text.removeTags(widgetText).trim();
        return visibleText.equalsIgnoreCase(expectedSideLabel.trim());
    }

    static Point timerLocation(
        Rectangle labelBounds,
        Rectangle slotBounds,
        int labelHorizontalAlignment,
        int labelTextWidth,
        int timerTextWidth,
        int fontHeight,
        int fontAscent)
    {
        int renderedLabelX;
        switch (labelHorizontalAlignment)
        {
            case WidgetTextAlignment.RIGHT:
                renderedLabelX = labelBounds.x + labelBounds.width - labelTextWidth;
                break;
            case WidgetTextAlignment.CENTER:
                renderedLabelX = labelBounds.x + (labelBounds.width - labelTextWidth) / 2;
                break;
            case WidgetTextAlignment.LEFT:
            default:
                renderedLabelX = labelBounds.x;
                break;
        }

        int timerX = renderedLabelX + labelTextWidth + TEXT_GAP;
        int minimumX = slotBounds.x + SLOT_PADDING;
        int maximumX = slotBounds.x + slotBounds.width - timerTextWidth - SLOT_PADDING;
        timerX = Math.max(minimumX, Math.min(timerX, Math.max(minimumX, maximumX)));

        int timerY = labelBounds.y + Math.max(0, (labelBounds.height - fontHeight) / 2) + fontAscent;
        int minimumY = slotBounds.y + fontAscent;
        int maximumY = slotBounds.y + slotBounds.height - SLOT_PADDING;
        timerY = Math.max(minimumY, Math.min(timerY, Math.max(minimumY, maximumY)));

        return new Point(timerX, timerY);
    }

    static boolean isCachedOfferLabelValid(Widget candidate, Widget slotRoot, String expectedSideLabel)
    {
        return isVisible(candidate) &&
            isMatchingOfferLabelText(candidate.getText(), expectedSideLabel) &&
            isDescendantOf(candidate, slotRoot);
    }

    private static Widget findVisibleOfferLabel(Widget root, String expectedSideLabel)
    {
        Deque<Widget> remaining = new ArrayDeque<>();
        addChildren(remaining, root);

        Set<Widget> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!remaining.isEmpty())
        {
            Widget candidate = remaining.removeFirst();
            if (!visited.add(candidate))
            {
                continue;
            }

            if (isCachedOfferLabelValid(candidate, root, expectedSideLabel))
            {
                return candidate;
            }

            addChildren(remaining, candidate);
        }

        return null;
    }

    private static boolean isDescendantOf(Widget candidate, Widget expectedRoot)
    {
        if (candidate == null || expectedRoot == null || candidate == expectedRoot)
        {
            return false;
        }

        Widget current = candidate.getParent();
        for (int depth = 0; current != null && depth < 64; depth++)
        {
            if (current == expectedRoot)
            {
                return true;
            }
            if (current == candidate)
            {
                return false;
            }
            current = current.getParent();
        }
        return false;
    }

    private static void addChildren(Deque<Widget> destination, Widget parent)
    {
        if (parent == null)
        {
            return;
        }

        addChildren(destination, parent.getStaticChildren());
        addChildren(destination, parent.getDynamicChildren());
        addChildren(destination, parent.getNestedChildren());
    }

    private static void addChildren(Deque<Widget> destination, Widget[] children)
    {
        if (children == null)
        {
            return;
        }

        for (Widget child : children)
        {
            if (child != null)
            {
                destination.addLast(child);
            }
        }
    }

    private static boolean isVisible(Widget widget)
    {
        return widget != null && !widget.isHidden() && hasArea(widget.getBounds());
    }

    private static boolean hasArea(Rectangle bounds)
    {
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    private static Color widgetTextColor(Widget widget)
    {
        int rgb = widget.getTextColor() & 0x00FFFFFF;
        return rgb == 0 ? FALLBACK_TIMER_COLOR : new Color(rgb);
    }

    private static SlotRenderCache[] createSlotRenderCaches()
    {
        SlotRenderCache[] caches = new SlotRenderCache[SLOT_WIDGET_IDS.length];
        for (int slot = 0; slot < caches.length; slot++)
        {
            caches[slot] = new SlotRenderCache();
        }
        return caches;
    }

    static final class SlotRenderCache
    {
        private final GeSlotTimerView.TextCache textCache = new GeSlotTimerView.TextCache();
        private Widget slotRoot;
        private Widget offerLabel;

        void observeRoot(Widget currentRoot)
        {
            if (slotRoot == currentRoot)
            {
                return;
            }

            slotRoot = currentRoot;
            offerLabel = null;
            textCache.clear();
        }

        Widget offerLabel(Widget currentRoot, String expectedSideLabel)
        {
            observeRoot(currentRoot);
            if (isCachedOfferLabelValid(offerLabel, currentRoot, expectedSideLabel))
            {
                return offerLabel;
            }

            offerLabel = findVisibleOfferLabel(currentRoot, expectedSideLabel);
            return offerLabel;
        }

        String timerText(GeSlotTimerView view, long nowEpochSeconds)
        {
            return textCache.timerText(view, nowEpochSeconds);
        }
    }
}
