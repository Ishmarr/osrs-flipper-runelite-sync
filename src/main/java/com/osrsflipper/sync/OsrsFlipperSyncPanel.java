package com.osrsflipper.sync;

import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class OsrsFlipperSyncPanel extends PluginPanel
{
    private final JLabel statusValue = new JLabel();

    OsrsFlipperSyncPanel(Runnable pairAction, Runnable syncAction, Runnable webappAction)
    {
        JLabel title = new JLabel("OSRS Flipper Sync");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setBorder(new EmptyBorder(2, 0, 6, 0));
        add(title);

        JLabel introduction = new JLabel(
            "<html>Beheer de veilige apparaatkoppeling en synchroniseer je acht Grand Exchange-slots.</html>");
        introduction.setBorder(new EmptyBorder(0, 0, 8, 0));
        add(introduction);

        JPanel statusPanel = new JPanel(new BorderLayout(0, 5));
        statusPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        statusPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel statusTitle = new JLabel("Verbindingsstatus");
        statusTitle.setFont(statusTitle.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusTitle, BorderLayout.NORTH);

        statusValue.setVerticalAlignment(SwingConstants.TOP);
        statusPanel.add(statusValue, BorderLayout.CENTER);
        add(statusPanel);

        JButton pairButton = new JButton("Apparaat koppelen");
        pairButton.setToolTipText("Open de webapp en voer een tijdelijke koppelcode in.");
        pairButton.addActionListener(event -> pairAction.run());
        add(pairButton);

        JButton syncButton = new JButton("Opnieuw synchroniseren");
        syncButton.setToolTipText("Lees alle acht GE-slots opnieuw en stuur een volledige snapshot.");
        syncButton.addActionListener(event -> syncAction.run());
        add(syncButton);

        JButton webappButton = new JButton("Webapp openen");
        webappButton.setToolTipText("Open de ingestelde OSRS Flip Tracker-webapp.");
        webappButton.addActionListener(event -> webappAction.run());
        add(webappButton);

        JLabel diagnosticHint = new JLabel(
            "<html><small>Bij problemen kun je in de pluginconfig <b>Uitgebreide logging</b> inschakelen.</small></html>");
        diagnosticHint.setBorder(new EmptyBorder(8, 0, 0, 0));
        add(diagnosticHint);

        setConnectionStatus("Nog niet gekoppeld");
    }

    void setConnectionStatus(String status)
    {
        String safeStatus = escapeHtml(status == null ? "Onbekende status" : status);
        SwingUtilities.invokeLater(() -> statusValue.setText("<html>" + safeStatus + "</html>"));
    }

    private static String escapeHtml(String value)
    {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
