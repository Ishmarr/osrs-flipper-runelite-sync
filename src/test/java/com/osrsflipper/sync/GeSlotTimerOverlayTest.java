package com.osrsflipper.sync;

import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GeSlotTimerOverlayTest
{
    @Test
    public void matchesOnlyTheExactVisibleBuyOrSellLabelText()
    {
        assertTrue(GeSlotTimerOverlay.isMatchingOfferLabelText("<col=ff981f>Buy</col>", "Buy"));
        assertTrue(GeSlotTimerOverlay.isMatchingOfferLabelText("  SELL  ", "Sell"));
        assertFalse(GeSlotTimerOverlay.isMatchingOfferLabelText("Buy offer", "Buy"));
        assertFalse(GeSlotTimerOverlay.isMatchingOfferLabelText("Sell", "Buy"));
        assertFalse(GeSlotTimerOverlay.isMatchingOfferLabelText(null, "Buy"));
    }

    @Test
    public void derivesTimerFontTwoPointsSmallerWithSafeMinimum()
    {
        Font base = new Font(Font.DIALOG, Font.BOLD, 12);
        Font smaller = GeSlotTimerOverlay.timerFont(base);
        Font minimum = GeSlotTimerOverlay.timerFont(base.deriveFont(6.0F));

        assertEquals(base.getFamily(), smaller.getFamily());
        assertEquals(base.getStyle(), smaller.getStyle());
        assertEquals(10.0F, smaller.getSize2D(), 0.0F);
        assertEquals(6.0F, minimum.getSize2D(), 0.0F);
    }

    @Test
    public void placesTimerImmediatelyAfterCenteredLabel()
    {
        Point location = GeSlotTimerOverlay.timerLocation(
            new Rectangle(10, 20, 100, 18),
            new Rectangle(10, 10, 160, 100),
            WidgetTextAlignment.CENTER,
            18,
            50,
            12,
            9);

        assertEquals(new Point(73, 32), location);
    }

    @Test
    public void keepsTimerInsideRightEdgeOfSlot()
    {
        Point location = GeSlotTimerOverlay.timerLocation(
            new Rectangle(100, 20, 60, 18),
            new Rectangle(100, 10, 80, 100),
            WidgetTextAlignment.RIGHT,
            20,
            55,
            12,
            9);

        assertEquals(122, location.x);
        assertEquals(32, location.y);
    }

    @Test
    public void cachedLabelMustRemainVisibleExactAndBelowTheSameRoot()
    {
        WidgetStub firstRoot = new WidgetStub("", false, new Rectangle(0, 0, 160, 100));
        WidgetStub firstLabel = new WidgetStub("<col=ff981f>Buy</col>", false,
            new Rectangle(40, 10, 80, 18));
        firstLabel.parent = firstRoot.widget;

        assertTrue(GeSlotTimerOverlay.isCachedOfferLabelValid(
            firstLabel.widget, firstRoot.widget, "Buy"));

        WidgetStub otherRoot = new WidgetStub("", false, new Rectangle(200, 0, 160, 100));
        assertFalse(GeSlotTimerOverlay.isCachedOfferLabelValid(
            firstLabel.widget, otherRoot.widget, "Buy"));

        firstLabel.hidden = true;
        assertFalse(GeSlotTimerOverlay.isCachedOfferLabelValid(
            firstLabel.widget, firstRoot.widget, "Buy"));
    }

    @Test
    public void slotCacheResetsLabelWhenRootIdentityChanges()
    {
        WidgetStub firstRoot = rootWithLabel("Buy", 0);
        WidgetStub secondRoot = rootWithLabel("Buy", 200);
        GeSlotTimerOverlay.SlotRenderCache cache = new GeSlotTimerOverlay.SlotRenderCache();

        Widget first = cache.offerLabel(firstRoot.widget, "Buy");
        Widget again = cache.offerLabel(firstRoot.widget, "Buy");
        Widget second = cache.offerLabel(secondRoot.widget, "Buy");

        assertSame(first, again);
        assertSame(secondRoot.staticChildren[0], second);
        assertFalse(first == second);

        ((WidgetStub) Proxy.getInvocationHandler(second)).parent = firstRoot.widget;
        assertNull(cache.offerLabel(secondRoot.widget, "Buy"));
    }

    private static WidgetStub rootWithLabel(String side, int x)
    {
        WidgetStub root = new WidgetStub("", false, new Rectangle(x, 0, 160, 100));
        WidgetStub label = new WidgetStub(side, false, new Rectangle(x + 40, 10, 80, 18));
        label.parent = root.widget;
        root.staticChildren = new Widget[] {label.widget};
        return root;
    }

    private static final class WidgetStub implements InvocationHandler
    {
        private final Widget widget;
        private final String text;
        private final Rectangle bounds;
        private boolean hidden;
        private Widget parent;
        private Widget[] staticChildren = new Widget[0];

        private WidgetStub(String text, boolean hidden, Rectangle bounds)
        {
            this.text = text;
            this.hidden = hidden;
            this.bounds = bounds;
            this.widget = (Widget) Proxy.newProxyInstance(
                Widget.class.getClassLoader(),
                new Class<?>[] {Widget.class},
                this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments)
        {
            switch (method.getName())
            {
                case "getText":
                    return text;
                case "isHidden":
                    return hidden;
                case "getBounds":
                    return bounds;
                case "getParent":
                    return parent;
                case "getStaticChildren":
                    return staticChildren;
                case "getDynamicChildren":
                case "getNestedChildren":
                    return new Widget[0];
                case "toString":
                    return "WidgetStub(" + text + ")";
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private static Object defaultValue(Class<?> type)
        {
            if (!type.isPrimitive())
            {
                return null;
            }
            if (type == boolean.class)
            {
                return false;
            }
            if (type == byte.class)
            {
                return (byte) 0;
            }
            if (type == short.class)
            {
                return (short) 0;
            }
            if (type == int.class)
            {
                return 0;
            }
            if (type == long.class)
            {
                return 0L;
            }
            if (type == float.class)
            {
                return 0F;
            }
            if (type == double.class)
            {
                return 0D;
            }
            if (type == char.class)
            {
                return '\0';
            }
            return null;
        }
    }
}
