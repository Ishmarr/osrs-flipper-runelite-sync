package com.osrsflipper.sync;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "OSRS Flipper Sync",
    description = "Toont live GE-slots, persoonlijke flipkansen en webstatistieken en synchroniseert veilig met de webapp.",
    tags = {"grand-exchange", "ge", "flip", "flipping", "prices", "profit", "sync"},
    enabledByDefault = true
)
public class OsrsFlipperSyncPlugin extends Plugin
{
    private static final Logger LOG = LoggerFactory.getLogger(OsrsFlipperSyncPlugin.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String PLUGIN_VERSION = "5.2.5";
    private static final String USER_AGENT = "OSRS-Flipper-RuneLite-Sync/" + PLUGIN_VERSION;
    private static final String WIKI_USER_AGENT = USER_AGENT +
        " (https://github.com/Ishmarr/osrs-flipper-runelite-sync)";
    private static final String WIKI_LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";

    private static final String PAIR_PATH = "/runelite-api/pair";
    private static final String STATUS_PATH = "/runelite-api/status";
    private static final String HEARTBEAT_PATH = "/runelite-api/heartbeat";
    private static final String SYNC_PATH = "/runelite-api/ge-slots/sync";
    private static final String SNAPSHOT_PATH = "/runelite-api/ge-slots/snapshot";
    private static final String STATE_PATH = "/runelite-api/ge-slots/state";
    private static final String OVERVIEW_PATH = "/runelite-api/overview";
    private static final String CASH_PATH = "/runelite-api/cash";

    private static final String STATE_PREFIX = "slotState_";
    private static final String OUTBOX_PREFIX = "outbox_";
    private static final String SNAPSHOT_SEQUENCE_PREFIX = "snapshotSequence_";
    private static final String PENDING_SNAPSHOT_PREFIX = "pendingSnapshot_";
    private static final String LAST_TRADE_PRICES_PREFIX = "lastTradePrices_";
    private static final int SLOT_COUNT = 8;
    private static final int LOGIN_RECONCILE_TICKS = 8;
    private static final int GE_OPEN_RECONCILE_TICKS = 3;
    private static final int HEARTBEAT_GAME_TICKS = 100;
    private static final int SERVER_STATE_GAME_TICKS = 200;
    private static final int FULL_SNAPSHOT_GAME_TICKS = 500;
    private static final int MARKET_PRICE_GAME_TICKS = 100;
    private static final int OVERVIEW_GAME_TICKS = 500;
    private static final int MAX_OUTBOX_SIZE = 500;
    private static final long MARKET_PRICE_CACHE_SECONDS = 60L;
    private static final long RETRY_BASE_SECONDS = 5L;
    private static final long RETRY_MAX_SECONDS = 300L;
    private static final long NO_ACCOUNT = Long.MIN_VALUE;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private ConfigManager configManager;

    @Inject
    private Gson gson;

    @Inject
    private OsrsFlipperSyncConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    private OsrsFlipperSyncPanel panel;
    private NavigationButton navButton;

    private final Map<Integer, SlotSnapshot> slotSnapshots = new HashMap<>();
    private final Deque<QueuedEvent> outbox = new ArrayDeque<>();
    private final Map<Integer, MarketPriceView> marketPrices = new HashMap<>();
    private final Deque<Integer> marketPriceQueue = new ArrayDeque<>();
    private final Set<Integer> queuedMarketPriceItems = new HashSet<>();
    private final SessionStatsTracker sessionStats = new SessionStatsTracker();
    private final LastTradePriceBook lastTradePrices = new LastTradePriceBook();
    private RuneliteOverviewView overview = RuneliteOverviewView.empty();

    private long activeAccountHash = NO_ACCOUNT;
    private boolean started;
    private boolean requestInFlight;
    private boolean pairingInFlight;
    private boolean statusInFlight;
    private boolean statusCheckPending;
    private boolean heartbeatInFlight;
    private boolean snapshotInFlight;
    private boolean snapshotPending;
    private boolean snapshotDirty;
    private boolean slotStateInFlight;
    private boolean serverStateCheckPending;
    private boolean manualSyncPending;
    private boolean loginReconciliationPending;
    private boolean geOpenReconciliationPending;
    private int loggedInTicks;
    private int geOpenTicks;
    private int heartbeatTicks;
    private int serverStateTicks;
    private int fullSnapshotTicks;
    private int marketPriceTicks;
    private long snapshotSequence;
    private int statusRetryAttempts;
    private long statusNextAttemptAt;
    private int heartbeatRetryAttempts;
    private long heartbeatNextAttemptAt;
    private int serverStateRetryAttempts;
    private long serverStateNextAttemptAt;
    private long workerBackoffUntil;
    private String snapshotReason;
    private PendingSnapshot pendingSnapshot;
    private boolean marketPriceInFlight;
    private boolean overviewInFlight;
    private boolean cashInFlight;
    private boolean overviewRefreshPending;
    private int overviewTicks;
    private int forcedOverviewDelayTicks;
    private int focusedGeItemId;

    @Provides
    OsrsFlipperSyncConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(OsrsFlipperSyncConfig.class);
    }

    @Override
    protected void startUp()
    {
        started = true;
        requestInFlight = false;
        pairingInFlight = false;
        statusInFlight = false;
        statusCheckPending = true;
        heartbeatInFlight = false;
        snapshotInFlight = false;
        cashInFlight = false;
        snapshotPending = false;
        snapshotDirty = false;
        slotStateInFlight = false;
        serverStateCheckPending = true;
        manualSyncPending = false;
        statusRetryAttempts = 0;
        statusNextAttemptAt = 0;
        heartbeatRetryAttempts = 0;
        heartbeatNextAttemptAt = 0;
        serverStateRetryAttempts = 0;
        serverStateNextAttemptAt = 0;
        workerBackoffUntil = 0;
        heartbeatTicks = HEARTBEAT_GAME_TICKS;
        serverStateTicks = SERVER_STATE_GAME_TICKS;
        fullSnapshotTicks = 0;
        marketPriceTicks = MARKET_PRICE_GAME_TICKS;
        marketPriceInFlight = false;
        overviewInFlight = false;
        cashInFlight = false;
        overviewRefreshPending = false;
        overviewTicks = OVERVIEW_GAME_TICKS;
        forcedOverviewDelayTicks = 0;
        focusedGeItemId = 0;
        overview = RuneliteOverviewView.empty();
        snapshotSequence = 0;
        snapshotReason = "startup";
        pendingSnapshot = null;
        marketPrices.clear();
        marketPriceQueue.clear();
        queuedMarketPriceItems.clear();
        sessionStats.reset();
        lastTradePrices.clear();
        loginReconciliationPending = client.getGameState() == GameState.LOGGED_IN;
        geOpenReconciliationPending = false;
        loggedInTicks = 0;
        geOpenTicks = 0;

        createSidePanel();
        switchToCurrentAccount();
        updateInitialConnectionStatus();
        checkDeviceStatus();
        // Een andere pc kan intussen prijstests, cash of deelverkopen hebben opgeslagen.
        // Alleen de eerste accountload omzeilt daarom de vijfminutencache.
        requestOverview(true);
        flushOutboxIfPossible();
        LOG.info("OSRS Flipper Sync {} gestart", PLUGIN_VERSION);
    }

    @Override
    protected void shutDown()
    {
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        if (panel != null)
        {
            panel.dispose();
        }
        panel = null;

        persistCurrentAccount();
        started = false;
        requestInFlight = false;
        pairingInFlight = false;
        statusInFlight = false;
        statusCheckPending = false;
        heartbeatInFlight = false;
        snapshotInFlight = false;
        snapshotPending = false;
        snapshotDirty = false;
        slotStateInFlight = false;
        serverStateCheckPending = false;
        manualSyncPending = false;
        statusRetryAttempts = 0;
        statusNextAttemptAt = 0;
        heartbeatRetryAttempts = 0;
        heartbeatNextAttemptAt = 0;
        serverStateRetryAttempts = 0;
        serverStateNextAttemptAt = 0;
        workerBackoffUntil = 0;
        slotSnapshots.clear();
        outbox.clear();
        marketPrices.clear();
        marketPriceQueue.clear();
        queuedMarketPriceItems.clear();
        marketPriceInFlight = false;
        overviewInFlight = false;
        overviewRefreshPending = false;
        overviewTicks = 0;
        forcedOverviewDelayTicks = 0;
        focusedGeItemId = 0;
        overview = RuneliteOverviewView.empty();
        activeAccountHash = NO_ACCOUNT;
        LOG.info("OSRS Flipper Sync gestopt");
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOGGED_IN)
        {
            switchToCurrentAccount();
            loginReconciliationPending = true;
            loggedInTicks = 0;
            heartbeatTicks = HEARTBEAT_GAME_TICKS;
            serverStateTicks = SERVER_STATE_GAME_TICKS;
            fullSnapshotTicks = 0;
            statusCheckPending = true;
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            checkDeviceStatus();
            flushOutboxIfPossible();
            return;
        }

        if (state == GameState.LOGGING_IN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
        {
            persistCurrentAccount();
            loginReconciliationPending = true;
            loggedInTicks = 0;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        switchToCurrentAccount();
        updateFocusedGeItem();

        if (loginReconciliationPending)
        {
            loggedInTicks++;
            if (loggedInTicks >= LOGIN_RECONCILE_TICKS)
            {
                loginReconciliationPending = false;
                reconcileAllSlots("login");
            }
        }

        if (geOpenReconciliationPending)
        {
            geOpenTicks++;
            if (geOpenTicks >= GE_OPEN_RECONCILE_TICKS)
            {
                geOpenReconciliationPending = false;
                reconcileAllSlots("ge_open");
            }
        }

        checkDeviceStatus();
        if (heartbeatNextAttemptAt > 0)
        {
            sendHeartbeat();
        }

        heartbeatTicks++;
        if (heartbeatTicks >= HEARTBEAT_GAME_TICKS)
        {
            heartbeatTicks = 0;
            sendHeartbeat();
        }

        serverStateTicks++;
        if (serverStateTicks >= SERVER_STATE_GAME_TICKS)
        {
            serverStateTicks = 0;
            serverStateCheckPending = true;
        }

        fullSnapshotTicks++;
        if (fullSnapshotTicks >= FULL_SNAPSHOT_GAME_TICKS)
        {
            fullSnapshotTicks = 0;
            reconcileAllSlots("periodic");
        }

        marketPriceTicks++;
        if (marketPriceTicks >= MARKET_PRICE_GAME_TICKS)
        {
            marketPriceTicks = 0;
            requestMarketPrices(false);
        }

        overviewTicks++;
        if (overviewTicks >= OVERVIEW_GAME_TICKS)
        {
            requestOverview(false);
        }
        if (forcedOverviewDelayTicks > 0 && --forcedOverviewDelayTicks == 0)
        {
            requestOverview(true);
        }

        flushOutboxIfPossible();
        checkServerSlotStateIfPossible();
        flushMarketPriceQueue();
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == InterfaceID.GE_OFFERS)
        {
            geOpenReconciliationPending = true;
            geOpenTicks = 0;
            debug("Grand Exchange geopend; volledige slotsynchronisatie wordt voorbereid");
        }
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        switchToCurrentAccount();
        if (activeAccountHash == NO_ACCOUNT)
        {
            return;
        }

        GrandExchangeOffer offer = event.getOffer();
        if (offer == null)
        {
            return;
        }

        if (loginReconciliationPending && offer.getState() == GrandExchangeOfferState.EMPTY)
        {
            debug("Voorlopige EMPTY-event tijdens login genegeerd voor slot {}", event.getSlot() + 1);
            return;
        }

        SlotSnapshot previous = slotSnapshots.get(event.getSlot() + 1);
        String nextSide = sideFor(offer.getState());
        boolean newOrRepricedOffer = nextSide != null && offer.getItemId() > 0 &&
            (previous == null ||
                previous.itemId != offer.getItemId() ||
                !nextSide.equals(previous.side) ||
                previous.price != offer.getPrice() ||
                previous.totalQuantity != offer.getTotalQuantity());
        processOffer(event.getSlot(), offer, false);
        if (newOrRepricedOffer)
        {
            // Geef de slotsync enkele game ticks om cash en buy limits eerst server-side te verwerken.
            forcedOverviewDelayTicks = 3;
        }
        flushOutboxIfPossible();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!OsrsFlipperSyncConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }

        if ("webappAddress".equals(event.getKey()))
        {
            if (endpoint(STATUS_PATH) == null)
            {
                setConnectionStatus("Ongeldig webapp-adres; gebruik HTTPS");
            }
            else if (hasDeviceToken())
            {
                setConnectionStatus("Webapp-adres gewijzigd; koppeling controleren...");
                statusCheckPending = true;
                checkDeviceStatus();
            }
            else
            {
                setConnectionStatus("Nog niet gekoppeld");
            }
        }
    }

    private void createSidePanel()
    {
        panel = new OsrsFlipperSyncPanel(
            itemManager,
            this::beginInteractivePairing,
            this::requestManualResync,
            this::openWebapp,
            () -> clientThread.invokeLater(() -> requestOverview(true)),
            value -> clientThread.invokeLater(() -> setAccountCash(value)));
        panel.setConnectionStatus(config.connectionStatus());
        refreshSidePanel();

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
        navButton = NavigationButton.builder()
            .tooltip("OSRS Flipper Sync")
            .icon(icon)
            .priority(8)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);
    }

    void beginInteractivePairing()
    {
        HttpUrl settingsUrl = endpoint("/settings");
        if (settingsUrl == null)
        {
            setConnectionStatus("Ongeldig webapp-adres; gebruik HTTPS");
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            LinkBrowser.browse(settingsUrl.toString());
            String message = hasDeviceToken()
                ? "De webapp is geopend. Maak daar een nieuwe tijdelijke code.\n" +
                    "Vul de code hieronder in om de huidige apparaatkoppeling te vervangen."
                : "De webapp is geopend. Maak bij Persoonlijke instellingen een tijdelijke code.\n" +
                    "Vul die code hieronder in.";
            String code = JOptionPane.showInputDialog(
                panel,
                message,
                "Apparaat koppelen",
                JOptionPane.PLAIN_MESSAGE);
            if (code != null)
            {
                clientThread.invokeLater(() -> startPairing(code));
            }
        });
    }

    void requestManualResync()
    {
        clientThread.invokeLater(() ->
        {
            if (!hasDeviceToken())
            {
                setConnectionStatus("Nog niet gekoppeld; koppel eerst dit apparaat");
                return;
            }
            if (client.getGameState() != GameState.LOGGED_IN)
            {
                setConnectionStatus("Log eerst in op RuneScape om te synchroniseren");
                return;
            }

            switchToCurrentAccount();
            manualSyncPending = true;
            statusCheckPending = false;
            statusRetryAttempts = 0;
            statusNextAttemptAt = 0;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            workerBackoffUntil = 0;
            setConnectionStatus("Volledige synchronisatie gestart...");
            reconcileAllSlots("manual");
            serverStateCheckPending = true;
            flushOutboxIfPossible();
        });
    }

    void openWebapp()
    {
        HttpUrl webapp = endpoint("/");
        if (webapp == null)
        {
            setConnectionStatus("Ongeldig webapp-adres; gebruik HTTPS");
            return;
        }
        LinkBrowser.browse(webapp.toString());
    }

    private void updateInitialConnectionStatus()
    {
        if (hasDeviceToken())
        {
            setConnectionStatus("Koppeling controleren...");
        }
        else
        {
            setConnectionStatus("Nog niet gekoppeld");
        }
    }

    private void startPairing(String pairingCode)
    {
        if (!started || pairingInFlight || anyWorkerRequestInFlight())
        {
            if (started && !pairingInFlight)
            {
                setConnectionStatus("Wacht tot de huidige synchronisatie klaar is en probeer opnieuw");
            }
            return;
        }

        String code = normalizePairingCode(pairingCode);
        if (code.length() != 9)
        {
            setConnectionStatus("Vul de volledige code van 8 tekens in");
            return;
        }

        HttpUrl endpoint = endpoint(PAIR_PATH);
        if (endpoint == null)
        {
            setConnectionStatus("Ongeldig webapp-adres");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("device_name", deviceName());
        payload.put("plugin_version", PLUGIN_VERSION);

        Request request = new Request.Builder()
            .url(endpoint)
            .post(RequestBody.create(JSON, gson.toJson(payload)))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("User-Agent", USER_AGENT)
            .header("X-RuneLite-Plugin-Version", PLUGIN_VERSION)
            .header("X-RuneLite-Device-Name", deviceName())
            .build();

        pairingInFlight = true;
        setConnectionStatus("Koppelen...");

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() ->
                {
                    pairingInFlight = false;
                    setConnectionStatus("Koppelen mislukt: geen verbinding");
                    LOG.warn("RuneLite-apparaat koppelen mislukt: {}", exception.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> handlePairResponse(statusCode, body));
            }
        });
    }

    private void handlePairResponse(int statusCode, String body)
    {
        pairingInFlight = false;
        if (statusCode >= 200 && statusCode < 300)
        {
            PairResponse pair;
            try
            {
                pair = gson.fromJson(body, PairResponse.class);
            }
            catch (RuntimeException exception)
            {
                setConnectionStatus("Koppelen mislukt: ongeldig antwoord");
                LOG.error("Koppelantwoord kon niet worden gelezen", exception);
                return;
            }

            if (pair == null || isBlank(pair.device_token) || isBlank(pair.device_id))
            {
                setConnectionStatus("Koppelen mislukt: token ontbreekt");
                return;
            }

            setStoredValue("deviceToken", pair.device_token);
            setStoredValue("deviceId", pair.device_id);
            setStoredValue("ownerEmail", trim(pair.owner_email).toLowerCase());
            setStoredValue("linkedAt", Long.toString(pair.linked_at));
            setConnectionStatus("Gekoppeld met " + displayOwner(pair.owner_email));
            statusCheckPending = false;
            statusRetryAttempts = 0;
            statusNextAttemptAt = 0;

            LOG.info("RuneLite-apparaat gekoppeld aan {}", displayOwner(pair.owner_email));
            heartbeatTicks = HEARTBEAT_GAME_TICKS;
            overviewTicks = OVERVIEW_GAME_TICKS;
            sendHeartbeat();
            requestOverview(true);
            if (client.getGameState() == GameState.LOGGED_IN)
            {
                reconcileAllSlots("paired");
            }
            else
            {
                queueFullSnapshot("paired");
            }
            serverStateCheckPending = true;
            flushOutboxIfPossible();
            return;
        }

        String error = apiError(body, "De koppelcode is ongeldig, gebruikt of vervallen.");
        setConnectionStatus("Koppelen mislukt: " + error);
        LOG.warn("RuneLite-apparaat koppelen kreeg HTTP {}: {}", statusCode, abbreviate(body, 400));
    }

    private void checkDeviceStatus()
    {
        if (!started || !statusCheckPending || statusInFlight || !hasDeviceToken() || anyWorkerRequestInFlight() ||
            !outbox.isEmpty() || snapshotPending || manualSyncPending || serverStateCheckPending)
        {
            return;
        }

        long currentTime = now();
        if (currentTime < statusNextAttemptAt || currentTime < workerBackoffUntil)
        {
            return;
        }

        HttpUrl endpoint = endpoint(STATUS_PATH);
        if (endpoint == null)
        {
            return;
        }

        Request request = authorizedRequest(endpoint)
            .get()
            .build();

        statusInFlight = true;
        statusCheckPending = false;
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() ->
                {
                    statusInFlight = false;
                    statusCheckPending = true;
                    statusNextAttemptAt = scheduleTransientRetry(++statusRetryAttempts);
                    registerWorkerBackoff(statusNextAttemptAt);
                    if (!manualSyncPending)
                    {
                        setConnectionStatus("Gekoppeld, Worker tijdelijk niet bereikbaar");
                    }
                    debug("Apparaatstatus kon niet worden opgehaald; nieuwe poging na {} seconden: {}",
                        Math.max(0, statusNextAttemptAt - now()), exception.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> handleStatusResponse(statusCode, body));
            }
        });
    }

    private void handleStatusResponse(int statusCode, String body)
    {
        statusInFlight = false;
        if (statusCode >= 200 && statusCode < 300)
        {
            markWorkerSuccess();
            statusCheckPending = false;
            statusRetryAttempts = 0;
            statusNextAttemptAt = 0;
            String owner = trim(config.ownerEmail());
            try
            {
                StatusResponse status = gson.fromJson(body, StatusResponse.class);
                if (status != null && status.owner != null && !isBlank(status.owner.email))
                {
                    owner = trim(status.owner.email).toLowerCase();
                    setStoredValue("ownerEmail", owner);
                }
            }
            catch (RuntimeException exception)
            {
                debug("Statusantwoord kon niet volledig worden gelezen: {}", exception.getMessage());
            }
            if (!manualSyncPending)
            {
                setConnectionStatus("Gekoppeld met " + displayOwner(owner));
            }
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            overviewTicks = OVERVIEW_GAME_TICKS;
            requestOverview(false);
            return;
        }

        if (statusCode == 401 || statusCode == 403)
        {
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }

        statusCheckPending = true;
        statusNextAttemptAt = scheduleTransientRetry(++statusRetryAttempts);
        registerWorkerBackoff(statusNextAttemptAt);
        if (!manualSyncPending)
        {
            setConnectionStatus("Gekoppeld, statuscontrole kreeg HTTP " + statusCode);
        }
        debug("Apparaatstatus kreeg HTTP {}; nieuwe poging na {} seconden: {}",
            statusCode, Math.max(0, statusNextAttemptAt - now()), abbreviate(body, 300));
    }

    private void sendHeartbeat()
    {
        if (!started || heartbeatInFlight || !hasDeviceToken() || anyWorkerRequestInFlight() ||
            !outbox.isEmpty() || snapshotPending || manualSyncPending || serverStateCheckPending)
        {
            return;
        }

        long currentTime = now();
        if (currentTime < heartbeatNextAttemptAt || currentTime < workerBackoffUntil)
        {
            return;
        }

        HttpUrl endpoint = endpoint(HEARTBEAT_PATH);
        if (endpoint == null)
        {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plugin_version", PLUGIN_VERSION);
        payload.put("device_name", deviceName());

        Request request = authorizedRequest(endpoint)
            .post(RequestBody.create(JSON, gson.toJson(payload)))
            .header("Content-Type", "application/json; charset=utf-8")
            .build();

        heartbeatInFlight = true;
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() ->
                {
                    heartbeatInFlight = false;
                    heartbeatNextAttemptAt = scheduleTransientRetry(++heartbeatRetryAttempts);
                    registerWorkerBackoff(heartbeatNextAttemptAt);
                    debug("Heartbeat mislukt; nieuwe poging na {} seconden: {}",
                        Math.max(0, heartbeatNextAttemptAt - now()), exception.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() ->
                {
                    heartbeatInFlight = false;
                    if (statusCode == 401 || statusCode == 403)
                    {
                        clearStoredPairing("Koppeling ingetrokken; maak een nieuwe code");
                    }
                    else if (statusCode >= 200 && statusCode < 300)
                    {
                        markWorkerSuccess();
                        heartbeatRetryAttempts = 0;
                        heartbeatNextAttemptAt = 0;
                    }
                    else
                    {
                        heartbeatNextAttemptAt = scheduleTransientRetry(++heartbeatRetryAttempts);
                        registerWorkerBackoff(heartbeatNextAttemptAt);
                        debug("Heartbeat kreeg HTTP {}; nieuwe poging na {} seconden: {}",
                            statusCode, Math.max(0, heartbeatNextAttemptAt - now()), abbreviate(body, 250));
                    }
                });
            }
        });
    }

    private void reconcileAllSlots(String reason)
    {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null)
        {
            if (manualSyncPending || "manual".equals(reason))
            {
                manualSyncPending = false;
                setConnectionStatus("Synchronisatie niet gestart: open eerst de Grand Exchange");
            }
            return;
        }

        int count = Math.min(SLOT_COUNT, offers.length);
        for (int slot = 0; slot < count; slot++)
        {
            GrandExchangeOffer offer = offers[slot];
            if (offer != null)
            {
                processOffer(slot, offer, true);
            }
        }
        refreshSidePanel();
        requestMarketPrices(false);
        queueFullSnapshot(reason);
        persistCurrentAccount();
        flushOutboxIfPossible();
    }

    private void queueFullSnapshot(String reason)
    {
        snapshotPending = true;
        snapshotReason = isBlank(reason) ? "reconcile" : reason;
        if (snapshotInFlight)
        {
            snapshotDirty = true;
        }
        else
        {
            pendingSnapshot = null;
        }
        persistCurrentAccount();
    }

    private void processOffer(int zeroBasedSlot, GrandExchangeOffer offer, boolean reconciliation)
    {
        int slotNumber = zeroBasedSlot + 1;
        if (slotNumber < 1 || slotNumber > SLOT_COUNT)
        {
            LOG.warn("Ongeldig GE-slot ontvangen: {}", slotNumber);
            return;
        }

        GrandExchangeOfferState runeLiteState = offer.getState();
        if (runeLiteState == null)
        {
            return;
        }

        SlotSnapshot previous = slotSnapshots.get(slotNumber);
        if (runeLiteState == GrandExchangeOfferState.EMPTY)
        {
            processEmptySlot(slotNumber, previous, reconciliation);
            return;
        }

        String side = sideFor(runeLiteState);
        String status = statusFor(runeLiteState, offer.getQuantitySold(), offer.getTotalQuantity());
        if (side == null || status == null)
        {
            LOG.warn("Onbekende GE-offerstatus {} in slot {}", runeLiteState, slotNumber);
            return;
        }

        int itemId = offer.getItemId();
        int price = offer.getPrice();
        int totalQuantity = offer.getTotalQuantity();
        int filledQuantity = Math.max(0, Math.min(offer.getQuantitySold(), totalQuantity));
        int spentAmount = Math.max(0, offer.getSpent());
        if (itemId <= 0 || price <= 0 || totalQuantity <= 0)
        {
            LOG.warn("Onvolledige GE-offerdata in slot {}: item={}, prijs={}, totaal={}",
                slotNumber, itemId, price, totalQuantity);
            return;
        }

        String itemName = itemName(itemId);
        boolean sameOffer = isSameOffer(previous, itemId, side, price, totalQuantity);
        if (sameOffer && previous != null && isTerminal(previous.status) && !isTerminal(status))
        {
            sameOffer = false;
        }
        long eventAt = nextLogicalTime(previous == null ? 0 : previous.lastEventAt);
        SlotSnapshot next;
        String eventType;

        if (!sameOffer)
        {
            long previousStart = previous == null ? 0 : previous.startedAt;
            long startedAt = Math.max(eventAt, previousStart + 1);
            next = new SlotSnapshot();
            next.slotNumber = slotNumber;
            next.itemId = itemId;
            next.itemName = itemName;
            next.side = side;
            next.price = price;
            next.totalQuantity = totalQuantity;
            next.filledQuantity = filledQuantity;
            next.spentAmount = spentAmount;
            next.status = status;
            next.offerId = createOfferId(slotNumber, startedAt);
            next.startedAt = startedAt;
            next.endedAt = isTerminal(status) ? eventAt : 0;
            next.eventSequence = 1;
            next.lastEventAt = eventAt;
            captureStartMarketSnapshot(next);
            if ("buy".equals(side))
            {
                next.suggestedSellPrice = suggestedSellPriceFor(itemId);
                next.suggestedSellPricePending = needsFreshSellPriceFor(itemId);
            }
            eventType = eventTypeFor(side, status, true, previous, next);
        }
        else
        {
            next = previous.copy();
            next.itemName = itemName;
            next.filledQuantity = filledQuantity;
            next.spentAmount = spentAmount;
            next.status = status;
            next.eventSequence = Math.max(1, previous.eventSequence + 1);
            next.lastEventAt = eventAt;
            if (isTerminal(status) && next.endedAt <= 0)
            {
                next.endedAt = eventAt;
            }
            eventType = eventTypeFor(side, status, false, previous, next);
        }

        String fingerprint = fingerprint(next);
        if (previous != null && fingerprint.equals(previous.fingerprint))
        {
            return;
        }
        next.fingerprint = fingerprint;

        int previousFilled = sameOffer && previous != null ? previous.filledQuantity : 0;
        int previousSpent = sameOffer && previous != null ? previous.spentAmount : 0;
        String previousStatus = sameOffer && previous != null ? previous.status : null;
        sessionStats.recordTransition(
            next.itemId,
            next.itemName,
            next.side,
            previousFilled,
            previousSpent,
            previousStatus,
            next.filledQuantity,
            next.spentAmount,
            next.status,
            next.price);
        if (!reconciliation)
        {
            lastTradePrices.recordTransition(
                next.itemId,
                next.side,
                previousFilled,
                previousSpent,
                next.filledQuantity,
                next.spentAmount,
                next.totalQuantity,
                next.status,
                next.price,
                next.lastEventAt);
        }

        slotSnapshots.put(slotNumber, next);
        enqueue(next.toSyncEvent(eventType));
        persistCurrentAccount();
        refreshSidePanel();
        queueMarketPrice(next.itemId, next.suggestedSellPricePending);

        debug("GE-slot {}: {} {} {}/{} @ {} ({}){}",
            slotNumber,
            side,
            itemName,
            filledQuantity,
            totalQuantity,
            price,
            status,
            reconciliation ? " [reconciliatie]" : "");
    }

    private void processEmptySlot(int slotNumber, SlotSnapshot previous, boolean reconciliation)
    {
        if (previous == null || "empty".equals(previous.status) || isBlank(previous.offerId))
        {
            return;
        }

        SlotSnapshot next = previous.copy();
        next.status = "empty";
        next.eventSequence = Math.max(1, previous.eventSequence + 1);
        next.lastEventAt = nextLogicalTime(previous.lastEventAt);
        if (next.endedAt <= 0)
        {
            next.endedAt = next.lastEventAt;
        }
        next.fingerprint = fingerprint(next);

        slotSnapshots.put(slotNumber, next);
        enqueue(next.toSyncEvent("slot_emptied"));
        persistCurrentAccount();
        refreshSidePanel();
        debug("GE-slot {} leeggemaakt{}", slotNumber, reconciliation ? " [reconciliatie]" : "");
    }

    private void enqueue(SyncEvent event)
    {
        if (outbox.size() >= MAX_OUTBOX_SIZE)
        {
            LOG.error("Synchronisatiewachtrij is vol; event {} kon niet worden toegevoegd", event.eventId);
            return;
        }

        QueuedEvent queued = new QueuedEvent();
        queued.event = event;
        queued.attempts = 0;
        queued.nextAttemptAt = 0;
        outbox.addLast(queued);
        if (snapshotInFlight)
        {
            snapshotDirty = true;
        }
        else if (pendingSnapshot != null)
        {
            pendingSnapshot = null;
            snapshotPending = true;
            snapshotReason = "slot_change";
        }
        persistCurrentAccount();
    }

    private void flushOutboxIfPossible()
    {
        if (!started || requestInFlight || activeAccountHash == NO_ACCOUNT || !hasDeviceToken() ||
            statusInFlight || heartbeatInFlight || snapshotInFlight || slotStateInFlight || pairingInFlight ||
            now() < workerBackoffUntil)
        {
            return;
        }

        if (outbox.isEmpty())
        {
            sendFullSnapshotIfPossible();
            return;
        }

        HttpUrl endpoint = endpoint(SYNC_PATH);
        if (endpoint == null)
        {
            return;
        }

        QueuedEvent queued = outbox.peekFirst();
        if (queued == null || queued.event == null || queued.nextAttemptAt > now())
        {
            return;
        }

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("source", "automatic");
        wrapper.put("event", queued.event.toApiMap());
        String payload = gson.toJson(wrapper);

        Request request = authorizedRequest(endpoint)
            .post(RequestBody.create(JSON, payload))
            .header("Content-Type", "application/json; charset=utf-8")
            .build();

        requestInFlight = true;
        String eventId = queued.event.eventId;
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> handleNetworkFailure(eventId, exception));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> handleHttpResponse(eventId, statusCode, body));
            }
        });
    }

    private void handleNetworkFailure(String eventId, IOException exception)
    {
        requestInFlight = false;
        QueuedEvent queued = currentQueuedEvent(eventId);
        if (queued != null)
        {
            scheduleRetry(queued);
            persistCurrentAccount();
        }
        if (queued != null)
        {
            registerWorkerBackoff(queued.nextAttemptAt);
        }
        LOG.warn("GE-synchronisatie mislukt; event blijft in de wachtrij: {}", exception.getMessage());
    }

    private void handleHttpResponse(String eventId, int statusCode, String responseText)
    {
        requestInFlight = false;
        QueuedEvent queued = currentQueuedEvent(eventId);
        if (queued == null)
        {
            flushOutboxIfPossible();
            return;
        }

        if (statusCode >= 200 && statusCode < 300)
        {
            markWorkerSuccess();
            outbox.removeFirst();
            SyncResponse syncResponse = parseSyncResponse(responseText);
            applyServerSlotsFromResults(syncResponse == null ? null : syncResponse.results);
            boolean rejected = syncResponse != null && syncResponse.summary != null &&
                syncResponse.summary.rejected > 0;
            if (rejected || responseText.contains("\"outcome\":\"rejected\""))
            {
                serverStateCheckPending = true;
                LOG.warn("Worker heeft GE-event {} inhoudelijk geweigerd; reconciliatie volgt: {}",
                    eventId, abbreviate(responseText, 500));
            }
            else
            {
                debug("GE-event {} door webapp ontvangen", eventId);
            }
            persistCurrentAccount();
            flushOutboxIfPossible();
            checkServerSlotStateIfPossible();
            if (outbox.isEmpty())
            {
                requestOverview(true);
            }
            return;
        }

        if (statusCode == 401 || statusCode == 403)
        {
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }

        if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429)
        {
            outbox.removeFirst();
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            persistCurrentAccount();
            LOG.error("GE-event {} definitief geweigerd met HTTP {}: {}",
                eventId, statusCode, abbreviate(responseText, 500));
            flushOutboxIfPossible();
            return;
        }

        scheduleRetry(queued);
        registerWorkerBackoff(queued.nextAttemptAt);
        persistCurrentAccount();
        LOG.warn("GE-synchronisatie kreeg HTTP {}; nieuwe poging volgt. {}",
            statusCode, abbreviate(responseText, 300));
    }

    private void sendFullSnapshotIfPossible()
    {
        if (!snapshotPending || snapshotInFlight || requestInFlight || !outbox.isEmpty() ||
            statusInFlight || heartbeatInFlight || slotStateInFlight || pairingInFlight ||
            !hasDeviceToken() || client.getGameState() != GameState.LOGGED_IN || now() < workerBackoffUntil)
        {
            return;
        }

        if (pendingSnapshot == null)
        {
            pendingSnapshot = buildPendingSnapshot(snapshotReason);
            persistCurrentAccount();
        }
        if (pendingSnapshot == null || now() < pendingSnapshot.nextAttemptAt)
        {
            return;
        }

        HttpUrl endpoint = endpoint(SNAPSHOT_PATH);
        if (endpoint == null)
        {
            return;
        }

        String snapshotId = pendingSnapshot.snapshotId;
        Request request = authorizedRequest(endpoint)
            .post(RequestBody.create(JSON, gson.toJson(pendingSnapshot.toApiMap())))
            .header("Content-Type", "application/json; charset=utf-8")
            .build();

        snapshotInFlight = true;
        snapshotDirty = false;
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> handleSnapshotFailure(snapshotId, exception));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> handleSnapshotResponse(snapshotId, statusCode, body));
            }
        });
    }

    private PendingSnapshot buildPendingSnapshot(String reason)
    {
        long snapshotAt = now();
        long sequence = nextSnapshotSequence();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int slotNumber = 1; slotNumber <= SLOT_COUNT; slotNumber++)
        {
            SlotSnapshot snapshot = slotSnapshots.get(slotNumber);
            if (snapshot == null || "empty".equals(snapshot.status))
            {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("slot_number", slotNumber);
                empty.put("status", "empty");
                empty.put("event_at", snapshotAt);
                empty.put("event_sequence", snapshot == null ? 0 : Math.max(0, snapshot.eventSequence));
                empty.put("known_server_version", snapshot == null ? 0 : Math.max(0, snapshot.serverVersion));
                slots.add(empty);
            }
            else
            {
                slots.add(snapshot.toSnapshotMap(snapshotAt));
            }
        }

        PendingSnapshot pending = new PendingSnapshot();
        pending.snapshotId = "snapshot-" + sequence + "-" +
            UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        pending.snapshotSequence = sequence;
        pending.snapshotAt = snapshotAt;
        pending.reason = isBlank(reason) ? "reconcile" : reason;
        pending.slots = slots;
        pending.attempts = 0;
        pending.nextAttemptAt = 0;
        return pending;
    }

    private long nextSnapshotSequence()
    {
        snapshotSequence = Math.max(System.currentTimeMillis(), snapshotSequence + 1);
        return snapshotSequence;
    }

    private void handleSnapshotFailure(String snapshotId, IOException exception)
    {
        snapshotInFlight = false;
        if (pendingSnapshot != null && Objects.equals(pendingSnapshot.snapshotId, snapshotId))
        {
            if (snapshotDirty)
            {
                pendingSnapshot = null;
                snapshotPending = true;
                snapshotDirty = false;
                snapshotReason = "changed_during_failed_snapshot";
            }
            else
            {
                scheduleSnapshotRetry(pendingSnapshot);
            }
            persistCurrentAccount();
            registerWorkerBackoff(pendingSnapshot == null ? scheduleTransientRetry(1) : pendingSnapshot.nextAttemptAt);
        }
        if (manualSyncPending)
        {
            setConnectionStatus("Synchronisatie onderbroken; automatische nieuwe poging volgt...");
        }
        LOG.warn("Volledige GE-slotsnapshot mislukt; automatische nieuwe poging volgt: {}",
            exception.getMessage());
        flushOutboxIfPossible();
    }

    private void handleSnapshotResponse(String snapshotId, int statusCode, String body)
    {
        snapshotInFlight = false;
        if (pendingSnapshot == null || !Objects.equals(pendingSnapshot.snapshotId, snapshotId))
        {
            flushOutboxIfPossible();
            return;
        }

        boolean manualSnapshot = manualSyncPending || "manual".equals(pendingSnapshot.reason);
        ServerStateResponse stateResponse = parseServerStateResponse(body);
        if (statusCode >= 200 && statusCode < 300 && stateResponse != null && stateResponse.success)
        {
            markWorkerSuccess();
            applyServerStateRows(stateResponse.data);
            pendingSnapshot = null;
            snapshotPending = snapshotDirty;
            snapshotDirty = false;
            if (snapshotPending)
            {
                snapshotReason = "changed_during_snapshot";
            }
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            persistCurrentAccount();
            if (manualSnapshot)
            {
                if (!snapshotPending && outbox.isEmpty())
                {
                    finishManualSync();
                }
                else
                {
                    setConnectionStatus("Volledige synchronisatie afronden...");
                }
            }
            debug("Volledige GE-slotsnapshot door webapp ontvangen");
            flushOutboxIfPossible();
            checkServerSlotStateIfPossible();
            requestOverview(true);
            return;
        }

        if (statusCode == 401 || statusCode == 403)
        {
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }

        if (statusCode == 409 || (stateResponse != null && stateResponse.reconcile_required))
        {
            applyServerStateRows(stateResponse == null ? null : stateResponse.data);
            pendingSnapshot = null;
            snapshotPending = false;
            snapshotDirty = false;
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            persistCurrentAccount();
            if (manualSyncPending)
            {
                setConnectionStatus("Synchronisatie controleren...");
            }
            LOG.warn("Slotsnapshot conflicteerde met een nieuwere servertoestand; automatische reconciliatie volgt: {}",
                abbreviate(body, 400));
            checkServerSlotStateIfPossible();
            return;
        }

        if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429)
        {
            pendingSnapshot = null;
            snapshotPending = false;
            snapshotDirty = false;
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            persistCurrentAccount();
            if (manualSyncPending)
            {
                manualSyncPending = false;
                setConnectionStatus("Synchronisatie mislukt: Worker gaf HTTP " + statusCode);
            }
            LOG.error("Slotsnapshot definitief geweigerd met HTTP {}: {}", statusCode, abbreviate(body, 500));
            return;
        }

        if (snapshotDirty)
        {
            pendingSnapshot = null;
            snapshotPending = true;
            snapshotDirty = false;
            snapshotReason = "changed_during_failed_snapshot";
        }
        else
        {
            scheduleSnapshotRetry(pendingSnapshot);
        }
        registerWorkerBackoff(pendingSnapshot == null ? scheduleTransientRetry(1) : pendingSnapshot.nextAttemptAt);
        persistCurrentAccount();
        if (manualSyncPending)
        {
            setConnectionStatus("Synchronisatie tijdelijk mislukt; automatische nieuwe poging volgt...");
        }
        LOG.warn("Volledige GE-slotsnapshot kreeg HTTP {}; automatische nieuwe poging volgt. {}",
            statusCode, abbreviate(body, 400));
        flushOutboxIfPossible();
    }

    private void scheduleSnapshotRetry(PendingSnapshot pending)
    {
        pending.attempts = Math.max(0, pending.attempts) + 1;
        pending.nextAttemptAt = scheduleTransientRetry(pending.attempts);
    }

    private void checkServerSlotStateIfPossible()
    {
        if (!serverStateCheckPending || slotStateInFlight || anyWorkerRequestInFlight() ||
            !outbox.isEmpty() || snapshotPending || !hasDeviceToken() ||
            client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        long currentTime = now();
        if (currentTime < serverStateNextAttemptAt || currentTime < workerBackoffUntil)
        {
            return;
        }

        HttpUrl endpoint = endpoint(STATE_PATH);
        if (endpoint == null)
        {
            return;
        }

        slotStateInFlight = true;
        serverStateCheckPending = false;
        Request request = authorizedRequest(endpoint).get().build();
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() ->
                {
                    slotStateInFlight = false;
                    serverStateCheckPending = true;
                    serverStateNextAttemptAt = scheduleTransientRetry(++serverStateRetryAttempts);
                    registerWorkerBackoff(serverStateNextAttemptAt);
                    if (manualSyncPending)
                    {
                        setConnectionStatus("Synchronisatie controleren; Worker tijdelijk niet bereikbaar...");
                    }
                    debug("Server-slotversies konden niet worden opgehaald; nieuwe poging na {} seconden: {}",
                        Math.max(0, serverStateNextAttemptAt - now()), exception.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> handleServerStateResponse(statusCode, body));
            }
        });
    }

    private void handleServerStateResponse(int statusCode, String body)
    {
        slotStateInFlight = false;
        if (statusCode >= 200 && statusCode < 300)
        {
            ServerStateResponse response = parseServerStateResponse(body);
            if (response == null || response.data == null || response.data.size() != SLOT_COUNT)
            {
                serverStateCheckPending = true;
                serverStateNextAttemptAt = scheduleTransientRetry(++serverStateRetryAttempts);
                registerWorkerBackoff(serverStateNextAttemptAt);
                if (manualSyncPending)
                {
                    setConnectionStatus("Synchronisatie controleren; onvolledig serverantwoord...");
                }
                LOG.warn("Server gaf geen volledige acht-slotstoestand terug; nieuwe poging na {} seconden",
                    Math.max(0, serverStateNextAttemptAt - now()));
                return;
            }
            markWorkerSuccess();
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            reconcileWithServerState(response.data);
            return;
        }

        if (statusCode == 401 || statusCode == 403)
        {
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }

        serverStateCheckPending = true;
        serverStateNextAttemptAt = scheduleTransientRetry(++serverStateRetryAttempts);
        registerWorkerBackoff(serverStateNextAttemptAt);
        if (manualSyncPending)
        {
            setConnectionStatus("Synchronisatie controleren; Worker gaf HTTP " + statusCode + "...");
        }
        debug("Server-slotversiecontrole kreeg HTTP {}; nieuwe poging na {} seconden: {}",
            statusCode, Math.max(0, serverStateNextAttemptAt - now()), abbreviate(body, 300));
    }

    private void reconcileWithServerState(List<ServerSlotState> serverRows)
    {
        Map<Integer, ServerSlotState> bySlot = new HashMap<>();
        for (ServerSlotState row : serverRows)
        {
            if (row != null && row.slot_number >= 1 && row.slot_number <= SLOT_COUNT)
            {
                bySlot.put(row.slot_number, row);
            }
        }

        boolean mismatch = bySlot.size() != SLOT_COUNT;
        for (int slotNumber = 1; slotNumber <= SLOT_COUNT; slotNumber++)
        {
            ServerSlotState server = bySlot.get(slotNumber);
            SlotSnapshot local = slotSnapshots.get(slotNumber);
            if (server == null || !slotContentMatches(local, server))
            {
                mismatch = true;
                continue;
            }
            adoptServerMetadata(slotNumber, local, server);
        }

        persistCurrentAccount();
        if (mismatch)
        {
            LOG.warn("Verschil tussen RuneLite en de webapp gevonden; volledige slotsnapshot wordt gestuurd");
            reconcileAllSlots("server_difference");
        }
        else
        {
            if (manualSyncPending)
            {
                finishManualSync();
            }
            debug("Lokale GE-slots en serverversies zijn gelijk");
        }
    }

    private void finishManualSync()
    {
        manualSyncPending = false;
        setConnectionStatus("Synchronisatie voltooid · gekoppeld met " +
            displayOwner(config.ownerEmail()));
    }

    private static boolean slotContentMatches(SlotSnapshot local, ServerSlotState server)
    {
        String localStatus = local == null ? "empty" : String.valueOf(local.status);
        String serverStatus = isBlank(server.status) ? "empty" : server.status;
        if (!Objects.equals(localStatus, serverStatus))
        {
            return false;
        }
        if ("empty".equals(serverStatus))
        {
            return true;
        }
        return local != null &&
            local.itemId == server.item_id &&
            Objects.equals(local.side, server.side) &&
            local.price == server.price &&
            local.totalQuantity == server.total_quantity &&
            local.filledQuantity == server.filled_quantity &&
            local.spentAmount == server.spent_amount;
    }

    private void adoptServerMetadata(int slotNumber, SlotSnapshot local, ServerSlotState server)
    {
        if (local == null)
        {
            local = new SlotSnapshot();
            local.slotNumber = slotNumber;
            local.status = "empty";
            slotSnapshots.put(slotNumber, local);
        }
        local.serverVersion = Math.max(0, server.version);
        if (!"empty".equals(local.status) && !isBlank(server.runelite_offer_id))
        {
            local.offerId = server.runelite_offer_id;
            if (server.started_at > 0)
            {
                local.startedAt = server.started_at;
            }
            if (server.ended_at > 0)
            {
                local.endedAt = server.ended_at;
            }
            if (server.start_instabuy_price > 0)
            {
                local.startInstabuyPrice = server.start_instabuy_price;
            }
            if (server.start_instasell_price > 0)
            {
                local.startInstasellPrice = server.start_instasell_price;
            }
            local.eventSequence = Math.max(local.eventSequence, server.event_sequence);
            local.lastEventAt = Math.max(local.lastEventAt, server.last_event_at);
            local.fingerprint = fingerprint(local);
        }
    }

    private void applyServerSlotsFromResults(List<SyncResult> results)
    {
        if (results == null)
        {
            return;
        }
        for (SyncResult result : results)
        {
            if (result != null && result.slot != null)
            {
                SlotSnapshot local = slotSnapshots.get(result.slot.slot_number);
                if (slotContentMatches(local, result.slot))
                {
                    adoptServerMetadata(result.slot.slot_number, local, result.slot);
                }
                if ("rejected".equals(result.outcome))
                {
                    serverStateCheckPending = true;
                }
            }
        }
    }

    private void applyServerStateRows(List<ServerSlotState> rows)
    {
        if (rows == null)
        {
            return;
        }
        for (ServerSlotState row : rows)
        {
            if (row == null)
            {
                continue;
            }
            SlotSnapshot local = slotSnapshots.get(row.slot_number);
            if (slotContentMatches(local, row))
            {
                adoptServerMetadata(row.slot_number, local, row);
            }
        }
    }

    private SyncResponse parseSyncResponse(String body)
    {
        try
        {
            return gson.fromJson(body, SyncResponse.class);
        }
        catch (RuntimeException exception)
        {
            debug("Synchronisatieantwoord kon niet worden gelezen: {}", exception.getMessage());
            return null;
        }
    }

    private ServerStateResponse parseServerStateResponse(String body)
    {
        try
        {
            return gson.fromJson(body, ServerStateResponse.class);
        }
        catch (RuntimeException exception)
        {
            debug("Server-slotantwoord kon niet worden gelezen: {}", exception.getMessage());
            return null;
        }
    }

    private boolean anyWorkerRequestInFlight()
    {
        return pairingInFlight || statusInFlight || heartbeatInFlight || requestInFlight ||
            snapshotInFlight || slotStateInFlight || cashInFlight;
    }

    private long scheduleTransientRetry(int attempts)
    {
        int exponent = Math.min(Math.max(1, attempts), 6);
        long delaySeconds = Math.min(RETRY_MAX_SECONDS, RETRY_BASE_SECONDS * (1L << exponent));
        return now() + delaySeconds;
    }

    private void registerWorkerBackoff(long retryAt)
    {
        workerBackoffUntil = Math.max(workerBackoffUntil, Math.max(now(), retryAt));
    }

    private void markWorkerSuccess()
    {
        workerBackoffUntil = 0;
    }

    private Request.Builder authorizedRequest(HttpUrl endpoint)
    {
        return new Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer " + trim(config.deviceToken()))
            .header("X-RuneLite-Plugin-Version", PLUGIN_VERSION)
            .header("X-RuneLite-Device-Name", deviceName());
    }

    private QueuedEvent currentQueuedEvent(String eventId)
    {
        QueuedEvent queued = outbox.peekFirst();
        if (queued == null || queued.event == null || !Objects.equals(queued.event.eventId, eventId))
        {
            return null;
        }
        return queued;
    }

    private void scheduleRetry(QueuedEvent queued)
    {
        queued.attempts = Math.max(0, queued.attempts) + 1;
        queued.nextAttemptAt = scheduleTransientRetry(queued.attempts);
    }

    private void switchToCurrentAccount()
    {
        long accountHash = client.getAccountHash();
        if (accountHash == -1L)
        {
            return;
        }
        if (activeAccountHash == accountHash)
        {
            return;
        }

        persistCurrentAccount();
        slotSnapshots.clear();
        outbox.clear();
        pendingSnapshot = null;
        snapshotSequence = 0;
        activeAccountHash = accountHash;
        sessionStats.reset();
        lastTradePrices.clear();
        marketPrices.clear();
        marketPriceQueue.clear();
        queuedMarketPriceItems.clear();
        overview = RuneliteOverviewView.empty();
        overviewInFlight = false;
        overviewTicks = OVERVIEW_GAME_TICKS;
        loadCurrentAccount();
        requestInFlight = false;
        snapshotPending = true;
        snapshotReason = "account_switch";
        serverStateCheckPending = true;
        refreshSidePanel();
        requestMarketPrices(false);
        debug("Accountstatus geladen voor hash {}", accountKey());
    }

    private void loadCurrentAccount()
    {
        if (activeAccountHash == NO_ACCOUNT)
        {
            return;
        }

        try
        {
            String statesJson = configManager.getConfiguration(
                OsrsFlipperSyncConfig.GROUP, STATE_PREFIX + accountKey());
            SlotSnapshot[] states = isBlank(statesJson) ? null : gson.fromJson(statesJson, SlotSnapshot[].class);
            if (states != null)
            {
                for (SlotSnapshot state : states)
                {
                    if (state != null && state.slotNumber >= 1 && state.slotNumber <= SLOT_COUNT)
                    {
                        slotSnapshots.put(state.slotNumber, state);
                    }
                }
            }

            String outboxJson = configManager.getConfiguration(
                OsrsFlipperSyncConfig.GROUP, OUTBOX_PREFIX + accountKey());
            QueuedEvent[] queuedEvents = isBlank(outboxJson) ? null : gson.fromJson(outboxJson, QueuedEvent[].class);
            if (queuedEvents != null)
            {
                for (QueuedEvent queued : queuedEvents)
                {
                    if (queued != null && queued.event != null && outbox.size() < MAX_OUTBOX_SIZE)
                    {
                        outbox.addLast(queued);
                    }
                }
            }

            String sequenceValue = configManager.getConfiguration(
                OsrsFlipperSyncConfig.GROUP, SNAPSHOT_SEQUENCE_PREFIX + accountKey());
            snapshotSequence = isBlank(sequenceValue) ? 0 : Math.max(0, Long.parseLong(sequenceValue));

            String pendingJson = configManager.getConfiguration(
                OsrsFlipperSyncConfig.GROUP, PENDING_SNAPSHOT_PREFIX + accountKey());
            pendingSnapshot = isBlank(pendingJson) ? null : gson.fromJson(pendingJson, PendingSnapshot.class);
            if (pendingSnapshot != null)
            {
                snapshotPending = true;
                snapshotReason = isBlank(pendingSnapshot.reason) ? "retry" : pendingSnapshot.reason;
            }

            String lastTradePricesJson = configManager.getConfiguration(
                OsrsFlipperSyncConfig.GROUP, LAST_TRADE_PRICES_PREFIX + accountKey());
            LastTradePriceBook.Entry[] lastTradeEntries = isBlank(lastTradePricesJson)
                ? null
                : gson.fromJson(lastTradePricesJson, LastTradePriceBook.Entry[].class);
            lastTradePrices.restore(lastTradeEntries);
        }
        catch (RuntimeException exception)
        {
            LOG.error("Lokale GE-synchronisatiestatus kon niet worden gelezen", exception);
            slotSnapshots.clear();
            outbox.clear();
            lastTradePrices.clear();
        }
    }

    private void persistCurrentAccount()
    {
        if (activeAccountHash == NO_ACCOUNT)
        {
            return;
        }

        List<SlotSnapshot> states = new ArrayList<>(slotSnapshots.values());
        states.sort((left, right) -> Integer.compare(left.slotNumber, right.slotNumber));
        configManager.setConfiguration(
            OsrsFlipperSyncConfig.GROUP,
            STATE_PREFIX + accountKey(),
            gson.toJson(states));
        configManager.setConfiguration(
            OsrsFlipperSyncConfig.GROUP,
            OUTBOX_PREFIX + accountKey(),
            gson.toJson(new ArrayList<>(outbox)));
        configManager.setConfiguration(
            OsrsFlipperSyncConfig.GROUP,
            SNAPSHOT_SEQUENCE_PREFIX + accountKey(),
            Long.toString(Math.max(0, snapshotSequence)));
        configManager.setConfiguration(
            OsrsFlipperSyncConfig.GROUP,
            PENDING_SNAPSHOT_PREFIX + accountKey(),
            pendingSnapshot == null ? "" : gson.toJson(pendingSnapshot));
        configManager.setConfiguration(
            OsrsFlipperSyncConfig.GROUP,
            LAST_TRADE_PRICES_PREFIX + accountKey(),
            gson.toJson(lastTradePrices.persistedEntries()));
    }

    private void clearStoredPairing(String status)
    {
        setStoredValue("deviceToken", "");
        setStoredValue("deviceId", "");
        setStoredValue("ownerEmail", "");
        setStoredValue("linkedAt", "");
        requestInFlight = false;
        heartbeatInFlight = false;
        snapshotInFlight = false;
        slotStateInFlight = false;
        cashInFlight = false;
        serverStateCheckPending = false;
        manualSyncPending = false;
        statusInFlight = false;
        statusCheckPending = false;
        statusRetryAttempts = 0;
        statusNextAttemptAt = 0;
        heartbeatRetryAttempts = 0;
        heartbeatNextAttemptAt = 0;
        serverStateRetryAttempts = 0;
        serverStateNextAttemptAt = 0;
        workerBackoffUntil = 0;
        setConnectionStatus(status);
        LOG.warn("RuneLite-apparaatkoppeling is niet langer geldig");
    }

    private void setConnectionStatus(String value)
    {
        String status = isBlank(value) ? "Onbekende status" : value;
        setStoredValue("connectionStatus", status);
        if (panel != null)
        {
            panel.setConnectionStatus(status);
        }
    }

    private void setStoredValue(String key, String value)
    {
        configManager.setConfiguration(OsrsFlipperSyncConfig.GROUP, key, value == null ? "" : value);
    }

    private void refreshSidePanel()
    {
        if (panel == null)
        {
            return;
        }

        List<FlipperOfferView> offers = new ArrayList<>();
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot == null || "empty".equals(snapshot.status) || snapshot.itemId <= 0)
            {
                continue;
            }
            MarketPriceView market = marketPrices.get(snapshot.itemId);
            offers.add(new FlipperOfferView(
                snapshot.slotNumber,
                snapshot.itemId,
                snapshot.itemName,
                snapshot.side,
                snapshot.price,
                snapshot.totalQuantity,
                snapshot.filledQuantity,
                snapshot.status,
                snapshot.startedAt,
                snapshot.endedAt,
                snapshot.suggestedSellPrice,
                snapshot.startInstabuyPrice > 0
                    ? snapshot.startInstabuyPrice
                    : (market == null ? 0 : market.instantBuyPrice),
                snapshot.startInstasellPrice > 0
                    ? snapshot.startInstasellPrice
                    : (market == null ? 0 : market.instantSellPrice)));
        }
        offers.sort((left, right) -> Integer.compare(left.slotNumber, right.slotNumber));
        panel.updateOffers(offers);
        panel.updateOverview(overview);
        panel.updateLastTradePrices(lastTradePrices.snapshot());
    }

    private void resetSessionStats()
    {
        sessionStats.reset();
        refreshSidePanel();
    }

    private void requestOverview(boolean force)
    {
        if (!started || !hasDeviceToken())
        {
            return;
        }
        if (overviewInFlight)
        {
            if (force)
            {
                overviewRefreshPending = true;
            }
            return;
        }

        HttpUrl base = endpoint(OVERVIEW_PATH);
        if (base == null)
        {
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long dayStart = today.atStartOfDay(zone).toEpochSecond();
        long monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toEpochSecond();
        HttpUrl.Builder url = base.newBuilder()
            .addQueryParameter("day_start", Long.toString(dayStart))
            .addQueryParameter("month_start", Long.toString(monthStart));
        if (focusedGeItemId > 0)
        {
            url.addQueryParameter("focus_item_id", Integer.toString(focusedGeItemId));
        }
        if (force)
        {
            url.addQueryParameter("fresh", "1");
        }

        Request request = authorizedRequest(url.build()).get().build();
        overviewInFlight = true;
        overviewTicks = 0;
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() ->
                {
                    debug("RuneLite-kansen konden niet worden opgehaald: {}", exception.getMessage());
                    finishOverviewRequest();
                });
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> handleOverviewResponse(statusCode, body));
            }
        });
    }

    private void handleOverviewResponse(int statusCode, String body)
    {
        if (statusCode == 401 || statusCode == 403)
        {
            overviewInFlight = false;
            overviewRefreshPending = false;
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }
        if (statusCode < 200 || statusCode >= 300)
        {
            debug("RuneLite-kansen kregen HTTP {}: {}", statusCode, abbreviate(body, 300));
            finishOverviewRequest();
            return;
        }

        try
        {
            OverviewResponse response = gson.fromJson(body, OverviewResponse.class);
            if (response == null || !response.success)
            {
                throw new IllegalArgumentException("success ontbreekt");
            }
            overview = response.toView();
            lastTradePrices.mergeAuthoritative(overview.priceTests);
            persistCurrentAccount();
            markWorkerSuccess();
            if (panel != null)
            {
                panel.updateOverview(overview);
                panel.updateLastTradePrices(lastTradePrices.snapshot());
            }
        }
        catch (RuntimeException exception)
        {
            debug("RuneLite-kansen konden niet worden gelezen: {}", exception.getMessage());
        }
        finally
        {
            finishOverviewRequest();
        }
    }

    private void finishOverviewRequest()
    {
        overviewInFlight = false;
        if (overviewRefreshPending)
        {
            overviewRefreshPending = false;
            requestOverview(true);
        }
    }

    private void setAccountCash(long value)
    {
        if (cashInFlight || !hasDeviceToken())
        {
            setConnectionStatus(hasDeviceToken()
                ? "Cashstack wordt al opgeslagen..."
                : "Koppel RuneLite eerst met de webapp");
            return;
        }
        HttpUrl endpoint = endpoint(CASH_PATH);
        if (endpoint == null)
        {
            setConnectionStatus("Ongeldig webapp-adres");
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cash_balance", Math.max(0, value));
        payload.put("request_id", UUID.randomUUID().toString());
        Request request = authorizedRequest(endpoint)
            .put(RequestBody.create(JSON, gson.toJson(payload)))
            .header("Content-Type", "application/json; charset=utf-8")
            .build();
        cashInFlight = true;
        setConnectionStatus("Cashstack opslaan...");
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() ->
                {
                    cashInFlight = false;
                    setConnectionStatus("Cashstack kon niet worden opgeslagen");
                    debug("Cashstack opslaan mislukt: {}", exception.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() ->
                {
                    cashInFlight = false;
                    if (statusCode >= 200 && statusCode < 300)
                    {
                        setConnectionStatus("Cashstack accountbreed opgeslagen");
                        requestOverview(true);
                    }
                    else if (statusCode == 401 || statusCode == 403)
                    {
                        clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
                    }
                    else
                    {
                        setConnectionStatus("Cashstack kreeg HTTP " + statusCode);
                        debug("Cashstackantwoord: {}", abbreviate(body, 300));
                    }
                });
            }
        });
    }

    private void updateFocusedGeItem()
    {
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
        Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS);
        boolean setupVisible = isVisible(setup);
        Widget setupItem = client.getWidget(InterfaceID.GeOffers.SETUP_GRAPHIC4);
        Widget[] detailsWidgets = new Widget[] {
            details,
            client.getWidget(InterfaceID.GeOffers.DETAILS_DESC),
            client.getWidget(InterfaceID.GeOffers.DETAILS_MARKETPRICE),
            client.getWidget(InterfaceID.GeOffers.DETAILS_FEE),
            client.getWidget(InterfaceID.GeOffers.DETAILS_GRAPHIC3),
            client.getWidget(InterfaceID.GeOffers.DETAILS_GRAPHIC4),
            client.getWidget(InterfaceID.GeOffers.DETAILS_GRAPHIC5),
            client.getWidget(InterfaceID.GeOffers.DETAILS_GRAPHIC6),
            client.getWidget(InterfaceID.GeOffers.DETAILS_STATUS),
            client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT),
            client.getWidget(InterfaceID.GeOffers.DETAILS_MODIFY)
        };
        boolean detailsVisible = anyVisible(detailsWidgets);
        int nextItemId = FocusedGeItemResolver.resolve(
            setupVisible,
            setupItem == null ? 0 : setupItem.getItemId(),
            setupVisible ? client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH) : 0,
            detailsVisible,
            firstItemId(detailsWidgets),
            detailsVisible ? selectedGeOfferItemId() : 0);
        if (nextItemId == focusedGeItemId)
        {
            return;
        }
        focusedGeItemId = nextItemId;
        if (panel != null)
        {
            panel.updateFocusedItem(focusedGeItemId);
        }
        if (focusedGeItemId > 0)
        {
            requestOverview(true);
        }
    }

    private static boolean isVisible(Widget widget)
    {
        return widget != null && !widget.isHidden();
    }

    private static boolean anyVisible(Widget[] widgets)
    {
        for (Widget widget : widgets)
        {
            if (isVisible(widget))
            {
                return true;
            }
        }
        return false;
    }

    private static int firstItemId(Widget[] widgets)
    {
        for (Widget widget : widgets)
        {
            if (isVisible(widget) && widget.getItemId() > 0)
            {
                return widget.getItemId();
            }
        }
        return 0;
    }

    private int selectedGeOfferItemId()
    {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        int selectedSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
        int offerIndex = FocusedGeItemResolver.selectedOfferIndex(
            selectedSlot,
            offers == null ? 0 : offers.length);
        if (offerIndex < 0)
        {
            return 0;
        }
        GrandExchangeOffer offer = offers[offerIndex];
        return offer == null || offer.getState() == GrandExchangeOfferState.EMPTY
            ? 0
            : Math.max(0, offer.getItemId());
    }

    private int suggestedSellPriceFor(int itemId)
    {
        return SellTargetPriceResolver.provisional(
            marketPrices.get(itemId),
            overview.opportunityForItem(itemId),
            lastTradePrices.snapshot().get(itemId));
    }

    private void captureStartMarketSnapshot(SlotSnapshot snapshot)
    {
        if (snapshot == null || snapshot.itemId <= 0)
        {
            return;
        }
        MarketPriceView market = marketPrices.get(snapshot.itemId);
        RuneliteOverviewView.Opportunity opportunity = overview.opportunityForItem(snapshot.itemId);
        snapshot.startInstabuyPrice = market != null && market.instantBuyPrice > 0
            ? market.instantBuyPrice
            : (opportunity == null ? 0 : opportunity.instantBuy);
        snapshot.startInstasellPrice = market != null && market.instantSellPrice > 0
            ? market.instantSellPrice
            : (opportunity == null ? 0 : opportunity.instantSell);
    }

    private boolean needsFreshSellPriceFor(int itemId)
    {
        LastTradePriceView priceTest = lastTradePrices.snapshot().get(itemId);
        if (priceTest != null && priceTest.lastBuyPrice > 0)
        {
            return false;
        }
        return SellTargetPriceResolver.needsFreshCapture(overview.opportunityForItem(itemId));
    }

    private void requestMarketPrices(boolean force)
    {
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot != null && !"empty".equals(snapshot.status) && snapshot.itemId > 0)
            {
                queueMarketPrice(snapshot.itemId, force || snapshot.suggestedSellPricePending);
            }
        }
        flushMarketPriceQueue();
    }

    private void queueMarketPrice(int itemId, boolean force)
    {
        if (itemId <= 0)
        {
            return;
        }
        MarketPriceView cached = marketPrices.get(itemId);
        if (!force && cached != null && cached.fetchedAt + MARKET_PRICE_CACHE_SECONDS > now())
        {
            return;
        }
        if (queuedMarketPriceItems.add(itemId))
        {
            marketPriceQueue.addLast(itemId);
        }
    }

    private void flushMarketPriceQueue()
    {
        if (!started || marketPriceInFlight)
        {
            return;
        }
        Integer itemId = marketPriceQueue.pollFirst();
        if (itemId == null)
        {
            return;
        }
        queuedMarketPriceItems.remove(itemId);

        HttpUrl base = HttpUrl.parse(WIKI_LATEST_URL);
        if (base == null)
        {
            return;
        }
        HttpUrl url = base.newBuilder().addQueryParameter("id", Integer.toString(itemId)).build();
        Request request = new Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", WIKI_USER_AGENT)
            .build();

        marketPriceInFlight = true;
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() ->
                {
                    marketPriceInFlight = false;
                    debug("Actuele Wiki-prijs voor item {} kon niet worden opgehaald: {}", itemId, exception.getMessage());
                    flushMarketPriceQueue();
                });
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> handleMarketPriceResponse(itemId, statusCode, body));
            }
        });
    }

    private void handleMarketPriceResponse(int itemId, int statusCode, String body)
    {
        marketPriceInFlight = false;
        if (statusCode >= 200 && statusCode < 300)
        {
            try
            {
                LatestPriceResponse response = gson.fromJson(body, LatestPriceResponse.class);
                LatestPriceData row = response == null || response.data == null
                    ? null
                    : response.data.get(Integer.toString(itemId));
                if (row != null)
                {
                    MarketPriceView market = new MarketPriceView(
                        itemId,
                        row.high,
                        row.low,
                        row.highTime,
                        row.lowTime,
                        now());
                    marketPrices.put(itemId, market);
                    capturePendingSellPrices(itemId, market);
                    refreshSidePanel();
                }
            }
            catch (RuntimeException exception)
            {
                debug("Actuele Wiki-prijs voor item {} kon niet worden gelezen: {}", itemId, exception.getMessage());
            }
        }
        else
        {
            debug("Actuele Wiki-prijs voor item {} kreeg HTTP {}", itemId, statusCode);
        }
        flushMarketPriceQueue();
    }

    private void capturePendingSellPrices(int itemId, MarketPriceView market)
    {
        int capturedPrice = SellTargetPriceResolver.captured(market);
        boolean changed = false;
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot == null || snapshot.itemId != itemId ||
                "empty".equals(snapshot.status))
            {
                continue;
            }
            if (snapshot.startInstabuyPrice <= 0 && market.instantBuyPrice > 0)
            {
                snapshot.startInstabuyPrice = market.instantBuyPrice;
                changed = true;
            }
            if (snapshot.startInstasellPrice <= 0 && market.instantSellPrice > 0)
            {
                snapshot.startInstasellPrice = market.instantSellPrice;
                changed = true;
            }
            if ("buy".equals(snapshot.side) && snapshot.suggestedSellPricePending && capturedPrice > 0)
            {
                snapshot.suggestedSellPrice = capturedPrice;
                snapshot.suggestedSellPricePending = false;
                snapshot.suggestedSellPriceCapturedAt = market.instantBuyAt > 0
                    ? market.instantBuyAt
                    : market.fetchedAt;
                changed = true;
            }
        }

        if (changed)
        {
            persistCurrentAccount();
        }
    }

    private boolean hasDeviceToken()
    {
        return trim(config.deviceToken()).matches("^rlt_[A-Za-z0-9_-]{40,120}$");
    }

    private String deviceName()
    {
        return "RuneLite Plugin Hub";
    }

    private String accountKey()
    {
        return Long.toUnsignedString(activeAccountHash);
    }

    private String createOfferId(int slotNumber, long startedAt)
    {
        return "rl-" + accountKey() + "-" + slotNumber + "-" + startedAt + "-" +
            UUID.randomUUID().toString().replace("-", "");
    }

    private String itemName(int itemId)
    {
        try
        {
            ItemComposition item = itemManager.getItemComposition(itemId);
            String name = item == null ? "" : trim(item.getName());
            return name.isEmpty() ? "Item " + itemId : name;
        }
        catch (RuntimeException exception)
        {
            LOG.debug("Itemnaam kon niet worden opgehaald voor {}", itemId, exception);
            return "Item " + itemId;
        }
    }

    private static boolean isSameOffer(SlotSnapshot previous, int itemId, String side, int price, int totalQuantity)
    {
        return previous != null &&
            !"empty".equals(previous.status) &&
            !isBlank(previous.offerId) &&
            previous.itemId == itemId &&
            Objects.equals(previous.side, side) &&
            previous.price == price &&
            previous.totalQuantity == totalQuantity;
    }

    private static String sideFor(GrandExchangeOfferState state)
    {
        switch (state)
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
                return null;
        }
    }

    static String statusFor(GrandExchangeOfferState state, int filledQuantity, int totalQuantity)
    {
        if (totalQuantity > 0 && filledQuantity >= totalQuantity &&
            (state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING))
        {
            return "completed";
        }
        switch (state)
        {
            case BUYING:
            case SELLING:
                return filledQuantity > 0 ? "partially_filled" : "active";
            case BOUGHT:
            case SOLD:
                return "completed";
            case CANCELLED_BUY:
            case CANCELLED_SELL:
                return "cancelled";
            case EMPTY:
                return "empty";
            default:
                return null;
        }
    }

    private static String eventTypeFor(
        String side,
        String status,
        boolean newOffer,
        SlotSnapshot previous,
        SlotSnapshot next)
    {
        if ("empty".equals(status))
        {
            return "slot_emptied";
        }
        if ("cancelled".equals(status))
        {
            return "cancellation";
        }

        boolean priceChange = newOffer && previous != null &&
            !"empty".equals(previous.status) &&
            previous.itemId == next.itemId &&
            Objects.equals(previous.side, next.side) &&
            previous.price != next.price;
        if (priceChange)
        {
            return "price_change";
        }
        if ("completed".equals(status))
        {
            return "sell".equals(side) ? "sell_completed" : "buy_completed";
        }
        if (newOffer)
        {
            return "sell".equals(side) ? "new_sell_offer" : "new_buy_offer";
        }
        return "sell".equals(side) ? "partial_sell" : "partial_buy";
    }

    private static boolean isTerminal(String status)
    {
        return "completed".equals(status) || "cancelled".equals(status);
    }

    private static String fingerprint(SlotSnapshot state)
    {
        return state.itemId + "|" + state.side + "|" + state.price + "|" +
            state.totalQuantity + "|" + state.filledQuantity + "|" + state.spentAmount + "|" + state.status + "|" +
            state.offerId + "|" + state.startedAt + "|" + state.endedAt;
    }

    private static long nextLogicalTime(long previous)
    {
        return Math.max(now(), previous + 1);
    }

    private static long now()
    {
        return Instant.now().getEpochSecond();
    }

    private HttpUrl endpoint(String path)
    {
        HttpUrl base = HttpUrl.parse(trim(config.webappAddress()));
        if (base == null || !"https".equalsIgnoreCase(base.scheme()) || isBlank(base.host()) ||
            !base.username().isEmpty() || !base.password().isEmpty())
        {
            return null;
        }

        HttpUrl resolved = base.resolve(path);
        if (resolved == null || !"https".equalsIgnoreCase(resolved.scheme()))
        {
            return null;
        }
        return resolved;
    }

    private static String normalizePairingCode(String value)
    {
        String compact = trim(value).toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (compact.isEmpty())
        {
            return "";
        }
        if (compact.length() != 8)
        {
            return compact;
        }
        return compact.substring(0, 4) + "-" + compact.substring(4);
    }

    private String apiError(String body, String fallback)
    {
        try
        {
            ApiError error = gson.fromJson(body, ApiError.class);
            if (error != null && !isBlank(error.error))
            {
                return trim(error.error);
            }
        }
        catch (RuntimeException ignored)
        {
            // Gebruik de vaste gebruikersmelding hieronder.
        }
        return fallback;
    }

    private static String readResponseBody(Response response)
    {
        try
        {
            ResponseBody body = response.body();
            return body == null ? "" : body.string();
        }
        catch (IOException exception)
        {
            return "";
        }
    }

    private static String displayOwner(String value)
    {
        String owner = trim(value);
        return owner.isEmpty() ? "jouw webappaccount" : owner;
    }

    private void debug(String message, Object... arguments)
    {
        if (config.debugLogging())
        {
            LOG.info(message, arguments);
        }
        else
        {
            LOG.debug(message, arguments);
        }
    }

    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value)
    {
        return trim(value).isEmpty();
    }

    private static String abbreviate(String value, int maxLength)
    {
        String text = trim(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private static final class PairResponse
    {
        String device_id;
        String device_token;
        String owner_email;
        long linked_at;
    }

    private static final class StatusResponse
    {
        Owner owner;
    }

    private static final class Owner
    {
        String email;
    }

    private static final class ApiError
    {
        String error;
    }

    private static final class OverviewResponse
    {
        boolean success;
        long generated_at;
        OpportunityLists opportunities;
        OverviewStats stats;
        List<PriceTestData> price_tests;
        CashData cash;

        RuneliteOverviewView toView()
        {
            List<RuneliteOverviewView.Opportunity> expected = opportunityViews(
                opportunities == null ? null : opportunities.expected);
            List<RuneliteOverviewView.Opportunity> hourly = opportunityViews(
                opportunities == null ? null : opportunities.hourly);
            return new RuneliteOverviewView(
                expected,
                hourly,
                opportunities == null || opportunities.focus == null
                    ? null
                    : opportunities.focus.toView(),
                periodView(stats == null ? null : stats.today),
                periodView(stats == null ? null : stats.month),
                periodView(stats == null ? null : stats.total),
                priceTestViews(price_tests),
                cash == null
                    ? RuneliteOverviewView.CashBalance.empty()
                    : cash.toView(),
                generated_at);
        }

        private static List<LastTradePriceView> priceTestViews(List<PriceTestData> rows)
        {
            if (rows == null || rows.isEmpty())
            {
                return Collections.emptyList();
            }
            List<LastTradePriceView> result = new ArrayList<>();
            for (PriceTestData row : rows)
            {
                if (row != null && row.item_id > 0)
                {
                    result.add(row.toView());
                }
            }
            return result;
        }

        private static List<RuneliteOverviewView.Opportunity> opportunityViews(List<OpportunityData> rows)
        {
            if (rows == null || rows.isEmpty())
            {
                return Collections.emptyList();
            }
            List<RuneliteOverviewView.Opportunity> result = new ArrayList<>();
            for (OpportunityData row : rows)
            {
                if (row != null && row.item_id > 0)
                {
                    result.add(row.toView());
                }
            }
            return result;
        }

        private static RuneliteOverviewView.PeriodStats periodView(PeriodStatsData row)
        {
            return row == null
                ? RuneliteOverviewView.PeriodStats.empty()
                : new RuneliteOverviewView.PeriodStats(
                    row.realized_profit,
                    row.roi_percent,
                    row.profit_per_hour,
                    row.ge_tax,
                    row.trading_volume,
                    row.completed_flips,
                    periodItemViews(row.items));
        }

        private static List<RuneliteOverviewView.PeriodItem> periodItemViews(List<PeriodItemData> rows)
        {
            if (rows == null || rows.isEmpty())
            {
                return Collections.emptyList();
            }
            List<RuneliteOverviewView.PeriodItem> result = new ArrayList<>();
            for (PeriodItemData row : rows)
            {
                if (row != null && row.item_id > 0 && row.realized_profit != 0)
                {
                    result.add(new RuneliteOverviewView.PeriodItem(
                        row.item_id,
                        row.item_name,
                        row.realized_profit,
                        row.completed_flips));
                }
            }
            return result;
        }
    }

    private static final class OpportunityLists
    {
        List<OpportunityData> expected;
        List<OpportunityData> hourly;
        OpportunityData focus;
    }

    private static final class OpportunityData
    {
        int item_id;
        String item_name;
        String ranking;
        int buy_price;
        int sell_price;
        int instant_buy;
        int instant_sell;
        int expected_quantity;
        long expected_profit;
        int maximum_quantity;
        long maximum_profit_per_hour;
        long maximum_cycle_profit;
        long price_updated_at;

        RuneliteOverviewView.Opportunity toView()
        {
            return new RuneliteOverviewView.Opportunity(
                item_id,
                item_name,
                ranking,
                buy_price,
                sell_price,
                instant_buy,
                instant_sell,
                expected_quantity,
                expected_profit,
                maximum_quantity,
                maximum_profit_per_hour,
                maximum_cycle_profit,
                price_updated_at);
        }
    }

    private static final class OverviewStats
    {
        PeriodStatsData today;
        PeriodStatsData month;
        PeriodStatsData total;
    }

    private static final class PeriodStatsData
    {
        long realized_profit;
        double roi_percent;
        long profit_per_hour;
        long ge_tax;
        long trading_volume;
        int completed_flips;
        List<PeriodItemData> items;
    }

    private static final class PeriodItemData
    {
        int item_id;
        String item_name;
        long realized_profit;
        int completed_flips;
    }

    private static final class PriceTestData
    {
        int item_id;
        int last_buy_price;
        int last_sell_price;
        long last_buy_at;
        long last_sell_at;

        LastTradePriceView toView()
        {
            return new LastTradePriceView(
                item_id,
                last_buy_price,
                last_sell_price,
                last_buy_at,
                last_sell_at);
        }
    }

    private static final class CashData
    {
        long available;
        long reserved;
        long available_plus_reserved;
        long updated_at;

        RuneliteOverviewView.CashBalance toView()
        {
            return new RuneliteOverviewView.CashBalance(
                available,
                reserved,
                available_plus_reserved,
                updated_at);
        }
    }

    private static final class LatestPriceResponse
    {
        Map<String, LatestPriceData> data;
    }

    private static final class LatestPriceData
    {
        int high;
        int low;
        long highTime;
        long lowTime;
    }

    private static final class SlotSnapshot
    {
        int slotNumber;
        int itemId;
        String itemName;
        String side;
        int price;
        int totalQuantity;
        int filledQuantity;
        int spentAmount;
        String status;
        String offerId;
        long startedAt;
        long endedAt;
        long eventSequence;
        long lastEventAt;
        long serverVersion;
        String fingerprint;
        int suggestedSellPrice;
        boolean suggestedSellPricePending;
        long suggestedSellPriceCapturedAt;
        int startInstabuyPrice;
        int startInstasellPrice;

        SlotSnapshot copy()
        {
            SlotSnapshot copy = new SlotSnapshot();
            copy.slotNumber = slotNumber;
            copy.itemId = itemId;
            copy.itemName = itemName;
            copy.side = side;
            copy.price = price;
            copy.totalQuantity = totalQuantity;
            copy.filledQuantity = filledQuantity;
            copy.spentAmount = spentAmount;
            copy.status = status;
            copy.offerId = offerId;
            copy.startedAt = startedAt;
            copy.endedAt = endedAt;
            copy.eventSequence = eventSequence;
            copy.lastEventAt = lastEventAt;
            copy.serverVersion = serverVersion;
            copy.fingerprint = fingerprint;
            copy.suggestedSellPrice = suggestedSellPrice;
            copy.suggestedSellPricePending = suggestedSellPricePending;
            copy.suggestedSellPriceCapturedAt = suggestedSellPriceCapturedAt;
            copy.startInstabuyPrice = startInstabuyPrice;
            copy.startInstasellPrice = startInstasellPrice;
            return copy;
        }

        SyncEvent toSyncEvent(String eventType)
        {
            SyncEvent event = new SyncEvent();
            event.eventId = UUID.randomUUID().toString();
            event.eventType = eventType;
            event.slotNumber = slotNumber;
            event.itemId = itemId;
            event.itemName = itemName;
            event.side = side;
            event.price = price;
            event.totalQuantity = totalQuantity;
            event.filledQuantity = filledQuantity;
            event.spentAmount = spentAmount;
            event.status = status;
            event.startedAt = startedAt;
            event.endedAt = endedAt;
            event.offerId = offerId;
            event.eventSequence = eventSequence;
            event.eventAt = lastEventAt;
            event.knownServerVersion = Math.max(0, serverVersion);
            event.startInstabuyPrice = startInstabuyPrice;
            event.startInstasellPrice = startInstasellPrice;
            return event;
        }

        Map<String, Object> toSnapshotMap(long fallbackEventAt)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("slot_number", slotNumber);
            result.put("item_id", itemId);
            result.put("item_name", itemName);
            result.put("side", side);
            result.put("price", price);
            result.put("total_quantity", totalQuantity);
            result.put("filled_quantity", filledQuantity);
            result.put("remaining_quantity", Math.max(0, totalQuantity - filledQuantity));
            result.put("spent_amount", spentAmount);
            result.put("status", status);
            result.put("started_at", startedAt);
            if (endedAt > 0)
            {
                result.put("ended_at", endedAt);
            }
            result.put("runelite_offer_id", offerId);
            result.put("event_sequence", Math.max(1, eventSequence));
            result.put("event_at", lastEventAt > 0 ? lastEventAt : fallbackEventAt);
            result.put("known_server_version", Math.max(0, serverVersion));
            result.put("start_instabuy_price", Math.max(0, startInstabuyPrice));
            result.put("start_instasell_price", Math.max(0, startInstasellPrice));
            return result;
        }
    }

    private static final class SyncEvent
    {
        String eventId;
        String eventType;
        int slotNumber;
        int itemId;
        String itemName;
        String side;
        int price;
        int totalQuantity;
        int filledQuantity;
        int spentAmount;
        String status;
        long startedAt;
        long endedAt;
        String offerId;
        long eventSequence;
        long eventAt;
        long knownServerVersion;
        int startInstabuyPrice;
        int startInstasellPrice;

        Map<String, Object> toApiMap()
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("event_id", eventId);
            result.put("event_type", eventType);
            result.put("slot_number", slotNumber);
            result.put("item_id", itemId);
            result.put("item_name", itemName);
            result.put("side", side);
            result.put("price", price);
            result.put("total_quantity", totalQuantity);
            result.put("filled_quantity", filledQuantity);
            result.put("remaining_quantity", Math.max(0, totalQuantity - filledQuantity));
            result.put("spent_amount", spentAmount);
            result.put("status", status);
            result.put("started_at", startedAt);
            if (endedAt > 0)
            {
                result.put("ended_at", endedAt);
            }
            result.put("runelite_offer_id", offerId);
            result.put("event_sequence", eventSequence);
            result.put("event_at", eventAt);
            result.put("known_server_version", Math.max(0, knownServerVersion));
            result.put("start_instabuy_price", Math.max(0, startInstabuyPrice));
            result.put("start_instasell_price", Math.max(0, startInstasellPrice));
            result.put("source", "automatic");
            return Collections.unmodifiableMap(result);
        }
    }

    private static final class SyncResponse
    {
        boolean success;
        SyncSummary summary;
        List<SyncResult> results;
    }

    private static final class SyncSummary
    {
        int received;
        int applied;
        int duplicates;
        int rejected;
    }

    private static final class SyncResult
    {
        String outcome;
        String classification;
        ServerSlotState slot;
    }

    private static final class ServerStateResponse
    {
        boolean success;
        boolean reconcile_required;
        List<ServerSlotState> data;
        String state_digest;
        long aggregate_version;
    }

    private static final class ServerSlotState
    {
        int slot_number;
        int item_id;
        String item_name;
        String side;
        int price;
        int total_quantity;
        int filled_quantity;
        int remaining_quantity;
        int spent_amount;
        String status;
        long started_at;
        long ended_at;
        String runelite_offer_id;
        long event_sequence;
        long last_event_at;
        long version;
        int start_instabuy_price;
        int start_instasell_price;
    }

    private static final class PendingSnapshot
    {
        String snapshotId;
        long snapshotSequence;
        long snapshotAt;
        String reason;
        List<Map<String, Object>> slots;
        int attempts;
        long nextAttemptAt;

        Map<String, Object> toApiMap()
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snapshot_id", snapshotId);
            result.put("snapshot_sequence", snapshotSequence);
            result.put("snapshot_at", snapshotAt);
            result.put("reason", reason);
            result.put("plugin_version", PLUGIN_VERSION);
            result.put("slots", slots == null ? Collections.emptyList() : slots);
            return result;
        }
    }

    private static final class QueuedEvent
    {
        SyncEvent event;
        int attempts;
        long nextAttemptAt;
    }
}
