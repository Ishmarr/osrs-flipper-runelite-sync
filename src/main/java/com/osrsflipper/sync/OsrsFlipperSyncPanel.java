package com.osrsflipper.sync;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
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
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
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
    private static final Color BLUE = new Color(102, 190, 235);
    private static final Color MUTED = new Color(170, 170, 170);
    private static final float DETAIL_FONT_SIZE = 12.5f;
    private static final int DETAIL_ROW_HEIGHT = 23;
    private static final String SLOTS = "slots";
    private static final String OPPORTUNITIES = "opportunities";
    private static final String STATS = "stats";
    private static final String SYNC = "sync";

    private final ItemManager itemManager;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final JPanel slotsList = verticalPanel();
    private final JPanel opportunitiesList = verticalPanel();
    private final Map<String, JButton> tabButtons = new LinkedHashMap<>();
    private final List<OfferCard> offerCards = new ArrayList<>();
    private final JLabel statusValue = wrapLabel("", 158);
    private final JLabel statsProfit = valueLabel("0 GP", GREEN, 15f);
    private final JLabel statsRoi = valueLabel("0,00%", Color.WHITE, 11f);
    private final JLabel statsHourly = valueLabel("0 GP/u", GREEN, 11f);
    private final JLabel statsTax = valueLabel("0 GP", Color.WHITE, 11f);
    private final JLabel statsVolume = valueLabel("0 GP", Color.WHITE, 11f);
    private final JLabel statsFlips = valueLabel("0", Color.WHITE, 11f);
    private final JComboBox<PeriodChoice> statsPeriod = new JComboBox<>(PeriodChoice.values());
    private final Timer displayTimer;

    private List<FlipperOfferView> offers = Collections.emptyList();
    private RuneliteOverviewView overview = RuneliteOverviewView.empty();

    OsrsFlipperSyncPanel(
        ItemManager itemManager,
        Runnable pairAction,
        Runnable syncAction,
        Runnable webappAction,
        Runnable refreshOverviewAction)
    {
        this.itemManager = itemManager;
        setLayout(new BorderLayout(0, 7));
        setBorder(new EmptyBorder(7, 6, 7, 6));

        JPanel header = verticalPanel();
        JLabel title = new JLabel("OSRS Flip Tracker");
        title.setFont(FontManager.getRunescapeBoldFont().deriveFont(17f));
        title.setForeground(GOLD);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(5));
        header.add(createTabs());
        add(header, BorderLayout.NORTH);

        cards.setOpaque(false);
        cards.add(scroll(slotsList), SLOTS);
        cards.add(scroll(createOpportunitiesPage(refreshOverviewAction)), OPPORTUNITIES);
        cards.add(scroll(createStatsPage()), STATS);
        cards.add(scroll(createSyncPage(pairAction, syncAction, webappAction)), SYNC);
        add(cards, BorderLayout.CENTER);

        displayTimer = new Timer(1000, event -> updateClocks());
        displayTimer.setRepeats(true);
        displayTimer.start();

        setConnectionStatus("Nog niet gekoppeld");
        rebuildSlots();
        rebuildOpportunities();
        rebuildStats();
        selectTab(SLOTS);
    }

    void dispose()
    {
        displayTimer.stop();
    }

    void setConnectionStatus(String status)
    {
        SwingUtilities.invokeLater(() -> statusValue.setText(html(
            escapeHtml(status == null ? "Onbekende status" : status), 158)));
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
        });
    }

    void updateOverview(RuneliteOverviewView nextOverview)
    {
        SwingUtilities.invokeLater(() ->
        {
            overview = nextOverview == null ? RuneliteOverviewView.empty() : nextOverview;
            rebuildOpportunities();
            rebuildStats();
        });
    }

    private JPanel createTabs()
    {
        JPanel tabs = new JPanel(new GridLayout(1, 4, 2, 0));
        tabs.setOpaque(false);
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        addTab(tabs, "Slots", SLOTS);
        addTab(tabs, "Kansen", OPPORTUNITIES);
        addTab(tabs, "Stats", STATS);
        addTab(tabs, "Sync", SYNC);
        return tabs;
    }

    private void addTab(JPanel tabs, String label, String key)
    {
        JButton button = new JButton(label);
        button.setFocusable(false);
        button.setFont(button.getFont().deriveFont(10f));
        button.setMargin(new java.awt.Insets(5, 1, 5, 1));
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

    private JPanel createOpportunitiesPage(Runnable refreshAction)
    {
        JPanel page = verticalPanel();
        JPanel heading = new JPanel(new BorderLayout(4, 0));
        heading.setOpaque(false);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel text = wrapLabel("<b>Beste flips</b><br><small>Zelfde model als de webscanner</small>", 150);
        heading.add(text, BorderLayout.CENTER);
        JButton refresh = new JButton("↻");
        refresh.setToolTipText("Kansen en statistieken vernieuwen");
        refresh.setPreferredSize(new Dimension(34, 28));
        refresh.addActionListener(event -> refreshAction.run());
        heading.add(refresh, BorderLayout.EAST);
        page.add(heading);
        page.add(Box.createVerticalStrut(6));
        page.add(opportunitiesList);
        return page;
    }

    private JPanel createStatsPage()
    {
        JPanel page = verticalPanel();
        JLabel heading = new JLabel("Website-statistieken");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        heading.setForeground(GOLD);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(heading);
        page.add(Box.createVerticalStrut(4));

        statsPeriod.setAlignmentX(Component.LEFT_ALIGNMENT);
        statsPeriod.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        statsPeriod.addActionListener(event -> rebuildStats());
        page.add(statsPeriod);
        page.add(Box.createVerticalStrut(7));

        JPanel summary = cardPanel();
        summary.add(metric("Winst", statsProfit));
        summary.add(metric("ROI", statsRoi));
        summary.add(metric("Gem. GP/u", statsHourly));
        summary.add(metric("GE-tax", statsTax));
        summary.add(metric("Volume", statsVolume));
        summary.add(metric("Flips", statsFlips));
        page.add(summary);

        JLabel hint = wrapLabel(
            "<small>Gebaseerd op afgeronde flips in de website. Prijstests en eigen gebruik tellen niet mee.</small>",
            176);
        hint.setForeground(MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(7, 0, 0, 0));
        page.add(hint);
        return page;
    }

    private JPanel createSyncPage(Runnable pairAction, Runnable syncAction, Runnable webappAction)
    {
        JPanel page = verticalPanel();
        JLabel introduction = wrapLabel(
            "Beheer de veilige apparaatkoppeling en synchroniseer je acht GE-slots.", 176);
        introduction.setAlignmentX(Component.LEFT_ALIGNMENT);
        introduction.setBorder(new EmptyBorder(0, 0, 7, 0));
        page.add(introduction);

        JPanel statusPanel = cardPanel();
        JLabel statusTitle = new JLabel("Verbinding");
        statusTitle.setFont(statusTitle.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusTitle);
        statusValue.setForeground(Color.LIGHT_GRAY);
        statusPanel.add(statusValue);
        page.add(statusPanel);
        page.add(Box.createVerticalStrut(7));

        page.add(actionBlock("Koppelen", "Open de webapp en voer een tijdelijke code in.", pairAction));
        page.add(Box.createVerticalStrut(5));
        page.add(actionBlock("Synchroniseren", "Stuur de volledige toestand van alle GE-slots.", syncAction));
        page.add(Box.createVerticalStrut(5));
        page.add(actionBlock("Webapp openen", "Open de OSRS Flip Tracker in je browser.", webappAction));

        JLabel diagnosticHint = wrapLabel(
            "<small>Bij problemen kun je in de pluginconfig uitgebreide logging inschakelen.</small>", 176);
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

    private void rebuildOpportunities()
    {
        opportunitiesList.removeAll();
        if (overview.generatedAt <= 0 && overview.expected.isEmpty() && overview.hourly.isEmpty())
        {
            opportunitiesList.add(emptyMessage("Persoonlijke kansen worden opgehaald..."));
            opportunitiesList.revalidate();
            opportunitiesList.repaint();
            return;
        }
        addOpportunitySection("Top verwachte winst", overview.expected, true);
        opportunitiesList.add(Box.createVerticalStrut(8));
        addOpportunitySection("Top winst per uur", overview.hourly, false);
        if (overview.generatedAt > 0)
        {
            JLabel age = new JLabel("Bijgewerkt " + relativeAge(overview.generatedAt));
            age.setForeground(MUTED);
            age.setFont(age.getFont().deriveFont(10f));
            age.setAlignmentX(Component.LEFT_ALIGNMENT);
            age.setBorder(new EmptyBorder(6, 1, 2, 1));
            opportunitiesList.add(age);
        }
        opportunitiesList.revalidate();
        opportunitiesList.repaint();
    }

    private void addOpportunitySection(
        String title,
        List<RuneliteOverviewView.Opportunity> opportunities,
        boolean expected)
    {
        JLabel heading = new JLabel(title);
        heading.setForeground(GOLD);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        opportunitiesList.add(heading);
        opportunitiesList.add(Box.createVerticalStrut(4));
        if (opportunities == null || opportunities.isEmpty())
        {
            opportunitiesList.add(emptyMessage(expected
                ? "Geen verwachte winst strikt boven 100k GP."
                : "Nog geen uitvoerbare uurkansen."));
            return;
        }
        int rank = 1;
        for (RuneliteOverviewView.Opportunity opportunity : opportunities)
        {
            opportunitiesList.add(opportunityCard(opportunity, expected, rank++));
            opportunitiesList.add(Box.createVerticalStrut(5));
        }
    }

    private JPanel opportunityCard(
        RuneliteOverviewView.Opportunity opportunity,
        boolean expected,
        int rank)
    {
        JPanel card = cardPanel();
        JPanel header = itemHeader(opportunity.itemId, opportunity.itemName);
        JLabel rankLabel = new JLabel("#" + rank);
        rankLabel.setForeground(MUTED);
        rankLabel.setFont(rankLabel.getFont().deriveFont(Font.BOLD));
        header.add(rankLabel, BorderLayout.EAST);
        card.add(header);

        long mainValue = expected ? opportunity.expectedProfit : opportunity.maximumProfitPerHour;
        JLabel profit = new JLabel(formatGp(mainValue) + (expected ? "" : "/u"));
        profit.setForeground(GREEN);
        profit.setFont(profit.getFont().deriveFont(Font.BOLD, 15f));
        profit.setAlignmentX(Component.LEFT_ALIGNMENT);
        profit.setBorder(new EmptyBorder(3, 0, 3, 0));
        card.add(profit);

        int quantity = expected ? opportunity.expectedQuantity : opportunity.maximumQuantity;
        card.add(compactMetric("Aantal", formatNumber(quantity)));
        card.add(compactMetric("Koop", priceOrDash(opportunity.buyPrice)));
        card.add(compactMetric("Verkoop", priceOrDash(opportunity.sellPrice)));
        card.add(compactMetric("Nu instabuy", priceOrDash(opportunity.instantBuy)));
        card.add(compactMetric("Nu instasell", priceOrDash(opportunity.instantSell)));
        if (!expected)
        {
            card.add(compactMetric("Cycluswinst", formatGp(opportunity.maximumCycleProfit)));
        }
        return card;
    }

    private void rebuildStats()
    {
        PeriodChoice selected = (PeriodChoice) statsPeriod.getSelectedItem();
        RuneliteOverviewView.PeriodStats stats = overview.statsFor(selected == null ? "today" : selected.key);
        statsProfit.setText(formatSignedGp(stats.realizedProfit));
        statsProfit.setForeground(stats.realizedProfit < 0 ? RED : GREEN);
        statsRoi.setText(String.format(Locale.US, "%.2f%%", stats.roiPercent).replace('.', ','));
        statsRoi.setForeground(stats.roiPercent < 0 ? RED : Color.WHITE);
        statsHourly.setText(formatSignedGp(stats.profitPerHour) + "/u");
        statsHourly.setForeground(stats.profitPerHour < 0 ? RED : GREEN);
        statsTax.setText(formatGp(stats.geTax));
        statsVolume.setText(formatGp(stats.tradingVolume));
        statsFlips.setText(Integer.toString(stats.completedFlips));
    }

    private JPanel itemHeader(int itemId, String itemName)
    {
        JPanel header = new JPanel(new BorderLayout(5, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(itemIcon(itemId), BorderLayout.WEST);
        JLabel name = wrapLabel("<b>" + escapeHtml(itemName) + "</b>", 120);
        header.add(name, BorderLayout.CENTER);
        return header;
    }

    private JLabel itemIcon(int itemId)
    {
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(32, 32));
        icon.setMinimumSize(new Dimension(32, 32));
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
    }

    private static JPanel verticalPanel()
    {
        return new WidthTrackingPanel();
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
            new EmptyBorder(7, 7, 7, 7)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    private static JPanel metric(String title, JLabel value)
    {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMinimumSize(new Dimension(0, DETAIL_ROW_HEIGHT));
        row.setPreferredSize(new Dimension(180, DETAIL_ROW_HEIGHT));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, DETAIL_ROW_HEIGHT));
        JLabel label = new JLabel(title);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(DETAIL_FONT_SIZE));
        row.add(label, BorderLayout.WEST);
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private static JPanel compactMetric(String title, String value)
    {
        return metric(title, valueLabel(value, Color.WHITE, DETAIL_FONT_SIZE));
    }

    private static JLabel valueLabel(String text, Color color, float size)
    {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size));
        return label;
    }

    private static JPanel actionBlock(String label, String description, Runnable action)
    {
        JPanel block = verticalPanel();
        block.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton button = new JButton(label);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        button.addActionListener(event -> action.run());
        block.add(button);
        JLabel hint = wrapLabel("<small>" + escapeHtml(description) + "</small>", 176);
        hint.setForeground(MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(2, 2, 0, 2));
        block.add(hint);
        return block;
    }

    private static JLabel emptyMessage(String text)
    {
        JLabel label = wrapLabel("<div style='text-align:center'>" + escapeHtml(text) + "</div>", 170);
        label.setForeground(MUTED);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(12, 4, 12, 4));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        return label;
    }

    private static JLabel wrapLabel(String value, int width)
    {
        return new JLabel(html(value, width));
    }

    private static String html(String value, int width)
    {
        return "<html><div style='width:" + width + "px'>" + (value == null ? "" : value) + "</div></html>";
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
            JLabel slot = new JLabel("S" + offer.slotNumber);
            slot.setForeground(MUTED);
            slot.setFont(slot.getFont().deriveFont(Font.BOLD, 10f));
            top.add(slot, BorderLayout.EAST);
            panel.add(top);

            JPanel state = new JPanel(new BorderLayout(4, 0));
            state.setOpaque(false);
            state.setMaximumSize(new Dimension(Integer.MAX_VALUE, 23));
            JLabel side = new JLabel("buy".equals(offer.side) ? "Koop" : "Verkoop");
            side.setForeground("buy".equals(offer.side) ? GREEN : GOLD);
            side.setFont(side.getFont().deriveFont(Font.BOLD));
            state.add(side, BorderLayout.WEST);
            elapsed.setForeground(MUTED);
            elapsed.setFont(elapsed.getFont().deriveFont(10f));
            state.add(elapsed, BorderLayout.EAST);
            panel.add(state);
            panel.add(compactMetric("Gevuld", formatNumber(offer.filledQuantity) + " / " + formatNumber(offer.totalQuantity)));
            panel.add(compactMetric("Offerprijs", priceOrDash(offer.price)));
            if (offer.wikiInstantBuyPrice > 0)
            {
                JPanel wiki = compactMetric("Wiki instabuy", priceOrDash(offer.wikiInstantBuyPrice));
                Component value = ((BorderLayout) wiki.getLayout()).getLayoutComponent(BorderLayout.EAST);
                if (value != null)
                {
                    value.setForeground(GREEN);
                }
                panel.add(wiki);
            }
            if ("buy".equals(offer.side) && offer.suggestedSellPrice > 0)
            {
                JPanel target = compactMetric("Verkoopprijs", priceOrDash(offer.suggestedSellPrice));
                Component value = ((BorderLayout) target.getLayout()).getLayoutComponent(BorderLayout.EAST);
                if (value != null)
                {
                    value.setForeground(BLUE);
                }
                panel.add(target);
            }

            JProgressBar progress = new JProgressBar(0, Math.max(1, offer.totalQuantity));
            progress.setValue(Math.min(offer.totalQuantity, Math.max(0, offer.filledQuantity)));
            progress.setForeground("buy".equals(offer.side) ? GREEN : GOLD);
            progress.setBackground(ColorScheme.DARK_GRAY_COLOR);
            progress.setBorderPainted(false);
            progress.setPreferredSize(new Dimension(170, 6));
            progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
            progress.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(progress);
            updateTime(Instant.now().getEpochSecond());
        }

        void updateTime(long now)
        {
            elapsed.setText(formatDuration(Math.max(0, now - offer.startedAt)));
        }
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable
    {
        WidthTrackingPanel()
        {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }

    private enum PeriodChoice
    {
        TODAY("today", "Vandaag"),
        MONTH("month", "Deze maand"),
        TOTAL("total", "Totaal");

        final String key;
        final String label;

        PeriodChoice(String key, String label)
        {
            this.key = key;
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }
}
