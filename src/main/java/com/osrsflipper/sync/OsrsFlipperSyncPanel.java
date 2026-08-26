package com.osrsflipper.sync;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

public class OsrsFlipperSyncPanel extends PluginPanel
{
    private static final Color GOLD = new Color(226, 182, 99);
    private static final Color GREEN = new Color(95, 211, 133);
    private static final Color RED = new Color(230, 126, 118);
    private static final Color MUTED = new Color(170, 170, 170);
    private static final String SLOTS = "slots";
    private static final String PRICES = "prices";
    private static final String STATS = "stats";
    private static final String SYNC = "sync";

    private final ItemManager itemManager;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JPanel slotsList = verticalPanel();
    private final JPanel pricesList = verticalPanel();
    private final JPanel statsItems = verticalPanel();
    private final Map<String, JButton> tabButtons = new LinkedHashMap<>();
    private final List<OfferCard> offerCards = new ArrayList<>();
    private final JLabel statusValue = new JLabel();
    private final JLabel profitValue = valueLabel("0 GP", GREEN, 22f);
    private final JLabel roiValue = valueLabel("0,00%", Color.WHITE, 12f);
    private final JLabel taxValue = valueLabel("0 GP", Color.WHITE, 12f);
    private final JLabel volumeValue = valueLabel("0 GP", Color.WHITE, 12f);
    private final JLabel flipsValue = valueLabel("0", Color.WHITE, 12f);
    private final JLabel sessionValue = valueLabel("00:00:00", Color.WHITE, 12f);
    private final JLabel hourlyValue = valueLabel("0 GP/u", GREEN, 12f);
    private final Timer displayTimer;

    private List<FlipperOfferView> offers = Collections.emptyList();
    private Map<Integer, MarketPriceView> marketPrices = Collections.emptyMap();
    private SessionStatsTracker.SessionStatsSnapshot stats;

    OsrsFlipperSyncPanel(
        ItemManager itemManager,
        Runnable pairAction,
        Runnable syncAction,
        Runnable webappAction,
        Runnable refreshPricesAction,
        Runnable resetStatsAction)
    {
        this.itemManager = itemManager;
        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel header = verticalPanel();
        JLabel title = new JLabel("OSRS Flip Tracker");
        title.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
        title.setForeground(GOLD);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(6));
        header.add(createTabs());
        add(header, BorderLayout.NORTH);

        cards.setOpaque(false);
        cards.add(scroll(slotsList), SLOTS);
        cards.add(scroll(createPricesPage(refreshPricesAction)), PRICES);
        cards.add(scroll(createStatsPage(resetStatsAction)), STATS);
        cards.add(scroll(createSyncPage(pairAction, syncAction, webappAction)), SYNC);
        add(cards, BorderLayout.CENTER);

        displayTimer = new Timer(1000, event -> updateClocks());
        displayTimer.setRepeats(true);
        displayTimer.start();

        setConnectionStatus("Nog niet gekoppeld");
        rebuildSlots();
        rebuildPrices();
        selectTab(SLOTS);
    }

    void dispose()
    {
        displayTimer.stop();
    }

    void setConnectionStatus(String status)
    {
        String safeStatus = escapeHtml(status == null ? "Onbekende status" : status);
        SwingUtilities.invokeLater(() -> statusValue.setText("<html>" + safeStatus + "</html>"));
    }

    void updateOffers(List<FlipperOfferView> nextOffers)
    {
        List<FlipperOfferView> copy = new ArrayList<>(nextOffers == null
            ? Collections.emptyList()
            : nextOffers);
        copy.sort(Comparator.comparingInt(value -> value.slotNumber));
        SwingUtilities.invokeLater(() ->
        {
            offers = copy;
            rebuildSlots();
            rebuildPrices();
        });
    }

    void updateMarketPrices(Map<Integer, MarketPriceView> nextPrices)
    {
        Map<Integer, MarketPriceView> copy = new LinkedHashMap<>(nextPrices == null
            ? Collections.emptyMap()
            : nextPrices);
        SwingUtilities.invokeLater(() ->
        {
            marketPrices = copy;
            rebuildPrices();
        });
    }

    void updateSessionStats(SessionStatsTracker.SessionStatsSnapshot nextStats)
    {
        SwingUtilities.invokeLater(() ->
        {
            stats = nextStats;
            rebuildStats();
        });
    }

    private JPanel createTabs()
    {
        JPanel tabs = new JPanel(new GridLayout(1, 4, 3, 0));
        tabs.setOpaque(false);
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        addTab(tabs, "Slots", SLOTS);
        addTab(tabs, "Prijzen", PRICES);
        addTab(tabs, "Stats", STATS);
        addTab(tabs, "Sync", SYNC);
        return tabs;
    }

    private void addTab(JPanel tabs, String label, String key)
    {
        JButton button = new JButton(label);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(5, 2, 5, 2));
        button.addActionListener(event -> selectTab(key));
        tabButtons.put(key, button);
        tabs.add(button);
    }

    private void selectTab(String key)
    {
        cardLayout.show(cards, key);
        for (Map.Entry<String, JButton> entry : tabButtons.entrySet())
        {
            boolean selected = entry.getKey().equals(key);
            entry.getValue().setForeground(selected ? GOLD : Color.LIGHT_GRAY);
            entry.getValue().setBackground(selected
                ? ColorScheme.DARKER_GRAY_COLOR
                : ColorScheme.DARK_GRAY_COLOR);
        }
    }

    private JPanel createPricesPage(Runnable refreshPricesAction)
    {
        JPanel page = verticalPanel();
        JPanel heading = new JPanel(new BorderLayout(5, 0));
        heading.setOpaque(false);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel explanation = new JLabel("<html><b>Actieve items</b><br><small>Officiële Wiki-prijzen, zonder D1-reads</small></html>");
        heading.add(explanation, BorderLayout.CENTER);
        JButton refresh = new JButton("Vernieuw");
        refresh.addActionListener(event -> refreshPricesAction.run());
        heading.add(refresh, BorderLayout.EAST);
        page.add(heading);
        page.add(Box.createVerticalStrut(6));
        page.add(pricesList);
        return page;
    }

    private JPanel createStatsPage(Runnable resetStatsAction)
    {
        JPanel page = verticalPanel();
        JLabel heading = new JLabel("Deze RuneLite-sessie");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        heading.setForeground(GOLD);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(heading);

        JLabel hint = new JLabel("<html><small>Winst telt alleen verkopen die gekoppeld kunnen worden aan aankopen die deze sessie zijn gezien.</small></html>");
        hint.setForeground(MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(2, 0, 6, 0));
        page.add(hint);

        JPanel summary = cardPanel();
        profitValue.setHorizontalAlignment(SwingConstants.CENTER);
        summary.add(metric("Gerealiseerde winst", profitValue));
        summary.add(metric("ROI", roiValue));
        summary.add(metric("Winst per uur", hourlyValue));
        summary.add(metric("GE-tax", taxValue));
        summary.add(metric("Handelsvolume", volumeValue));
        summary.add(metric("Voltooide offers", flipsValue));
        summary.add(metric("Sessietijd", sessionValue));
        page.add(summary);
        page.add(Box.createVerticalStrut(7));

        JLabel itemTitle = new JLabel("Items met gerealiseerde winst");
        itemTitle.setFont(itemTitle.getFont().deriveFont(Font.BOLD));
        itemTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(itemTitle);
        page.add(Box.createVerticalStrut(4));
        page.add(statsItems);
        page.add(Box.createVerticalStrut(7));

        JButton reset = new JButton("Sessiestatistieken resetten");
        reset.setAlignmentX(Component.LEFT_ALIGNMENT);
        reset.addActionListener(event -> resetStatsAction.run());
        page.add(reset);
        return page;
    }

    private JPanel createSyncPage(Runnable pairAction, Runnable syncAction, Runnable webappAction)
    {
        JPanel page = verticalPanel();
        JLabel introduction = new JLabel(
            "<html>Beheer de veilige apparaatkoppeling en synchroniseer je acht Grand Exchange-slots.</html>");
        introduction.setAlignmentX(Component.LEFT_ALIGNMENT);
        introduction.setBorder(new EmptyBorder(0, 0, 8, 0));
        page.add(introduction);

        JPanel statusPanel = cardPanel();
        JLabel statusTitle = new JLabel("Verbindingsstatus");
        statusTitle.setFont(statusTitle.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusTitle);
        statusValue.setVerticalAlignment(SwingConstants.TOP);
        statusPanel.add(statusValue);
        page.add(statusPanel);
        page.add(Box.createVerticalStrut(7));

        page.add(actionButton("Apparaat koppelen", "Open de webapp en voer een tijdelijke koppelcode in.", pairAction));
        page.add(Box.createVerticalStrut(4));
        page.add(actionButton("Opnieuw synchroniseren", "Stuur een volledige slotsnapshot.", syncAction));
        page.add(Box.createVerticalStrut(4));
        page.add(actionButton("Webapp openen", "Open de OSRS Flip Tracker-webapp.", webappAction));

        JLabel diagnosticHint = new JLabel(
            "<html><small>Bij problemen kun je in de pluginconfig <b>Uitgebreide logging</b> inschakelen.</small></html>");
        diagnosticHint.setForeground(MUTED);
        diagnosticHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        diagnosticHint.setBorder(new EmptyBorder(8, 0, 0, 0));
        page.add(diagnosticHint);
        return page;
    }

    private void rebuildSlots()
    {
        slotsList.removeAll();
        offerCards.clear();
        if (offers.isEmpty())
        {
            slotsList.add(emptyMessage("Geen actieve GE-offers gevonden."));
        }
        else
        {
            for (FlipperOfferView offer : offers)
            {
                OfferCard card = new OfferCard(offer);
                offerCards.add(card);
                slotsList.add(card.panel);
                slotsList.add(Box.createVerticalStrut(6));
            }
        }
        slotsList.revalidate();
        slotsList.repaint();
    }

    private void rebuildPrices()
    {
        pricesList.removeAll();
        Map<Integer, List<FlipperOfferView>> byItem = new LinkedHashMap<>();
        for (FlipperOfferView offer : offers)
        {
            byItem.computeIfAbsent(offer.itemId, ignored -> new ArrayList<>()).add(offer);
        }
        if (byItem.isEmpty())
        {
            pricesList.add(emptyMessage("Actieve GE-items verschijnen hier automatisch."));
        }
        else
        {
            for (Map.Entry<Integer, List<FlipperOfferView>> entry : byItem.entrySet())
            {
                pricesList.add(priceCard(entry.getValue(), marketPrices.get(entry.getKey())));
                pricesList.add(Box.createVerticalStrut(6));
            }
        }
        pricesList.revalidate();
        pricesList.repaint();
    }

    private void rebuildStats()
    {
        if (stats == null)
        {
            return;
        }
        profitValue.setText(formatSignedGp(stats.realizedProfit));
        profitValue.setForeground(stats.realizedProfit < 0 ? RED : GREEN);
        roiValue.setText(String.format(Locale.US, "%.2f%%", stats.roi()).replace('.', ','));
        taxValue.setText(formatGp(stats.taxPaid));
        volumeValue.setText(formatGp(stats.invested + stats.grossRevenue));
        flipsValue.setText(Integer.toString(stats.completedBuyOffers + stats.completedSellOffers));

        statsItems.removeAll();
        List<SessionStatsTracker.SessionItemStats> itemRows = new ArrayList<>(stats.items.values());
        itemRows.removeIf(item -> item.matchedQuantity <= 0);
        itemRows.sort((left, right) -> Long.compare(Math.abs(right.profit), Math.abs(left.profit)));
        if (itemRows.isEmpty())
        {
            statsItems.add(emptyMessage("Nog geen gekoppelde koop-verkoopresultaten in deze sessie."));
        }
        else
        {
            for (SessionStatsTracker.SessionItemStats item : itemRows)
            {
                statsItems.add(sessionItemCard(item));
                statsItems.add(Box.createVerticalStrut(4));
            }
        }
        updateClocks();
        statsItems.revalidate();
        statsItems.repaint();
    }

    private JPanel priceCard(List<FlipperOfferView> itemOffers, MarketPriceView market)
    {
        FlipperOfferView first = itemOffers.get(0);
        JPanel card = cardPanel();
        card.add(itemHeader(first.itemId, first.itemName));

        JPanel prices = new JPanel(new GridLayout(0, 2, 5, 3));
        prices.setOpaque(false);
        addPair(prices, "Wiki instant buy", market == null ? "Laden…" : priceOrDash(market.instantBuyPrice), GREEN);
        addPair(prices, "Wiki instant sell", market == null ? "Laden…" : priceOrDash(market.instantSellPrice), GREEN);
        for (FlipperOfferView offer : itemOffers)
        {
            String label = "buy".equals(offer.side) ? "Mijn koopoffer" : "Mijn verkoopoffer";
            addPair(prices, label, formatNumber(offer.price) + " gp", Color.WHITE);
        }
        card.add(prices);
        if (market != null)
        {
            long newest = Math.max(market.instantBuyAt, market.instantSellAt);
            JLabel age = new JLabel("Wiki bijgewerkt: " + relativeAge(newest));
            age.setForeground(MUTED);
            age.setFont(age.getFont().deriveFont(10f));
            card.add(age);
        }
        return card;
    }

    private JPanel sessionItemCard(SessionStatsTracker.SessionItemStats item)
    {
        JPanel card = new JPanel(new BorderLayout(6, 0));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(new EmptyBorder(6, 6, 6, 6));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(itemIcon(item.itemId), BorderLayout.WEST);
        JLabel name = new JLabel("<html><b>" + escapeHtml(item.itemName) + "</b><br><small>" +
            formatNumber(item.matchedQuantity) + " gematcht</small></html>");
        card.add(name, BorderLayout.CENTER);
        JLabel profit = new JLabel(formatSignedGp(item.profit));
        profit.setForeground(item.profit < 0 ? RED : GREEN);
        profit.setFont(profit.getFont().deriveFont(Font.BOLD));
        card.add(profit, BorderLayout.EAST);
        return card;
    }

    private JPanel itemHeader(int itemId, String itemName)
    {
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(itemIcon(itemId), BorderLayout.WEST);
        JLabel name = new JLabel("<html><b>" + escapeHtml(itemName) + "</b></html>");
        header.add(name, BorderLayout.CENTER);
        return header;
    }

    private JLabel itemIcon(int itemId)
    {
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(36, 36));
        icon.setMinimumSize(new Dimension(36, 36));
        AsyncBufferedImage image = itemManager.getImage(itemId);
        image.addTo(icon);
        return icon;
    }

    private void updateClocks()
    {
        long now = Instant.now().getEpochSecond();
        for (OfferCard card : offerCards)
        {
            card.updateTime(now);
        }
        if (stats != null)
        {
            sessionValue.setText(formatDuration(Math.max(0, now - stats.startedAt)));
            long hourly = stats.hourlyProfit(now);
            hourlyValue.setText(formatSignedGp(hourly) + "/u");
            hourlyValue.setForeground(hourly < 0 ? RED : GREEN);
        }
    }

    private static JPanel verticalPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    private static JScrollPane scroll(JPanel content)
    {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        return scroll;
    }

    private static JPanel cardPanel()
    {
        JPanel panel = verticalPanel();
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setOpaque(true);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JPanel metric(String title, JLabel value)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        JLabel label = new JLabel(title);
        label.setForeground(MUTED);
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private static JLabel valueLabel(String text, Color color, float size)
    {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size));
        return label;
    }

    private static JButton actionButton(String label, String tooltip, Runnable action)
    {
        JButton button = new JButton(label);
        button.setToolTipText(tooltip);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JLabel emptyMessage(String text)
    {
        JLabel label = new JLabel("<html><div style='text-align:center'>" + escapeHtml(text) + "</div></html>");
        label.setForeground(MUTED);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setBorder(new EmptyBorder(20, 5, 20, 5));
        return label;
    }

    private static void addPair(JPanel panel, String label, String value, Color valueColor)
    {
        JLabel key = new JLabel(label);
        key.setForeground(MUTED);
        panel.add(key);
        JLabel result = new JLabel(value, SwingConstants.RIGHT);
        result.setForeground(valueColor);
        result.setFont(result.getFont().deriveFont(Font.BOLD));
        panel.add(result);
    }

    static String formatDuration(long seconds)
    {
        long safe = Math.max(0, seconds);
        long hours = safe / 3600;
        long minutes = (safe % 3600) / 60;
        long remainder = safe % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainder);
    }

    private static String relativeAge(long epochSeconds)
    {
        if (epochSeconds <= 0)
        {
            return "onbekend";
        }
        long age = Math.max(0, Instant.now().getEpochSecond() - epochSeconds);
        if (age < 60)
        {
            return age + " sec geleden";
        }
        if (age < 3600)
        {
            return age / 60 + " min geleden";
        }
        return age / 3600 + " u geleden";
    }

    private static String priceOrDash(int value)
    {
        return value > 0 ? formatNumber(value) + " gp" : "—";
    }

    private static String formatGp(long value)
    {
        return formatNumber(value) + " GP";
    }

    private static String formatSignedGp(long value)
    {
        return (value > 0 ? "+" : "") + formatNumber(value) + " GP";
    }

    private static String formatNumber(long value)
    {
        String raw = Long.toString(Math.abs(value));
        StringBuilder grouped = new StringBuilder();
        for (int index = 0; index < raw.length(); index++)
        {
            if (index > 0 && (raw.length() - index) % 3 == 0)
            {
                grouped.append(' ');
            }
            grouped.append(raw.charAt(index));
        }
        return value < 0 ? "-" + grouped : grouped.toString();
    }

    private static String escapeHtml(String value)
    {
        return (value == null ? "" : value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private final class OfferCard
    {
        final FlipperOfferView offer;
        final JPanel panel;
        final JLabel elapsed = new JLabel();

        OfferCard(FlipperOfferView offer)
        {
            this.offer = offer;
            panel = cardPanel();

            JPanel top = itemHeader(offer.itemId, offer.itemName);
            elapsed.setForeground(MUTED);
            elapsed.setFont(elapsed.getFont().deriveFont(10f));
            top.add(elapsed, BorderLayout.EAST);
            panel.add(top);

            JPanel detail = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
            detail.setOpaque(false);
            JLabel side = new JLabel("buy".equals(offer.side) ? "Koop" : "Verkoop");
            side.setForeground("buy".equals(offer.side) ? GREEN : GOLD);
            side.setFont(side.getFont().deriveFont(Font.BOLD));
            detail.add(side);
            detail.add(new JLabel("  " + formatNumber(offer.filledQuantity) + " / " +
                formatNumber(offer.totalQuantity) + "  ·  " + statusText(offer.status)));
            panel.add(detail);

            JProgressBar progress = new JProgressBar(0, Math.max(1, offer.totalQuantity));
            progress.setValue(Math.min(offer.totalQuantity, Math.max(0, offer.filledQuantity)));
            progress.setForeground("buy".equals(offer.side) ? GREEN : GOLD);
            progress.setBackground(ColorScheme.DARK_GRAY_COLOR);
            progress.setBorderPainted(false);
            progress.setPreferredSize(new Dimension(180, 7));
            progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
            panel.add(progress);

            JLabel price = new JLabel(formatNumber(offer.price) + " gp per item");
            price.setHorizontalAlignment(SwingConstants.CENTER);
            price.setForeground(Color.LIGHT_GRAY);
            price.setBorder(new EmptyBorder(4, 0, 0, 0));
            panel.add(price);
            updateTime(Instant.now().getEpochSecond());
        }

        void updateTime(long now)
        {
            elapsed.setText(formatDuration(Math.max(0, now - offer.startedAt)));
        }
    }

    private static String statusText(String status)
    {
        if ("partially_filled".equals(status))
        {
            return "gedeeltelijk gevuld";
        }
        if ("completed".equals(status))
        {
            return "voltooid";
        }
        if ("cancelled".equals(status))
        {
            return "geannuleerd";
        }
        return "actief";
    }
}
