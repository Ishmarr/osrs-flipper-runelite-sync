package com.osrsflipper.sync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
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

    private static final String PLUGIN_VERSION = "5.2.32";
    private static final String PRICE_EDITOR_PREFIX = "OSRS Flip Tracker - ";
    private static final String QUANTITY_EDITOR_PREFIX = "OSRS Flip Tracker - Aanbevolen aantal: ";
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
    private static final String GE_ITEM_PRESENCE_PREFIX = "geItemPresence_";
    private static final String FLIP_CYCLES_PREFIX = "flipCycles_";
    private static final int SLOT_COUNT = 8;
    private static final int MAX_TRACKED_OVERVIEW_ITEMS = 640;
    private static final int LOGIN_RECONCILE_TICKS = 8;
    private static final int GE_OPEN_RECONCILE_TICKS = 3;
    private static final int HEARTBEAT_GAME_TICKS = 100;
    private static final int SERVER_STATE_GAME_TICKS = 200;
    private static final int MARKET_PRICE_GAME_TICKS = 100;
    // Een game tick duurt ongeveer 600 ms. De kanslijst wordt dus ongeveer
    // iedere minuut opnieuw opgehaald, terwijl de Worker zijn lichte cache kan
    // blijven gebruiken om onnodige D1-reads te vermijden.
    static final int OVERVIEW_GAME_TICKS = 100;
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

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GeSlotTimerOverlay geSlotTimerOverlay;

    private volatile OsrsFlipperSyncPanel panel;
    private NavigationButton navButton;

    private final Map<Integer, SlotSnapshot> slotSnapshots = new HashMap<>();
    private final Deque<QueuedEvent> outbox = new ArrayDeque<>();
    // The deque is only the next delivery window. The journal owns all events.
    private final Deque<SyncEvent> unjournaledEvents = new ArrayDeque<>();
    private final Map<String, AccountState> unsavedAccounts = new HashMap<>();
    private Path eventJournalRoot;
    private EventJournal eventJournal;
    private SyncStorageContext activeStorageContext;
    private String activeConfigProfileKey;
    private volatile PairingCredentials pairingCredentials;
    private String credentialProfileKey;
    private boolean legacyPairingRemoved;
    private String legacyConfigProfileKey;
    private String legacyConnectionKey;
    private String proposedLegacyConnectionKey;
    private long journalSize;
    private long storageRetryAt;
    private boolean storageBlocked;
    private boolean storageInitialized;
    private boolean updatingPairing;
    private String lastPersistedStateJson;
    private final Map<Integer, MarketPriceView> marketPrices = new HashMap<>();
    private final Deque<Integer> marketPriceQueue = new ArrayDeque<>();
    private final Set<Integer> queuedMarketPriceItems = new HashSet<>();
    private final SessionStatsTracker sessionStats = new SessionStatsTracker();
    private final LastTradePriceBook lastTradePrices = new LastTradePriceBook();
    private final GeItemPresenceBook geItemPresence = new GeItemPresenceBook();
    private final FlipCyclePlanBook flipCycles = new FlipCyclePlanBook();
    private RuneliteOverviewView overview = RuneliteOverviewView.empty();
    private final SyncHealthTracker syncHealth = new SyncHealthTracker();
    private final WorkerRequestCoordinator workerRequests = new WorkerRequestCoordinator();

    private long activeAccountHash = NO_ACCOUNT;
    private final Object lifecycleLock = new Object();
    private volatile long lifecycleGeneration;
    private volatile boolean started;
    private boolean requestInFlight;
    private boolean outboxBatchBuyLimitDirty;
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
    private int localReconcileTicks;
    private int fullSnapshotTicks;
    private int marketPriceTicks;
    private long snapshotSequence;
    private int statusRetryAttempts;
    private long statusNextAttemptAt;
    private int heartbeatRetryAttempts;
    private long heartbeatNextAttemptAt;
    private int serverStateRetryAttempts;
    private long serverStateNextAttemptAt;
    private int cashRetryAttempts;
    private long workerBackoffUntil;
    private String snapshotReason;
    private PendingSnapshot pendingSnapshot;
    private boolean marketPriceInFlight;
    private volatile Call marketPriceCall;
    private long marketPriceGeneration;
    private boolean overviewInFlight;
    private int overviewInFlightFocusItemId;
    private int pendingFocusedOverviewItemId;
    private long overviewRequestGeneration;
    private long overviewContextGeneration;
    private boolean cashInFlight;
    private boolean overviewRefreshPending;
    private boolean overviewFreshMarketPending;
    private boolean overviewFreshBuyLimitsPending;
    private boolean overviewInFlightFreshMarket;
    private boolean overviewInFlightFreshBuyLimits;
    private PendingCashUpdate pendingCashUpdate;
    private PendingCashUpdate cashInFlightUpdate;
    private boolean workerPumpActive;
    private int overviewTicks;
    private int forcedOverviewDelayTicks;
    private int focusedGeItemId;
    private String focusedGeItemName = "";
    private String focusedGeSide;
    private FocusedGeItemResolver.EditorContext focusedGeContext =
        FocusedGeItemResolver.EditorContext.NONE;
    private int focusedExistingSlot;

    @Provides
    OsrsFlipperSyncConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(OsrsFlipperSyncConfig.class);
    }

    @Override
    protected void startUp()
    {
        TokenLogFilter.install();
        final long generation;
        synchronized (lifecycleLock)
        {
            started = false;
            generation = ++lifecycleGeneration;
        }
        // RuneLite invokes lifecycle hooks on the EDT. Only Swing work belongs
        // here; account books, the journal and callbacks share the client thread.
        createUi();
        clientThread.invokeLater(() -> startOnClientThread(generation));
    }

    void createUi()
    {
        createSidePanel();
        overlayManager.add(geSlotTimerOverlay);
    }

    private void startOnClientThread(long generation)
    {
        if (generation != lifecycleGeneration)
        {
            return;
        }
        requestInFlight = false;
        outboxBatchBuyLimitDirty = false;
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
        cashRetryAttempts = 0;
        workerBackoffUntil = 0;
        heartbeatTicks = HEARTBEAT_GAME_TICKS;
        serverStateTicks = SERVER_STATE_GAME_TICKS;
        localReconcileTicks = 0;
        fullSnapshotTicks = 0;
        marketPriceTicks = MARKET_PRICE_GAME_TICKS;
        marketPriceInFlight = false;
        invalidateOverviewContext();
        cashInFlight = false;
        overviewRefreshPending = false;
        overviewFreshMarketPending = false;
        overviewFreshBuyLimitsPending = false;
        overviewInFlightFreshMarket = false;
        overviewInFlightFreshBuyLimits = false;
        pendingCashUpdate = null;
        cashInFlightUpdate = null;
        workerPumpActive = false;
        overviewTicks = OVERVIEW_GAME_TICKS;
        forcedOverviewDelayTicks = 0;
        focusedGeItemId = 0;
        focusedGeItemName = "";
        focusedGeSide = "";
        focusedGeContext = FocusedGeItemResolver.EditorContext.NONE;
        focusedExistingSlot = 0;
        overview = RuneliteOverviewView.empty();
        snapshotSequence = 0;
        snapshotReason = "startup";
        pendingSnapshot = null;
        marketPrices.clear();
        marketPriceQueue.clear();
        queuedMarketPriceItems.clear();
        sessionStats.reset();
        lastTradePrices.clear();
        geItemPresence.clear();
        loginReconciliationPending = client.getGameState() == GameState.LOGGED_IN;
        geOpenReconciliationPending = false;
        loggedInTicks = 0;
        geOpenTicks = 0;

        synchronized (lifecycleLock)
        {
            if (generation != lifecycleGeneration)
            {
                return;
            }
            started = true;
        }
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
        // Close the gate before cancellation can enqueue any completion. Cleanup
        // remains FIFO with startup and network callbacks on the client thread.
        synchronized (lifecycleLock)
        {
            started = false;
            ++lifecycleGeneration;
        }
        workerRequests.cancelActive(WorkerRequestCoordinator.Cancellation.SHUTDOWN);
        Call wikiCall = marketPriceCall;
        if (wikiCall != null)
        {
            wikiCall.cancel();
        }
        disposeUi();
        clientThread.invokeLater(this::stopOnClientThread);
    }

    void disposeUi()
    {
        overlayManager.remove(geSlotTimerOverlay);
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

    }

    private void stopOnClientThread()
    {
        pairingCredentials = null;
        credentialProfileKey = null;
        persistCurrentAccount();
        if (activeStorageContext != null && storageBlocked)
        {
            unsavedAccounts.put(activeStorageContext.accountKey,
                gson.fromJson(gson.toJson(captureAccountState()), AccountState.class));
        }
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
        localReconcileTicks = 0;
        fullSnapshotTicks = 0;
        slotSnapshots.clear();
        flipCycles.clear();
        geItemPresence.clear();
        outbox.clear();
        outboxBatchBuyLimitDirty = false;
        marketPrices.clear();
        marketPriceQueue.clear();
        queuedMarketPriceItems.clear();
        invalidateMarketPriceContext();
        invalidateOverviewContext();
        overviewRefreshPending = false;
        overviewFreshMarketPending = false;
        overviewFreshBuyLimitsPending = false;
        overviewInFlightFreshMarket = false;
        overviewInFlightFreshBuyLimits = false;
        pendingCashUpdate = null;
        cashInFlightUpdate = null;
        workerPumpActive = false;
        overviewTicks = 0;
        forcedOverviewDelayTicks = 0;
        focusedGeItemId = 0;
        focusedGeItemName = "";
        focusedGeSide = "";
        focusedGeContext = FocusedGeItemResolver.EditorContext.NONE;
        focusedExistingSlot = 0;
        overview = RuneliteOverviewView.empty();
        activeAccountHash = NO_ACCOUNT;
        activeStorageContext = null;
        eventJournal = null;
        storageInitialized = false;
        unjournaledEvents.clear();
        LOG.info("OSRS Flipper Sync gestopt");
    }

    private boolean isCurrentLifecycle(long generation)
    {
        return started && lifecycleGeneration == generation;
    }

    void dispatchToClientThread(Runnable action)
    {
        long generation = lifecycleGeneration;
        if (!started)
        {
            return;
        }
        clientThread.invokeLater(() ->
        {
            if (isCurrentLifecycle(generation))
            {
                action.run();
            }
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (!started)
        {
            return;
        }
        GameState state = event.getGameState();
        if (state == GameState.LOGGED_IN)
        {
            switchToCurrentAccount();
            loginReconciliationPending = true;
            loggedInTicks = 0;
            heartbeatTicks = HEARTBEAT_GAME_TICKS;
            serverStateTicks = SERVER_STATE_GAME_TICKS;
            localReconcileTicks = 0;
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
        if (!started || client.getGameState() != GameState.LOGGED_IN)
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
                if (reconcileAllSlots(
                    "login",
                    SnapshotSyncPolicy.ReconcileMode.ALWAYS))
                {
                    loginReconciliationPending = false;
                    loggedInTicks = 0;
                    flushOutboxIfPossible();
                    checkServerSlotStateIfPossible();
                }
            }
        }

        if (geOpenReconciliationPending)
        {
            geOpenTicks++;
            if (geOpenTicks >= GE_OPEN_RECONCILE_TICKS)
            {
                geOpenReconciliationPending = false;
                reconcileAllSlots(
                    "ge_open",
                    SnapshotSyncPolicy.ReconcileMode.WHEN_CHANGED);
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

        localReconcileTicks++;
        fullSnapshotTicks++;
        SnapshotSyncPolicy.TickAction snapshotTickAction = SnapshotSyncPolicy.tickAction(
            localReconcileTicks,
            fullSnapshotTicks);
        if (snapshotTickAction == SnapshotSyncPolicy.TickAction.HOURLY_SNAPSHOT)
        {
            if (reconcileAllSlots(
                "periodic_hourly",
                SnapshotSyncPolicy.ReconcileMode.ALWAYS))
            {
                localReconcileTicks = 0;
            }
        }
        else if (snapshotTickAction == SnapshotSyncPolicy.TickAction.LOCAL_RECONCILE)
        {
            if (reconcileAllSlots(
                "periodic_reconcile",
                SnapshotSyncPolicy.ReconcileMode.NEVER))
            {
                localReconcileTicks = 0;
            }
        }

        marketPriceTicks++;
        if (marketPriceTicks >= MARKET_PRICE_GAME_TICKS)
        {
            marketPriceTicks = 0;
            requestMarketPrices(false);
        }

        overviewTicks++;
        if (overviewTicks >= OVERVIEW_GAME_TICKS || overviewRefreshPending ||
            (syncHealth.failed(SyncHealthTracker.Channel.OVERVIEW) &&
                now() >= syncHealth.retryAt(SyncHealthTracker.Channel.OVERVIEW)))
        {
            requestOverview(false);
        }
        if (forcedOverviewDelayTicks > 0 && --forcedOverviewDelayTicks == 0)
        {
            requestOverview(true);
        }

        observePriceTestItemPresence();

        flushOutboxIfPossible();
        checkServerSlotStateIfPossible();
        pumpWorkerRequests();
        flushMarketPriceQueue();
        updateHealthPanel();
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (started && event.getGroupId() == InterfaceID.GE_OFFERS)
        {
            geOpenReconciliationPending = true;
            geOpenTicks = 0;
            debug("Grand Exchange geopend; volledige slotsynchronisatie wordt voorbereid");
        }
    }

    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged event)
    {
        if (event.getIndex() != VarClientInt.INPUT_TYPE ||
            client.getVarcIntValue(VarClientInt.INPUT_TYPE) != 7)
        {
            return;
        }
        dispatchToClientThread(() ->
        {
            showGePriceEditorSuggestion();
            showGeQuantityEditorSuggestion();
        });
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
    {
        if (!started)
        {
            return;
        }
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

        if (!started || updatingPairing)
        {
            return;
        }
        if ("webappAddress".equals(event.getKey()) || "ownerEmail".equals(event.getKey()) ||
            "deviceId".equals(event.getKey()) || "deviceToken".equals(event.getKey()))
        {
            String key = event.getKey();
            dispatchToClientThread(() ->
            {
                if ("deviceToken".equals(key))
                {
                    // Imported/old configuration must never reactivate an unbound token.
                    credentialProfileKey = null;
                }
                switchToCurrentAccount();
                if ("webappAddress".equals(key))
                {
                    invalidateOverviewContext();
                    if (endpoint(STATUS_PATH) == null)
                        setConnectionStatus("Ongeldig webapp-adres; gebruik HTTPS");
                    else if (hasDeviceToken())
                    {
                        setConnectionStatus("Webapp-adres gewijzigd; koppeling controleren...");
                        statusCheckPending = true;
                        checkDeviceStatus();
                    }
                    else setConnectionStatus("Koppel opnieuw voor dit webapp-adres");
                }
            });
        }
    }

    @Subscribe
    public void onProfileChanged(net.runelite.client.events.ProfileChanged event)
    {
        dispatchToClientThread(() -> { switchToCurrentAccount(); updateInitialConnectionStatus(); });
    }

    private void createSidePanel()
    {
        final long panelGeneration = lifecycleGeneration;
        panel = new OsrsFlipperSyncPanel(
            itemManager,
            panelAction(panelGeneration, this::beginInteractivePairing),
            panelAction(panelGeneration, this::requestManualResync),
            panelAction(panelGeneration, this::openWebapp),
            panelAction(panelGeneration, () -> dispatchToClientThread(this::requestFreshMarketOverview)),
            value -> panelAction(panelGeneration, () -> dispatchToClientThread(() -> setAccountCash(value))).run());
        panel.setConnectionStatus(config.connectionStatus());
        // startUp() runs on Swing's AWT thread. RuneLite item definitions may only
        // be read on the client thread, so the first full refresh is driven by the
        // client-thread state/overview callbacks below instead of from here.

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
        navButton = NavigationButton.builder()
            .tooltip("OSRS Flipper Sync")
            .icon(icon)
            .priority(8)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);
    }

    private Runnable panelAction(long generation, Runnable action)
    {
        return () ->
        {
            if (isCurrentLifecycle(generation))
            {
                action.run();
            }
        };
    }

    void beginInteractivePairing()
    {
        final long generation = lifecycleGeneration;
        if (!isCurrentLifecycle(generation))
        {
            return;
        }
        HttpUrl settingsUrl = endpoint("/settings");
        if (settingsUrl == null)
        {
            dispatchToClientThread(() -> setConnectionStatus("Ongeldig webapp-adres; gebruik HTTPS"));
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            if (!isCurrentLifecycle(generation))
            {
                return;
            }
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
                dispatchToClientThread(() ->
                {
                    if (isCurrentLifecycle(generation))
                    {
                        startPairing(code);
                    }
                });
            }
        });
    }

    void requestManualResync()
    {
        dispatchToClientThread(() ->
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
            reconcileAllSlots(
                "manual",
                SnapshotSyncPolicy.ReconcileMode.ALWAYS);
            serverStateCheckPending = true;
            flushOutboxIfPossible();
        });
    }

    void openWebapp()
    {
        if (!started)
        {
            return;
        }
        HttpUrl webapp = endpoint("/");
        if (webapp == null)
        {
            dispatchToClientThread(() -> setConnectionStatus("Ongeldig webapp-adres; gebruik HTTPS"));
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
            setConnectionStatus(legacyPairingRemoved
                ? "Koppel eenmaal opnieuw om je token veilig aan dit webapp-adres te binden"
                : "Nog niet gekoppeld voor dit webapp-adres");
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

        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.PAIRING, request);
        if (workerCall == null)
        {
            setConnectionStatus("Wacht tot de huidige synchronisatie klaar is en probeer opnieuw");
            return;
        }
        pairingInFlight = true;
        setConnectionStatus("Koppelen...");

        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.PAIRING,
                    call,
                    () -> {
                    pairingInFlight = false;
                    setConnectionStatus("Koppelen mislukt: geen verbinding");
                    LOG.warn("RuneLite-apparaat koppelen mislukt: {}", exception.getMessage());
                    }));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.PAIRING,
                    call,
                    () -> handlePairResponse(statusCode, body)));
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
                LOG.warn("Koppelantwoord kon niet worden gelezen");
                return;
            }

            if (pair == null || !Boolean.TRUE.equals(pair.success) || isBlank(pair.device_token) ||
                isBlank(pair.device_id) || isBlank(pair.owner_email) || pair.linked_at <= 0)
            {
                setConnectionStatus("Koppelen mislukt: token ontbreekt");
                return;
            }

            PairingCredentials credentials;
            try
            {
                credentials = PairingCredentials.create(configProfileKey(), endpoint(PAIR_PATH),
                    pair.owner_email, pair.device_id, pair.device_token);
            }
            catch (IllegalArgumentException exception)
            {
                setConnectionStatus("Koppeling niet opgeslagen: lokale tokenopslag niet beschikbaar; probeer opnieuw te koppelen");
                LOG.warn("Lokale tokenopslag niet beschikbaar");
                return;
            }
            // Een eventueel nog lopende overview gebruikte de vorige token.
            // Laat die response nooit in de nieuwe koppeling terechtkomen.
            ensureLegacyConnectionBinding();
            if (legacyConnectionKey == null)
            {
                setConnectionStatus("Koppeling niet opgeslagen: herstel eerst de lokale opslag en gebruik daarna een nieuwe code");
                return;
            }
            try { credentialStore().write(credentials); }
            catch (IOException exception)
            {
                setConnectionStatus("Koppeling niet opgeslagen: lokale tokenopslag niet beschikbaar; probeer opnieuw te koppelen");
                LOG.warn("Lokale tokenopslag niet beschikbaar");
                return;
            }
            persistCurrentAccount();
            invalidateOverviewContext();
            updatingPairing = true;
            try
            {
                pairingCredentials = credentials;
                credentialProfileKey = credentials.profile;
                legacyPairingRemoved = false;
                configManager.unsetConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken");
                setStoredValue("deviceId", pair.device_id);
                setStoredValue("ownerEmail", trim(pair.owner_email).toLowerCase(Locale.ROOT));
                setStoredValue("linkedAt", Long.toString(pair.linked_at));
            }
            finally
            {
                updatingPairing = false;
            }
            switchToCurrentAccount();
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
                reconcileAllSlots(
                    "paired",
                    SnapshotSyncPolicy.ReconcileMode.ALWAYS);
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
        LOG.warn("RuneLite-apparaat koppelen kreeg HTTP {}", statusCode);
    }

    private void checkDeviceStatus()
    {
        if (!started || !statusCheckPending || statusInFlight || !hasDeviceToken() || anyWorkerRequestInFlight() ||
            hasQueuedEvents() || snapshotPending || manualSyncPending || serverStateCheckPending)
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

        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.STATUS, request);
        if (workerCall == null)
        {
            return;
        }
        statusInFlight = true;
        statusCheckPending = false;
        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.STATUS,
                    call,
                    () -> {
                    statusInFlight = false;
                    statusCheckPending = true;
                    statusNextAttemptAt = scheduleTransientRetry(++statusRetryAttempts);
                    registerWorkerBackoff(statusNextAttemptAt);
                    if (!manualSyncPending)
                    {
                        setConnectionStatus("Gekoppeld, Worker tijdelijk niet bereikbaar");
                    }
                    healthFailure(SyncHealthTracker.Channel.STATUS, "netwerkfout/time-out");
                    debug("Apparaatstatus kon niet worden opgehaald; nieuwe poging na {} seconden: {}",
                        Math.max(0, statusNextAttemptAt - now()), exception.getMessage());
                    }));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.STATUS,
                    call,
                    () -> handleStatusResponse(statusCode, body)));
            }
        });
    }

    private void handleStatusResponse(int statusCode, String body)
    {
        statusInFlight = false;
        if (statusCode >= 200 && statusCode < 300 &&
            WorkerResponseValidation.status(body, trim(config.deviceId()), trim(config.ownerEmail())))
        {
            markWorkerSuccess();
            healthSuccess(SyncHealthTracker.Channel.STATUS);
            statusCheckPending = false;
            statusRetryAttempts = 0;
            statusNextAttemptAt = 0;
            String owner = trim(config.ownerEmail());
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
        String failure = statusCode >= 200 && statusCode < 300 ? "ongeldig statusantwoord" : "HTTP " + statusCode;
        if (!manualSyncPending)
        {
            setConnectionStatus("Statuscontrole niet bevestigd: " + failure);
        }
        healthFailure(SyncHealthTracker.Channel.STATUS, failure);
        debug("Apparaatstatus kreeg HTTP {}; nieuwe poging na {} seconden",
            statusCode, Math.max(0, statusNextAttemptAt - now()));
    }

    private void sendHeartbeat()
    {
        if (!started || heartbeatInFlight || !hasDeviceToken() || anyWorkerRequestInFlight() ||
            hasQueuedEvents() || snapshotPending || manualSyncPending || serverStateCheckPending)
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

        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.HEARTBEAT, request);
        if (workerCall == null)
        {
            return;
        }
        heartbeatInFlight = true;
        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.HEARTBEAT,
                    call,
                    () -> {
                    heartbeatInFlight = false;
                    heartbeatNextAttemptAt = scheduleTransientRetry(++heartbeatRetryAttempts);
                    registerWorkerBackoff(heartbeatNextAttemptAt);
                    healthFailure(SyncHealthTracker.Channel.HEARTBEAT, "netwerkfout/time-out");
                    debug("Heartbeat mislukt; nieuwe poging na {} seconden: {}",
                        Math.max(0, heartbeatNextAttemptAt - now()), exception.getMessage());
                    }));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.HEARTBEAT,
                    call,
                    () -> {
                    heartbeatInFlight = false;
                    if (statusCode == 401 || statusCode == 403)
                    {
                        clearStoredPairing("Koppeling ingetrokken; maak een nieuwe code");
                    }
                    else if (statusCode >= 200 && statusCode < 300 &&
                        WorkerResponseValidation.heartbeat(body, trim(config.deviceId()), trim(config.ownerEmail())))
                    {
                        markWorkerSuccess();
                        healthSuccess(SyncHealthTracker.Channel.HEARTBEAT);
                        heartbeatRetryAttempts = 0;
                        heartbeatNextAttemptAt = 0;
                    }
                    else
                    {
                        heartbeatNextAttemptAt = scheduleTransientRetry(++heartbeatRetryAttempts);
                        registerWorkerBackoff(heartbeatNextAttemptAt);
                        healthFailure(SyncHealthTracker.Channel.HEARTBEAT,
                            statusCode >= 200 && statusCode < 300 ? "ongeldig heartbeatantwoord" : "HTTP " + statusCode);
                        debug("Heartbeat kreeg HTTP {}; nieuwe poging na {} seconden",
                            statusCode, Math.max(0, heartbeatNextAttemptAt - now()));
                    }
                    }));
            }
        });
    }

    private boolean reconcileAllSlots(
        String reason,
        SnapshotSyncPolicy.ReconcileMode snapshotMode)
    {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (!hasCompleteRuneLiteSlotArray(offers))
        {
            if (manualSyncPending || "manual".equals(reason))
            {
                manualSyncPending = false;
                setConnectionStatus("Synchronisatie niet gestart: open eerst de Grand Exchange");
            }
            return false;
        }

        String contentBefore = localSlotContentDigest();
        for (int slot = 0; slot < SLOT_COUNT; slot++)
        {
            GrandExchangeOffer offer = offers[slot];
            if (offer != null)
            {
                processOffer(slot, offer, true);
            }
        }
        repairUnlinkedSellCycles();
        refreshSidePanel();
        requestMarketPrices(false);
        boolean changed = !Objects.equals(contentBefore, localSlotContentDigest());
        if (SnapshotSyncPolicy.shouldQueueSnapshot(snapshotMode, changed))
        {
            queueFullSnapshot(reason);
        }
        persistCurrentAccount();
        flushOutboxIfPossible();
        return true;
    }

    private static boolean hasCompleteRuneLiteSlotArray(GrandExchangeOffer[] offers)
    {
        if (offers == null || offers.length != SLOT_COUNT)
        {
            return false;
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++)
        {
            if (offers[slot] == null)
            {
                return false;
            }
        }
        return true;
    }

    private String localSlotContentDigest()
    {
        StringBuilder digest = new StringBuilder();
        for (int slotNumber = 1; slotNumber <= SLOT_COUNT; slotNumber++)
        {
            SlotSnapshot snapshot = slotSnapshots.get(slotNumber);
            digest.append(slotNumber).append('=');
            if (snapshot == null || "empty".equals(snapshot.status))
            {
                digest.append("empty");
            }
            else
            {
                digest.append(fingerprint(snapshot));
            }
            digest.append(';');
        }
        return digest.toString();
    }

    private void queueFullSnapshot(String reason)
    {
        fullSnapshotTicks = 0;
        snapshotPending = true;
        snapshotReason = isBlank(reason) ? "reconcile" : reason;
        if (snapshotInFlight || pendingSnapshot != null)
        {
            // Een reeds aangeboden snapshot-ID blijft de durable intent. Een
            // handmatige/periodieke trigger plant alleen een verse opvolger.
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
        boolean continuingReplacement = !sameOffer &&
            (OfferGuidanceResolver.continuesOfferLifecycle(
                sameOfferShape(previous, itemId, side, totalQuantity),
                previous == null ? "" : previous.status) ||
                continuesCancelledReprice(
                    previous,
                    itemId,
                    side,
                    price,
                    totalQuantity));
        long observedAt = now();
        long eventAt = Math.max(observedAt, (previous == null ? 0 : previous.lastEventAt) + 1);
        SlotSnapshot next;
        String eventType;

        if (!sameOffer)
        {
            releaseReplacedCycleState(previous, eventAt);
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
                boolean continuingBuyOffer = continuingReplacement;
                int currentSellCandidate = continuingBuyOffer
                    ? liveWikiSellRaiseCandidateFor(itemId)
                    : initialSuggestedSellPriceFor(itemId);
                OfferGuidanceResolver.Guidance guidance = OfferGuidanceResolver.buy(
                    price,
                    currentSellCandidate,
                    itemId,
                    guidance(previous),
                    continuingBuyOffer);
                next.suggestedBuyPrice = guidance.buyPrice;
                next.suggestedSellPrice = guidance.sellPrice;
                next.sourceBuyOfferId = !isBlank(guidance.sourceBuyOfferId)
                    ? guidance.sourceBuyOfferId
                    : (continuingBuyOffer && previous != null && !isBlank(previous.sourceBuyOfferId)
                        ? previous.sourceBuyOfferId
                        : next.offerId);
                next.lowestSellPrice = guidance.lowestSellPrice;
                next.suggestedSellPricePending = needsFreshSellPriceFor(itemId);
                if (continuingBuyOffer)
                {
                    next.startInstabuyPrice = positiveOrFallback(
                        previous.startInstabuyPrice,
                        next.startInstabuyPrice);
                    next.startInstasellPrice = positiveOrFallback(
                        previous.startInstasellPrice,
                        next.startInstasellPrice);
                    next.suggestedSellPricePending =
                        previous.suggestedSellPricePending || next.suggestedSellPricePending;
                    next.suggestedSellPriceCapturedAt = previous.suggestedSellPriceCapturedAt;
                }
            }
            else
            {
                boolean continuingSellOffer = continuingReplacement;
                if (continuingSellOffer)
                {
                    int currentSellCandidate = liveWikiSellRaiseCandidateFor(itemId);
                    OfferGuidanceResolver.Guidance guidance = OfferGuidanceResolver.reprice(
                        side,
                        price,
                        currentSellCandidate,
                        guidance(previous));
                    next.suggestedBuyPrice = guidance.buyPrice;
                    next.suggestedSellPrice = guidance.sellPrice;
                    next.sourceBuyOfferId = guidance.sourceBuyOfferId;
                    next.lowestSellPrice = guidance.lowestSellPrice;
                    next.startInstabuyPrice = positiveOrFallback(
                        previous.startInstabuyPrice,
                        next.startInstabuyPrice);
                    next.startInstasellPrice = positiveOrFallback(
                        previous.startInstasellPrice,
                        next.startInstasellPrice);
                    next.suggestedSellPriceCapturedAt = previous.suggestedSellPriceCapturedAt;
                }
                else
                {
                    FlipCyclePlanBook.Cycle sourceCycle = flipCycles.selectForSell(
                        itemId,
                        totalQuantity,
                        startedAt);
                    OfferGuidanceResolver.BuyCandidate source = buyCandidate(sourceCycle);
                    if (source == null)
                    {
                        source = OfferGuidanceResolver.selectBuyForSell(
                            slotNumber,
                            itemId,
                            totalQuantity,
                            startedAt,
                            buyGuidanceCandidates(),
                            linkedBuyOfferIds());
                    }
                    int currentSellCandidate = source == null
                        ? initialSuggestedSellPriceFor(itemId)
                        : liveWikiSellRaiseCandidateFor(itemId);
                    OfferGuidanceResolver.Guidance guidance = OfferGuidanceResolver.linkedSell(
                        price,
                        currentSellCandidate,
                        next.startInstasellPrice,
                        source);
                    next.suggestedBuyPrice = guidance.buyPrice;
                    next.suggestedSellPrice = guidance.sellPrice;
                    next.sourceBuyOfferId = guidance.sourceBuyOfferId;
                    next.lowestSellPrice = guidance.lowestSellPrice;
                }
            }
            eventType = eventTypeFor(side, status, true, previous, next);
        }
        else
        {
            next = previous.copy();
            next.itemName = itemName;
            if ("buy".equals(side) && next.suggestedBuyPrice <= 0)
            {
                next.suggestedBuyPrice = price;
            }
            if ("buy".equals(side) && isBlank(next.sourceBuyOfferId))
            {
                next.sourceBuyOfferId = next.offerId;
            }
            if ("sell".equals(side) && isBlank(next.sourceBuyOfferId))
            {
                tryLinkSellToOpenCycle(next);
            }
            boolean mayHaveFrozenFloor = "buy".equals(side) ||
                ("sell".equals(side) && !isBlank(next.sourceBuyOfferId));
            if (mayHaveFrozenFloor && next.lowestSellPrice <= 0 && next.suggestedBuyPrice > 0)
            {
                next.lowestSellPrice = OfferGuidanceResolver.freezeLowestSellPrice(
                    next.lowestSellPrice,
                    next.suggestedBuyPrice,
                    itemId);
            }
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

        GeSlotTimerLifecycle.State timerState = GeSlotTimerLifecycle.advance(
            previous == null ? 0 : previous.timerStartedAt,
            previous == null ? 0 : previous.lastFillAt,
            previous == null ? 0 : previous.startedAt,
            previous == null ? 0 : previous.timerFillHighWaterMark,
            previous == null ? 0 : previous.filledQuantity,
            next.filledQuantity,
            sameOffer,
            continuingReplacement,
            next.startedAt,
            observedAt);
        next.timerStartedAt = timerState.timerStartedAt;
        next.lastFillAt = timerState.lastFillAt;
        next.timerFillHighWaterMark = timerState.fillHighWaterMark;

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
        if (shouldRecordPriceTransition(
            reconciliation,
            sameOffer,
            previousFilled,
            next.filledQuantity))
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
                observedAt);
        }

        slotSnapshots.put(slotNumber, next);
        recordFlipCycle(next);
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
        recordFlipCycle(next);
        enqueue(next.toSyncEvent("slot_emptied"));
        persistCurrentAccount();
        refreshSidePanel();
        debug("GE-slot {} leeggemaakt{}", slotNumber, reconciliation ? " [reconciliatie]" : "");
    }

    private void enqueue(SyncEvent event)
    {
        if (activeStorageContext != null)
        {
            // Retain in RAM first as well: disk failures must be visible and must
            // not discard the incoming event or let later events overtake it.
            unjournaledEvents.addLast(event);
            storeUnjournaledEvents();
        }
        else
        {
            // Before an account context is available no delivery can occur.
            QueuedEvent queued = new QueuedEvent();
            queued.event = event;
            outbox.addLast(queued);
        }
        preemptOverviewForGeDelivery();
        if (snapshotInFlight || pendingSnapshot != null)
        {
            // Een snapshot-ID kan al als durable intent op de Worker bestaan,
            // ook wanneer de client alleen een time-out of HTTP 503 zag. Gooi
            // dat ID daarom nooit weg bij een volgende slotmutatie. Hervat eerst
            // exact dezelfde snapshot en stuur daarna de nieuwere toestand.
            snapshotPending = true;
            snapshotDirty = true;
        }
        persistCurrentAccount();
    }

    private void flushOutboxIfPossible()
    {
        if (!prepareStorageForDelivery())
        {
            return;
        }
        if (!started || workerRequests.isActive() || requestInFlight ||
            activeAccountHash == NO_ACCOUNT || !hasDeviceToken() ||
            statusInFlight || heartbeatInFlight || snapshotInFlight || slotStateInFlight || pairingInFlight ||
            now() < workerBackoffUntil)
        {
            return;
        }

        if (pendingSnapshot != null)
        {
            // Een half verwerkte snapshotreceipt moet vóór latere delta-events
            // met hetzelfde ID worden afgerond. Anders kan een nieuwe fill de
            // enige serverreceipt van de vorige tranche overschrijven.
            sendFullSnapshotIfPossible();
            return;
        }

        if (!hasQueuedEvents())
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

        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.EVENT, request);
        if (workerCall == null)
        {
            return;
        }
        requestInFlight = true;
        String eventId = queued.event.eventId;
        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.EVENT,
                    call,
                    () -> handleNetworkFailure(eventId, exception)));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.EVENT,
                    call,
                    () -> handleHttpResponse(eventId, statusCode, body)));
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
            healthFailure(SyncHealthTracker.Channel.EVENTS, "netwerkfout/time-out");
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
            SyncResponse syncResponse = parseSyncResponse(responseText);
            if (syncResponse == null || !syncResponse.isCompleteFor(eventId))
            {
                scheduleRetry(queued);
                registerWorkerBackoff(queued.nextAttemptAt);
                healthFailure(SyncHealthTracker.Channel.EVENTS, "ongeldig serverantwoord");
                persistCurrentAccount();
                return;
            }
            markWorkerSuccess();
            if (!acknowledgeQueuedEvent(eventId))
            {
                return;
            }
            if (successfulBuyLimitEvent(queued.event, syncResponse))
            {
                outboxBatchBuyLimitDirty = true;
            }
            applyServerSlotsFromResults(syncResponse.results);
            boolean rejected = syncResponse.summary.rejected > 0;
            if (rejected)
            {
                serverStateCheckPending = true;
                serverStateRetryAttempts = 0;
                serverStateNextAttemptAt = 0;
                healthFailure(SyncHealthTracker.Channel.EVENTS, "update geweigerd; herstelcontrole volgt");
                LOG.warn("Worker heeft GE-event {} inhoudelijk geweigerd; reconciliatie volgt", eventId);
            }
            else
            {
                if (!serverStateCheckPending)
                {
                    healthSuccess(SyncHealthTracker.Channel.EVENTS);
                }
                debug("GE-event {} door webapp ontvangen", eventId);
            }
            persistCurrentAccount();
            flushOutboxIfPossible();
            checkServerSlotStateIfPossible();
            refreshOverviewAfterCompletedOutboxBatch();
            return;
        }

        if (statusCode == 401 || statusCode == 403)
        {
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }

        if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429)
        {
            if (!acknowledgeQueuedEvent(eventId))
            {
                return;
            }
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            persistCurrentAccount();
            healthFailure(SyncHealthTracker.Channel.EVENTS, "HTTP " + statusCode + "; herstelcontrole volgt");
            LOG.error("GE-event {} definitief geweigerd met HTTP {}", eventId, statusCode);
            flushOutboxIfPossible();
            refreshOverviewAfterCompletedOutboxBatch();
            return;
        }

        scheduleRetry(queued);
        registerWorkerBackoff(queued.nextAttemptAt);
        persistCurrentAccount();
        healthFailure(SyncHealthTracker.Channel.EVENTS, "HTTP " + statusCode);
        LOG.warn("GE-synchronisatie kreeg HTTP {}; nieuwe poging volgt", statusCode);
    }

    private void sendFullSnapshotIfPossible()
    {
        boolean continuingDurableSnapshot = pendingSnapshot != null;
        if (!snapshotPending || workerRequests.isActive() || snapshotInFlight || requestInFlight ||
            loginReconciliationPending ||
            (hasQueuedEvents() && !continuingDurableSnapshot) ||
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

        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.SNAPSHOT, request);
        if (workerCall == null)
        {
            return;
        }
        snapshotInFlight = true;
        snapshotDirty = false;
        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.SNAPSHOT,
                    call,
                    () -> handleSnapshotFailure(snapshotId, exception)));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.SNAPSHOT,
                    call,
                    () -> handleSnapshotResponse(snapshotId, statusCode, body)));
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
        serverStateCheckPending = true;
        if (pendingSnapshot != null && Objects.equals(pendingSnapshot.snapshotId, snapshotId))
        {
            healthFailure(SyncHealthTracker.Channel.STATE, "netwerkfout/time-out");
            // Een snapshot-ID is een durable intent op de Worker. Ook wanneer
            // RuneLite tijdens de request veranderde, moet eerst exact dezelfde
            // snapshot worden hervat; na succes plant snapshotDirty vanzelf een
            // verse opvolger. Een nieuw ID zou een half verwerkte serverreceipt
            // kunnen achterlaten zonder lifecycle- of cashherstel.
            scheduleSnapshotRetry(pendingSnapshot);
            persistCurrentAccount();
            registerWorkerBackoff(pendingSnapshot.nextAttemptAt);
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

        ServerStateResponse stateResponse = parseServerStateResponse(body);
        if (statusCode >= 200 && statusCode < 300 && stateResponse != null && stateResponse.success)
        {
            markWorkerSuccess();
            pendingSnapshot = null;
            snapshotPending = snapshotDirty;
            snapshotDirty = false;
            if (snapshotPending)
            {
                snapshotReason = "changed_during_snapshot";
            }
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;

            boolean trustedSnapshotState = SnapshotSyncPolicy.canUseSuccessfulSnapshotState(
                statusCode,
                stateResponse.success,
                !Boolean.FALSE.equals(stateResponse.reconcile_required),
                serverSlotNumbers(stateResponse.data));
            boolean stateMatches = false;
            if (trustedSnapshotState)
            {
                stateMatches = compareAndMergeServerState(stateResponse.data);
                if (stateMatches)
                {
                    serverStateCheckPending = false;
                    healthSuccess(SyncHealthTracker.Channel.STATE);
                    if (!hasQueuedEvents())
                    {
                        healthSuccess(SyncHealthTracker.Channel.EVENTS);
                    }
                }
                else
                {
                    healthFailure(SyncHealthTracker.Channel.STATE, "slotverschil; herstel actief");
                    LOG.warn("Slotsnapshot wijkt af van de actuele RuneLite-slots; herstelsnapshot wordt gestuurd");
                    boolean recoveryQueued = snapshotPending;
                    if (!recoveryQueued)
                    {
                        recoveryQueued = reconcileAllSlots(
                            "server_difference",
                            SnapshotSyncPolicy.ReconcileMode.ALWAYS) && snapshotPending;
                    }
                    serverStateCheckPending = !recoveryQueued;
                }
            }
            else
            {
                // Alleen een expliciet complete, conflictvrije acht-slotrespons mag
                // de aparte /state-controle vervangen.
                applyServerStateRows(stateResponse.data);
                serverStateCheckPending = true;
                healthFailure(SyncHealthTracker.Channel.STATE, "onvolledig antwoord; controle volgt");
            }

            persistCurrentAccount();
            if (manualSyncPending)
            {
                if (trustedSnapshotState && SnapshotSyncPolicy.canFinishManualSync(
                    !stateMatches,
                    snapshotPending,
                    snapshotInFlight,
                    !hasQueuedEvents()))
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
            requestFreshBuyLimitOverview();
            return;
        }

        if (statusCode == 401 || statusCode == 403)
        {
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }

        if ((statusCode == 202 || statusCode == 503) && stateResponse != null &&
            "snapshot_processing".equals(stateResponse.code))
        {
            // Een grote inhaalsnapshot wordt door de Worker bewust in kleine,
            // CPU-veilige delen verwerkt. Dit is voortgang, geen storing: houd
            // hetzelfde durable ID vast en vraag de volgende tranche snel op.
            pendingSnapshot.nextAttemptAt = now() + 1L;
            snapshotPending = true;
            persistCurrentAccount();
            debug("Worker verwerkt slotsnapshot {} verder", snapshotId);
            flushOutboxIfPossible();
            return;
        }

        if (statusCode == 409 ||
            (stateResponse != null && Boolean.TRUE.equals(stateResponse.reconcile_required)))
        {
            healthFailure(SyncHealthTracker.Channel.STATE, "slotconflict; herstel actief");
            applyServerStateRows(stateResponse == null ? null : stateResponse.data);
            pendingSnapshot = null;
            snapshotPending = snapshotDirty;
            snapshotDirty = false;
            if (snapshotPending)
            {
                snapshotReason = "changed_during_conflicted_snapshot";
            }
            serverStateCheckPending = true;
            serverStateRetryAttempts = 0;
            serverStateNextAttemptAt = 0;
            persistCurrentAccount();
            if (manualSyncPending)
            {
                setConnectionStatus("Synchronisatie controleren...");
            }
            LOG.warn("Slotsnapshot conflicteerde met een nieuwere servertoestand; automatische reconciliatie volgt");
            flushOutboxIfPossible();
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
            healthFailure(SyncHealthTracker.Channel.STATE, "HTTP " + statusCode + "; controle volgt");
            LOG.error("Slotsnapshot definitief geweigerd met HTTP {}", statusCode);
            return;
        }

        // Behoud hetzelfde snapshot-ID bij tijdelijke HTTP-/serverfouten. Een
        // wijziging tijdens de request blijft in snapshotDirty staan en wordt
        // pas na de succesvolle retry als nieuwe snapshot verstuurd.
        scheduleSnapshotRetry(pendingSnapshot);
        serverStateCheckPending = true;
        registerWorkerBackoff(pendingSnapshot.nextAttemptAt);
        persistCurrentAccount();
        if (manualSyncPending)
        {
            setConnectionStatus("Synchronisatie tijdelijk mislukt; automatische nieuwe poging volgt...");
        }
        healthFailure(SyncHealthTracker.Channel.STATE,
            statusCode >= 200 && statusCode < 300 ? "ongeldig serverantwoord" : "HTTP " + statusCode);
        LOG.warn("Volledige GE-slotsnapshot kreeg HTTP {}; automatische nieuwe poging volgt", statusCode);
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
            loginReconciliationPending ||
            hasQueuedEvents() || snapshotPending || !hasDeviceToken() ||
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

        Request request = authorizedRequest(endpoint).get().build();
        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.STATE, request);
        if (workerCall == null)
        {
            return;
        }
        slotStateInFlight = true;
        serverStateCheckPending = false;
        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.STATE,
                    call,
                    () -> {
                    slotStateInFlight = false;
                    serverStateCheckPending = true;
                    serverStateNextAttemptAt = scheduleTransientRetry(++serverStateRetryAttempts);
                    registerWorkerBackoff(serverStateNextAttemptAt);
                    if (manualSyncPending)
                    {
                        setConnectionStatus("Synchronisatie controleren; Worker tijdelijk niet bereikbaar...");
                    }
                    healthFailure(SyncHealthTracker.Channel.STATE, "netwerkfout/time-out");
                    debug("Server-slotversies konden niet worden opgehaald; nieuwe poging na {} seconden: {}",
                        Math.max(0, serverStateNextAttemptAt - now()), exception.getMessage());
                    }));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.STATE,
                    call,
                    () -> handleServerStateResponse(statusCode, body)));
            }
        });
    }

    private void handleServerStateResponse(int statusCode, String body)
    {
        slotStateInFlight = false;
        if (statusCode >= 200 && statusCode < 300)
        {
            ServerStateResponse response = parseServerStateResponse(body);
            if (response == null || !response.success || !hasCompleteServerState(response.data))
            {
                serverStateCheckPending = true;
                serverStateNextAttemptAt = scheduleTransientRetry(++serverStateRetryAttempts);
                registerWorkerBackoff(serverStateNextAttemptAt);
                if (manualSyncPending)
                {
                    setConnectionStatus("Synchronisatie controleren; onvolledig serverantwoord...");
                }
                healthFailure(SyncHealthTracker.Channel.STATE, "onvolledig serverantwoord");
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
        healthFailure(SyncHealthTracker.Channel.STATE, "HTTP " + statusCode);
        debug("Server-slotversiecontrole kreeg HTTP {}; nieuwe poging na {} seconden",
            statusCode, Math.max(0, serverStateNextAttemptAt - now()));
    }

    private void reconcileWithServerState(List<ServerSlotState> serverRows)
    {
        boolean stateMatches = compareAndMergeServerState(serverRows);
        if (!stateMatches)
        {
            healthFailure(SyncHealthTracker.Channel.STATE, "slotverschil; herstel actief");
            LOG.warn("Verschil tussen RuneLite en de webapp gevonden; volledige slotsnapshot wordt gestuurd");
            boolean recoveryQueued = reconcileAllSlots(
                "server_difference",
                SnapshotSyncPolicy.ReconcileMode.ALWAYS) && snapshotPending;
            serverStateCheckPending = !recoveryQueued;
            if (!recoveryQueued)
            {
                serverStateNextAttemptAt = scheduleTransientRetry(++serverStateRetryAttempts);
                registerWorkerBackoff(serverStateNextAttemptAt);
            }
            return;
        }

        serverStateCheckPending = false;
        if (manualSyncPending && SnapshotSyncPolicy.canFinishManualSync(
            false,
            snapshotPending,
            snapshotInFlight,
            !hasQueuedEvents()))
        {
            finishManualSync();
        }
        healthSuccess(SyncHealthTracker.Channel.STATE);
        if (!hasQueuedEvents() && !snapshotPending)
        {
            healthSuccess(SyncHealthTracker.Channel.EVENTS);
        }
        debug("Lokale GE-slots en serverversies zijn gelijk");
    }

    private boolean compareAndMergeServerState(List<ServerSlotState> serverRows)
    {
        if (serverRows == null)
        {
            return false;
        }
        Map<Integer, ServerSlotState> bySlot = new HashMap<>();
        for (ServerSlotState row : serverRows)
        {
            if (row != null && row.slot_number >= 1 && row.slot_number <= SLOT_COUNT)
            {
                bySlot.put(row.slot_number, row);
            }
        }

        boolean mismatch = !hasCompleteServerState(serverRows);
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
        return !mismatch;
    }

    private static boolean hasCompleteServerState(List<ServerSlotState> rows)
    {
        return SnapshotSyncPolicy.isCompleteSlotSet(serverSlotNumbers(rows));
    }

    private static List<Integer> serverSlotNumbers(List<ServerSlotState> rows)
    {
        if (rows == null)
        {
            return Collections.emptyList();
        }
        List<Integer> slotNumbers = new ArrayList<>(rows.size());
        for (ServerSlotState row : rows)
        {
            slotNumbers.add(row == null ? null : row.slot_number);
        }
        return slotNumbers;
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
        boolean firstServerAdoption = local.serverVersion <= 0;
        local.serverVersion = Math.max(0, server.version);
        if (!"empty".equals(local.status) && !isBlank(server.runelite_offer_id))
        {
            FlipCyclePlanBook.Cycle previousCycle = cycleForSnapshot(local);
            if (previousCycle != null)
            {
                // Legacy snapshots may use offerId as their implicit cycle key.
                // Preserve that link before adopting the server's offer identity.
                local.sourceBuyOfferId = previousCycle.cycleId;
                flipCycles.adoptOfferIdentity(
                    previousCycle.cycleId, local.itemId, local.side,
                    local.offerId, server.runelite_offer_id);
            }
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
            FlipCyclePlanBook.Cycle linkedCycle = cycleForSnapshot(local);
            boolean linkedCycleClosed = linkedCycle != null && linkedCycle.isClosed();
            boolean adoptBuyPrice = server.suggested_buy_price > 0 &&
                !linkedCycleClosed &&
                (firstServerAdoption || local.suggestedBuyPrice <= 0);
            if (adoptBuyPrice)
            {
                local.suggestedBuyPrice = server.suggested_buy_price;
            }
            if (server.suggested_sell_price > 0 && !linkedCycleClosed)
            {
                local.suggestedSellPrice = SellTargetPriceResolver.raiseOnly(
                    local.suggestedSellPrice,
                    server.suggested_sell_price);
            }
            if (!linkedCycleClosed)
            {
                local.lowestSellPrice = OfferGuidanceResolver.adoptServerLowestSellPrice(
                    local.lowestSellPrice,
                    server.lowest_sell_price);
            }
            if (linkedCycle != null && !linkedCycleClosed)
            {
                flipCycles.adoptFrozenBuyPlan(
                    linkedCycle.cycleId,
                    adoptBuyPrice ? server.suggested_buy_price : 0,
                    server.lowest_sell_price);
                flipCycles.raiseSellTarget(
                    linkedCycle.cycleId,
                    local.suggestedSellPrice);
                FlipCyclePlanBook.Cycle adoptedCycle = flipCycles.cycle(linkedCycle.cycleId);
                local.suggestedBuyPrice = adoptedCycle.frozenBuyPrice;
                local.lowestSellPrice = adoptedCycle.lowestSellPrice;
                // Only live state changes here. Queued events and snapshot
                // payloads retain the exact data already submitted for replay.
                for (SlotSnapshot linked : slotSnapshots.values())
                {
                    if (linked != null && linked.itemId == local.itemId &&
                        linkedCycle.cycleId.equals(linked.sourceBuyOfferId))
                    {
                        linked.suggestedBuyPrice = adoptedCycle.frozenBuyPrice;
                        linked.lowestSellPrice = adoptedCycle.lowestSellPrice;
                        linked.fingerprint = fingerprint(linked);
                    }
                }
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
            if (row == null || row.slot_number < 1 || row.slot_number > SLOT_COUNT)
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
        return workerRequests.isActive() || pairingInFlight || statusInFlight || heartbeatInFlight || requestInFlight ||
            snapshotInFlight || slotStateInFlight || cashInFlight || overviewInFlight;
    }

    private Call beginWorkerRequest(WorkerRequestCoordinator.Kind kind, Request request)
    {
        if (!started || (kind != WorkerRequestCoordinator.Kind.PAIRING &&
            (credentialTokenFor(request.url()).isEmpty() || !Objects.equals(request.header("Authorization"),
                "Bearer " + credentialTokenFor(request.url()))))) return null;
        if (kind != WorkerRequestCoordinator.Kind.PAIRING && activeStorageContext != null &&
            !activeStorageContext.accountKey.equals(SyncStorageContext.capture(config, activeAccountHash).accountKey))
        {
            return null;
        }
        if ((kind == WorkerRequestCoordinator.Kind.EVENT || kind == WorkerRequestCoordinator.Kind.SNAPSHOT ||
            kind == WorkerRequestCoordinator.Kind.CASH) && !prepareStorageForDelivery())
        {
            return null;
        }
        synchronized (lifecycleLock)
        {
            if (!started) return null;
            Call call = httpClient.newCall(request.newBuilder().tag(new WorkerRequestContext(configProfileKey())).build());
            return workerRequests.begin(kind, call, call::cancel) ? call : null;
        }
    }

    private void finishWorkerRequest(
        WorkerRequestCoordinator.Kind kind,
        Call call,
        Runnable responseHandler)
    {
        WorkerRequestCoordinator.Completion completion = workerRequests.complete(kind, call);
        if (completion.status == WorkerRequestCoordinator.CompletionStatus.STALE)
        {
            return;
        }

        try
        {
            boolean currentContext = started && call.request().tag() instanceof WorkerRequestContext &&
                Objects.equals(((WorkerRequestContext) call.request().tag()).profile, configProfileKey()) &&
                (kind == WorkerRequestCoordinator.Kind.PAIRING
                ? Objects.equals(call.request().url(), endpoint(PAIR_PATH))
                : (activeStorageContext == null ||
                    activeStorageContext.accountKey.equals(SyncStorageContext.capture(config, activeAccountHash).accountKey)) &&
                    !credentialTokenFor(call.request().url()).isEmpty() &&
                    Objects.equals(call.request().header("Authorization"), "Bearer " + credentialTokenFor(call.request().url())));
            if (completion.shouldHandleResponse() && currentContext)
            {
                responseHandler.run();
            }
            else
            {
                clearWorkerInFlight(kind);
                debug("Lokale annulering van Worker-request {} ({})",
                    kind, completion.cancellation);
            }
        }
        finally
        {
            pumpWorkerRequests();
        }
    }

    private void clearWorkerInFlight(WorkerRequestCoordinator.Kind kind)
    {
        switch (kind)
        {
            case PAIRING:
                pairingInFlight = false;
                break;
            case STATUS:
                statusInFlight = false;
                break;
            case HEARTBEAT:
                heartbeatInFlight = false;
                break;
            case EVENT:
                requestInFlight = false;
                break;
            case SNAPSHOT:
                snapshotInFlight = false;
                break;
            case STATE:
                slotStateInFlight = false;
                break;
            case OVERVIEW:
                overviewInFlight = false;
                overviewInFlightFocusItemId = 0;
                overviewInFlightFreshMarket = false;
                overviewInFlightFreshBuyLimits = false;
                break;
            case CASH:
                cashInFlight = false;
                cashInFlightUpdate = null;
                break;
            default:
                break;
        }
    }

    private void preemptOverviewForGeDelivery()
    {
        if (workerRequests.activeKind() != WorkerRequestCoordinator.Kind.OVERVIEW)
        {
            return;
        }
        rememberOverviewRequest(overviewInFlightFocusItemId,
            overviewInFlightFreshMarket, overviewInFlightFreshBuyLimits);
        workerRequests.cancelOverview(WorkerRequestCoordinator.Cancellation.OVERVIEW_PREEMPTED);
    }

    private void pumpWorkerRequests()
    {
        if (!started || workerPumpActive || workerRequests.isActive())
        {
            return;
        }

        if (!prepareStorageForDelivery())
        {
            return;
        }

        workerPumpActive = true;
        try
        {
            boolean fullOverviewDue = (overviewRefreshPending ||
                syncHealth.failed(SyncHealthTracker.Channel.OVERVIEW)) &&
                now() >= syncHealth.retryAt(SyncHealthTracker.Channel.OVERVIEW);
            boolean focusOverviewDue = pendingFocusedOverviewItemId > 0 &&
                pendingFocusedOverviewItemId == focusedGeItemId &&
                now() >= syncHealth.retryAt(SyncHealthTracker.Channel.FOCUS);
            WorkerRequestCoordinator.Kind next = nextWorkerRequestKind(
                pendingSnapshot != null,
                hasQueuedEvents(),
                snapshotPending,
                serverStateCheckPending && client.getGameState() == GameState.LOGGED_IN,
                pendingCashUpdate != null,
                statusCheckPending,
                fullOverviewDue || focusOverviewDue,
                heartbeatNextAttemptAt > 0);
            if (next == null)
            {
                return;
            }
            switch (next)
            {
                case EVENT:
                case SNAPSHOT:
                    flushOutboxIfPossible();
                    break;
                case STATE:
                    checkServerSlotStateIfPossible();
                    break;
                case CASH:
                    sendPendingCashIfPossible();
                    break;
                case STATUS:
                    checkDeviceStatus();
                    break;
                case OVERVIEW:
                    if (fullOverviewDue) requestOverview(false);
                    else requestFocusedOverview(pendingFocusedOverviewItemId);
                    break;
                case HEARTBEAT:
                    sendHeartbeat();
                    break;
                default:
                    break;
            }
        }
        finally
        {
            workerPumpActive = false;
        }
    }

    static WorkerRequestCoordinator.Kind nextWorkerRequestKind(
        boolean snapshotContinuation,
        boolean eventPending,
        boolean snapshotPending,
        boolean statePending,
        boolean cashPending,
        boolean statusPending,
        boolean overviewPending,
        boolean heartbeatPending)
    {
        if (snapshotContinuation)
        {
            return WorkerRequestCoordinator.Kind.SNAPSHOT;
        }
        if (eventPending)
        {
            return WorkerRequestCoordinator.Kind.EVENT;
        }
        if (snapshotPending)
        {
            return WorkerRequestCoordinator.Kind.SNAPSHOT;
        }
        if (statePending)
        {
            return WorkerRequestCoordinator.Kind.STATE;
        }
        if (cashPending)
        {
            return WorkerRequestCoordinator.Kind.CASH;
        }
        if (statusPending)
        {
            return WorkerRequestCoordinator.Kind.STATUS;
        }
        if (overviewPending)
        {
            return WorkerRequestCoordinator.Kind.OVERVIEW;
        }
        return heartbeatPending ? WorkerRequestCoordinator.Kind.HEARTBEAT : null;
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
        // A success from another endpoint must not erase a still-active shared
        // circuit-breaker delay. Once elapsed, clearing is only housekeeping.
        workerBackoffUntil = workerBackoffAfterSuccess(workerBackoffUntil, now());
    }

    static long workerBackoffAfterSuccess(long currentBackoffUntil, long currentTime)
    {
        return currentBackoffUntil <= currentTime ? 0 : currentBackoffUntil;
    }

    private Request.Builder authorizedRequest(HttpUrl endpoint)
    {
        Request.Builder builder = new Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("X-RuneLite-Plugin-Version", PLUGIN_VERSION)
            .header("X-RuneLite-Device-Name", deviceName());
        String token = credentialTokenFor(endpoint);
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return builder;
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

    private static boolean successfulBuyLimitEvent(SyncEvent event, SyncResponse response)
    {
        if (event == null || response == null || !response.isCompleteFor(event.eventId))
        {
            return false;
        }
        return syncResultChangesBuyLimit(event.side, event.eventType, response.results.get(0).outcome);
    }

    static boolean syncResultChangesBuyLimit(String side, String eventType, String outcome)
    {
        // Guidance events repeat the cumulative fill, but only change prices.
        // The Worker may classify those as partial_buy too, so its outcome or
        // classification alone cannot establish that buy-limit usage changed.
        // Keep refreshes for real fills/reservations and unknown legacy events,
        // including a duplicate acknowledgement after a lost network reply.
        return "buy".equals(side) &&
            !"guidance_updated".equals(eventType) &&
            ("applied".equals(outcome) || "duplicate".equals(outcome));
    }

    static OutboxOverviewRefresh outboxOverviewRefresh(
        boolean outboxEmpty,
        boolean buyLimitDirty)
    {
        if (!outboxEmpty)
        {
            return OutboxOverviewRefresh.NONE;
        }
        return buyLimitDirty
            ? OutboxOverviewRefresh.FRESH_BUY_LIMITS
            : OutboxOverviewRefresh.NORMAL;
    }

    private void refreshOverviewAfterCompletedOutboxBatch()
    {
        OutboxOverviewRefresh refresh = outboxOverviewRefresh(
            !hasQueuedEvents(),
            outboxBatchBuyLimitDirty);
        if (refresh == OutboxOverviewRefresh.NONE)
        {
            return;
        }

        outboxBatchBuyLimitDirty = false;
        if (refresh == OutboxOverviewRefresh.FRESH_BUY_LIMITS)
        {
            requestFreshBuyLimitOverview();
        }
        else
        {
            requestOverview(true);
        }
    }

    enum OutboxOverviewRefresh
    {
        NONE,
        NORMAL,
        FRESH_BUY_LIMITS
    }

    private void scheduleRetry(QueuedEvent queued)
    {
        queued.attempts = Math.max(0, queued.attempts) + 1;
        queued.nextAttemptAt = scheduleTransientRetry(queued.attempts);
    }

    private void switchToCurrentAccount()
    {
        initializeCredentials();
        long accountHash = client.getAccountHash();
        String profileKey = configProfileKey();
        SyncStorageContext candidate = SyncStorageContext.capture(config, accountHash);
        if (accountHash == -1L)
        {
            // Ordinary logout keeps the current queue. A new connection while
            // logged out gets its own unbound context, never the old RS account.
            accountHash = activeStorageContext != null &&
                activeStorageContext.connectionKey.equals(candidate.connectionKey)
                ? activeAccountHash : NO_ACCOUNT;
            candidate = SyncStorageContext.capture(config, accountHash);
        }
        if (activeStorageContext != null && activeStorageContext.accountKey.equals(candidate.accountKey) &&
            Objects.equals(activeConfigProfileKey, profileKey))
        {
            return;
        }

        ensureLegacyConnectionBinding();
        persistCurrentAccount();
        if (activeStorageContext != null && storageBlocked)
        {
            // Preserve unwritten data in this process even when the user pairs
            // another device while their disk is unavailable.
            unsavedAccounts.put(activeStorageContext.accountKey,
                gson.fromJson(gson.toJson(captureAccountState()), AccountState.class));
        }
        slotSnapshots.clear();
        flipCycles.clear();
        outbox.clear();
        unjournaledEvents.clear();
        eventJournal = null;
        journalSize = 0;
        storageRetryAt = 0;
        storageBlocked = false;
        storageInitialized = false;
        lastPersistedStateJson = null;
        pendingSnapshot = null;
        snapshotSequence = 0;
        activeAccountHash = accountHash;
        activeStorageContext = candidate;
        activeConfigProfileKey = profileKey;
        sessionStats.reset();
        lastTradePrices.clear();
        geItemPresence.clear();
        invalidateMarketPriceContext();
        marketPrices.clear();
        marketPriceQueue.clear();
        queuedMarketPriceItems.clear();
        overview = RuneliteOverviewView.empty();
        invalidateOverviewContext();
        clearAccountScopedCashQueue();
        overviewRefreshPending = false;
        overviewFreshMarketPending = false;
        overviewTicks = OVERVIEW_GAME_TICKS;
        loadCurrentAccount();
        requestInFlight = false;
        snapshotPending = activeAccountHash != NO_ACCOUNT || pendingSnapshot != null;
        snapshotReason = "account_switch";
        serverStateCheckPending = true;
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            loginReconciliationPending = true;
            loggedInTicks = 0;
        }
        refreshSidePanel();
        requestMarketPrices(false);
        debug("Accountstatus geladen voor hash {}", accountKey());
    }

    private void clearAccountScopedCashQueue()
    {
        // Een lopende call wordt door invalidateOverviewContext() geannuleerd. Wis
        // ook de last-write-wins waarden zelf: anders kan de callback na een
        // accountwissel het oude saldo onder het nieuwe account opnieuw aanbieden.
        pendingCashUpdate = null;
        cashInFlightUpdate = null;
        cashInFlight = false;
        cashRetryAttempts = 0;
    }

    private Path journalRoot()
    {
        if (eventJournalRoot == null)
        {
            eventJournalRoot = RuneLite.RUNELITE_DIR.toPath().resolve("osrs-flipper-sync").resolve("outbox");
        }
        return eventJournalRoot;
    }

    private String configProfileKey()
    {
        return configManager == null || configManager.getProfile() == null
            ? "default" : Long.toUnsignedString(configManager.getProfile().getId());
    }

    private void ensureLegacyConnectionBinding()
    {
        if (configManager == null)
        {
            return;
        }
        String profileKey = configProfileKey();
        if (!Objects.equals(legacyConfigProfileKey, profileKey))
        {
            // Each RuneLite profile has a different legacy configuration file.
            // Bind the newly selected profile to its own currently stored owner.
            legacyConfigProfileKey = profileKey;
            legacyConnectionKey = null;
            proposedLegacyConnectionKey = SyncStorageContext.capture(config, NO_ACCOUNT).connectionKey;
        }
        if (legacyConnectionKey != null)
        {
            return;
        }
        try
        {
            legacyConnectionKey = EventJournal.claimLegacyConnection(
                journalRoot(), profileKey, proposedLegacyConnectionKey);
        }
        catch (IOException exception)
        {
            localStorageFailure(exception);
        }
    }

    private void loadCurrentAccount()
    {
        if (activeStorageContext == null || configManager == null)
        {
            return;
        }
        try
        {
            List<SyncEvent> waitingEvents = new ArrayList<>(unjournaledEvents);
            PendingCashUpdate waitingCash = pendingCashUpdate;
            ensureLegacyConnectionBinding();
            if (legacyConnectionKey == null)
            {
                throw new IOException("Legacy storage identity has not been bound");
            }
            eventJournal = new EventJournal(journalRoot(), activeStorageContext.accountKey);
            AccountState legacy = null;
            List<EventJournal.Entry> legacyEvents = new ArrayList<>();
            if (!eventJournal.legacyImported(activeConfigProfileKey) && activeAccountHash != NO_ACCOUNT &&
                activeStorageContext.connectionKey.equals(legacyConnectionKey))
            {
                String outboxJson = legacyValue(OUTBOX_PREFIX);
                QueuedEvent[] events = isBlank(outboxJson) ? null : gson.fromJson(outboxJson, QueuedEvent[].class);
                if (events != null)
                {
                    for (QueuedEvent queued : events)
                    {
                        if (queued == null || queued.event == null || isBlank(queued.event.eventId))
                        {
                            throw new IOException("Invalid legacy event; migration stopped without discarding it");
                        }
                        legacyEvents.add(new EventJournal.Entry(queued.event.eventId, gson.toJson(queued.event)));
                    }
                }
                // Cache corruption must never clear the event history being migrated.
                legacy = readLegacyAccountState();
            }
            eventJournal.importLegacy(legacyEvents, legacy == null ? null : gson.toJson(legacy), activeConfigProfileKey);
            journalSize = eventJournal.size();
            refillOutbox();
            AccountState recovered = unsavedAccounts.get(activeStorageContext.accountKey);
            if (recovered == null)
            {
                String json = eventJournal.readState();
                recovered = isBlank(json) ? null : readAccountState(json);
            }
            if (recovered != null)
            {
                unjournaledEvents.clear();
                restoreAccountState(recovered);
            }
            Set<String> waitingIds = new HashSet<>();
            for (SyncEvent event : unjournaledEvents)
            {
                waitingIds.add(event.eventId);
            }
            for (SyncEvent event : waitingEvents)
            {
                if (waitingIds.add(event.eventId))
                {
                    unjournaledEvents.addLast(event);
                }
            }
            if (waitingCash != null)
            {
                pendingCashUpdate = waitingCash;
            }
            storageInitialized = true;
            storageBlocked = false;
            storageRetryAt = 0;
            storeUnjournaledEvents();
            persistCurrentAccount();
            if (!storageBlocked)
            {
                unsavedAccounts.remove(activeStorageContext.accountKey);
            }
        }
        catch (IOException | RuntimeException exception)
        {
            localStorageFailure(exception);
        }
    }

    private String legacyValue(String prefix)
    {
        return configManager.getConfiguration(OsrsFlipperSyncConfig.GROUP, prefix + accountKey());
    }

    private AccountState readAccountState(String json)
    {
        JsonObject object = gson.fromJson(json, JsonObject.class);
        if (object == null) throw new IllegalArgumentException("Missing account state");
        JsonElement prices = object.remove("lastTradePrices");
        JsonElement presence = object.remove("itemPresence");
        // Durable intents and cycle identities remain strict: if those cannot be
        // decoded, preserve the file and pause. Reconstructable caches are isolated.
        AccountState state = gson.fromJson(object, AccountState.class);
        state.lastTradePrices = readDerivedCache(prices, LastTradePriceBook.Entry[].class, "lastTradePrices");
        state.itemPresence = readDerivedCache(presence, GeItemPresenceBook.Entry[].class, "itemPresence");
        return state;
    }

    private <T> T readDerivedCache(JsonElement value, Class<T> type, String name)
    {
        try { return value == null ? null : gson.fromJson(value, type); }
        catch (RuntimeException exception)
        {
            LOG.warn("Lokale cache {} is onleesbaar; GE-events en opdrachten blijven bewaard", name);
            return null;
        }
    }

    private <T> T readLegacyJson(String prefix, Class<T> type)
    {
        try
        {
            String value = legacyValue(prefix);
            return isBlank(value) ? null : gson.fromJson(value, type);
        }
        catch (RuntimeException exception)
        {
            LOG.warn("Lokale cache {} is onleesbaar; GE-events blijven bewaard", prefix);
            return null;
        }
    }

    private AccountState readLegacyAccountState()
    {
        AccountState state = new AccountState();
        state.slots = readLegacyJson(STATE_PREFIX, SlotSnapshot[].class);
        state.pendingSnapshot = readLegacyJson(PENDING_SNAPSHOT_PREFIX, PendingSnapshot.class);
        state.lastTradePrices = readLegacyJson(LAST_TRADE_PRICES_PREFIX, LastTradePriceBook.Entry[].class);
        state.itemPresence = readLegacyJson(GE_ITEM_PRESENCE_PREFIX, GeItemPresenceBook.Entry[].class);
        state.cycles = readLegacyJson(FLIP_CYCLES_PREFIX, FlipCyclePlanBook.Cycle[].class);
        try
        {
            String sequence = legacyValue(SNAPSHOT_SEQUENCE_PREFIX);
            state.snapshotSequence = isBlank(sequence) ? 0 : Math.max(0, Long.parseLong(sequence));
        }
        catch (RuntimeException exception)
        {
            LOG.warn("Lokale snapshotvolgorde is onleesbaar; bestaande snapshot blijft bewaard");
        }
        return state;
    }

    private AccountState captureAccountState()
    {
        AccountState state = new AccountState();
        state.slots = slotSnapshots.values().toArray(new SlotSnapshot[0]);
        state.snapshotSequence = snapshotSequence;
        state.pendingSnapshot = pendingSnapshot;
        state.lastTradePrices = lastTradePrices.persistedEntries().toArray(new LastTradePriceBook.Entry[0]);
        state.itemPresence = geItemPresence.persistedEntries().toArray(new GeItemPresenceBook.Entry[0]);
        state.cycles = flipCycles.persistedCycles();
        state.pendingCash = pendingCashUpdate;
        state.unjournaled = unjournaledEvents.toArray(new SyncEvent[0]);
        return state;
    }

    private void restoreAccountState(AccountState state)
    {
        if (state.slots != null)
        {
            for (SlotSnapshot slot : state.slots)
            {
                if (slot != null && slot.slotNumber >= 1 && slot.slotNumber <= SLOT_COUNT)
                {
                    slotSnapshots.put(slot.slotNumber, slot);
                }
            }
        }
        snapshotSequence = Math.max(0, state.snapshotSequence);
        pendingSnapshot = state.pendingSnapshot;
        if (pendingSnapshot != null)
        {
            snapshotPending = true;
            snapshotReason = isBlank(pendingSnapshot.reason) ? "retry" : pendingSnapshot.reason;
        }
        lastTradePrices.restore(state.lastTradePrices);
        geItemPresence.restore(state.itemPresence);
        flipCycles.restore(state.cycles);
        recoverFlipCyclesFromSlots();
        pendingCashUpdate = state.pendingCash != null && state.pendingCash.isValid() ? state.pendingCash : null;
        if (state.unjournaled != null)
        {
            Collections.addAll(unjournaledEvents, state.unjournaled);
        }
    }

    private void persistCurrentAccount()
    {
        if (activeStorageContext == null || eventJournal == null || !storageInitialized)
        {
            return;
        }
        try
        {
            String json = gson.toJson(captureAccountState());
            if (!json.equals(lastPersistedStateJson))
            {
                eventJournal.writeState(json);
                lastPersistedStateJson = json;
            }
        }
        catch (IOException | RuntimeException exception)
        {
            localStorageFailure(exception);
        }
    }

    private void storeUnjournaledEvents()
    {
        if (!storageInitialized || eventJournal == null || (storageBlocked && now() < storageRetryAt))
        {
            return;
        }
        try
        {
            while (!unjournaledEvents.isEmpty())
            {
                SyncEvent event = unjournaledEvents.peekFirst();
                eventJournal.append(event.eventId, gson.toJson(event));
                unjournaledEvents.removeFirst();
            }
            journalSize = eventJournal.size();
            if (outbox.isEmpty())
            {
                refillOutbox();
            }
        }
        catch (IOException | RuntimeException exception)
        {
            localStorageFailure(exception);
        }
    }

    private void refillOutbox() throws IOException
    {
        if (eventJournal == null || !outbox.isEmpty())
        {
            return;
        }
        List<QueuedEvent> head = new ArrayList<>();
        for (EventJournal.Entry entry : eventJournal.readHead(MAX_OUTBOX_SIZE))
        {
            SyncEvent event = gson.fromJson(entry.eventJson, SyncEvent.class);
            if (event == null || !entry.eventId.equals(event.eventId))
            {
                throw new IOException("Stored event identity does not match its journal entry");
            }
            QueuedEvent queued = new QueuedEvent();
            queued.event = event;
            head.add(queued);
        }
        outbox.addAll(head);
    }

    private boolean acknowledgeQueuedEvent(String eventId)
    {
        try
        {
            if (eventJournal != null)
            {
                if (!eventJournal.acknowledge(eventId))
                {
                    throw new IOException("Journal head changed before acknowledgement");
                }
                journalSize = Math.max(0, journalSize - 1);
            }
            outbox.removeFirst();
            refillOutbox();
            return true;
        }
        catch (IOException | RuntimeException exception)
        {
            localStorageFailure(exception);
            return false;
        }
    }

    private boolean hasQueuedEvents()
    {
        return !outbox.isEmpty() || journalSize > 0 || !unjournaledEvents.isEmpty() || storageBlocked;
    }

    private boolean prepareStorageForDelivery()
    {
        if (activeStorageContext == null)
        {
            return true;
        }
        if (!activeStorageContext.accountKey.equals(SyncStorageContext.capture(config, activeAccountHash).accountKey))
        {
            return false;
        }
        if (storageBlocked && now() < storageRetryAt)
        {
            return false;
        }
        if (!storageInitialized)
        {
            loadCurrentAccount();
        }
        else if (storageBlocked || !unjournaledEvents.isEmpty())
        {
            if (storageBlocked)
            {
                // Re-read the durable head after an I/O failure or a competing
                // local acknowledgement. Never keep retrying a stale RAM head.
                outbox.clear();
            }
            storageBlocked = false;
            storeUnjournaledEvents();
            persistCurrentAccount();
            try
            {
                refillOutbox();
            }
            catch (IOException | RuntimeException exception)
            {
                localStorageFailure(exception);
            }
        }
        if (!storageBlocked)
        {
            storageRetryAt = 0;
            if (syncHealth.failed(SyncHealthTracker.Channel.STORAGE))
            {
                healthSuccess(SyncHealthTracker.Channel.STORAGE);
            }
        }
        return storageInitialized && !storageBlocked && unjournaledEvents.isEmpty();
    }

    private void localStorageFailure(Exception exception)
    {
        if (!storageBlocked || now() >= storageRetryAt)
        {
            LOG.error("Lokale synchronisatieopslag niet beschikbaar; verzending gepauzeerd, gegevens blijven bewaard",
                exception);
        }
        storageBlocked = true;
        storageRetryAt = now() + 15;
        healthFailure(SyncHealthTracker.Channel.STORAGE, "lokale opslag niet beschikbaar; verzending gepauzeerd");
    }

    private void clearStoredPairing(String status)
    {
        ensureLegacyConnectionBinding();
        persistCurrentAccount();
        invalidateOverviewContext();
        updatingPairing = true;
        try
        {
            pairingCredentials = null;
            try { credentialStore().delete(configProfileKey()); }
            catch (IOException exception) { LOG.warn("Ongeldige lokale tokenopslag kon niet worden verwijderd"); }
            configManager.unsetConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken");
            setStoredValue("deviceId", "");
            setStoredValue("ownerEmail", "");
            setStoredValue("linkedAt", "");
        }
        finally
        {
            updatingPairing = false;
        }
        switchToCurrentAccount();
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
        cashRetryAttempts = 0;
        workerBackoffUntil = 0;
        setConnectionStatus(status);
        healthFailure(SyncHealthTracker.Channel.CONNECTION, "ongeldig of ingetrokken");
        LOG.warn("RuneLite-apparaatkoppeling is niet langer geldig");
    }

    private void invalidateOverviewContext()
    {
        workerRequests.cancelActive(WorkerRequestCoordinator.Cancellation.CONTEXT_CHANGED);
        syncHealth.clear();
        updateHealthPanel();
        overviewContextGeneration++;
        overviewRequestGeneration++;
        overviewInFlight = false;
        overviewInFlightFocusItemId = 0;
        pendingFocusedOverviewItemId = 0;
        overviewRefreshPending = false;
        overviewFreshMarketPending = false;
        overviewFreshBuyLimitsPending = false;
        overviewInFlightFreshMarket = false;
        overviewInFlightFreshBuyLimits = false;
        outboxBatchBuyLimitDirty = false;
    }

    private void setConnectionStatus(String value)
    {
        String status = isBlank(value) ? "Onbekende status" : value;
        setStoredValue("connectionStatus", status);
        OsrsFlipperSyncPanel currentPanel = panel;
        if (currentPanel != null)
        {
            currentPanel.setConnectionStatus(status);
        }
    }

    private void healthFailure(SyncHealthTracker.Channel channel, String safeReason)
    {
        syncHealth.fail(channel, safeReason, now());
        if (channel == SyncHealthTracker.Channel.OVERVIEW)
        {
            overview = overview.withMarketUnavailable();
            refreshSidePanel();
        }
        LOG.warn("{}: {}; automatische herstelcontrole blijft actief", channel.label, safeReason);
        updateHealthPanel();
    }

    private void healthSuccess(SyncHealthTracker.Channel channel)
    {
        syncHealth.succeed(channel, now());
        updateHealthPanel();
    }

    private void updateHealthPanel()
    {
        OsrsFlipperSyncPanel currentPanel = panel;
        if (currentPanel != null)
        {
            currentPanel.updateHealth(syncHealth.banner((int) Math.min(Integer.MAX_VALUE,
                Math.max(journalSize, outbox.size()) + unjournaledEvents.size())));
        }
    }

    private void setStoredValue(String key, String value)
    {
        configManager.setConfiguration(OsrsFlipperSyncConfig.GROUP, key, value == null ? "" : value);
    }

    private void refreshSidePanel()
    {
        OsrsFlipperSyncPanel currentPanel = panel;
        if (currentPanel == null)
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
            offers.add(offerView(snapshot));
        }
        offers.sort((left, right) -> Integer.compare(left.slotNumber, right.slotNumber));
        currentPanel.updateOffers(offers);
        currentPanel.updateOverview(overview);
        currentPanel.updateLastTradePrices(lastTradePrices.snapshot());
        SelectedGeOpportunityResolver.Resolution focused = resolveSelectedGeOpportunity(
            focusedGeItemId,
            focusedGeSide);
        currentPanel.updateFocusedItem(focusedGeItemId, focusedGeSide, focused.opportunity);
        updateHealthPanel();
    }

    private void resetSessionStats()
    {
        sessionStats.reset();
        refreshSidePanel();
    }

    private void observePriceTestItemPresence()
    {
        if (activeAccountHash == NO_ACCOUNT)
        {
            return;
        }

        long observedAt = now();
        boolean changed = geItemPresence.observe(
            observedAt,
            occupiedGeItemIds(),
            trackedGuidanceItemIds());
        boolean refreshDue = hasDeviceToken() &&
            geItemPresence.markAuthoritativeRefreshDue(observedAt);
        if (changed || refreshDue)
        {
            persistCurrentAccount();
        }
        if (!refreshDue)
        {
            return;
        }

        // De lokale waarneming start alleen de controle. De Worker combineert
        // alle accountslots en is de enige bron die de prijstest met een
        // tombstone mag wissen. Bij netwerkuitval blijven de lokale prijzen dus
        // behouden en volgt iedere minuut een nieuwe controlepoging.
        requestOverview(true);
    }

    private Set<Integer> occupiedGeItemIds()
    {
        Set<Integer> itemIds = new HashSet<>();
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null)
        {
            return itemIds;
        }
        for (GrandExchangeOffer offer : offers)
        {
            if (offer != null && isOccupiedGeOffer(offer.getItemId(), offer.getState()))
            {
                itemIds.add(offer.getItemId());
            }
        }
        return itemIds;
    }

    private Set<Integer> trackedGuidanceItemIds()
    {
        return trackedGuidanceItemIds(
            lastTradePrices.snapshot(),
            flipCycles.openItemIds());
    }

    static Set<Integer> trackedGuidanceItemIds(
        Map<Integer, LastTradePriceView> personalPrices,
        Set<Integer> openCycleItemIds)
    {
        Set<Integer> itemIds = new HashSet<>();
        if (personalPrices != null)
        {
            for (Map.Entry<Integer, LastTradePriceView> price : personalPrices.entrySet())
            {
                LastTradePriceView value = price.getValue();
                if (value != null && (value.lastBuyPrice > 0 || value.lastSellPrice > 0))
                {
                    itemIds.add(price.getKey());
                }
            }
        }
        if (openCycleItemIds != null)
        {
            itemIds.addAll(openCycleItemIds);
        }
        return itemIds;
    }

    static boolean isOccupiedGeOffer(int itemId, GrandExchangeOfferState state)
    {
        return itemId > 0 && state != null && state != GrandExchangeOfferState.EMPTY;
    }

    private void requestOverview(boolean force)
    {
        requestOverview(force, false, false);
    }

    private void requestFreshMarketOverview()
    {
        requestOverview(true, true, false);
    }

    private void requestFreshBuyLimitOverview()
    {
        requestOverview(true, false, true);
    }

    private void requestOverview(boolean force, boolean freshMarket, boolean freshBuyLimits)
    {
        requestOverviewForScope(force, freshMarket, freshBuyLimits, 0);
    }

    private void requestFocusedOverview(int itemId)
    {
        if (itemId <= 0 || itemId != focusedGeItemId) return;
        // A targeted result supplies advice for one item, never the top list.
        if (!overview.topOpportunitiesLoaded) overviewRefreshPending = true;
        requestOverviewForScope(true, false, false, itemId);
    }

    private void rememberOverviewRequest(int focusItemId, boolean freshMarket, boolean freshBuyLimits)
    {
        if (focusItemId > 0)
        {
            if (focusItemId == focusedGeItemId) pendingFocusedOverviewItemId = focusItemId;
        }
        else
        {
            overviewRefreshPending = true;
            overviewFreshMarketPending |= freshMarket;
            overviewFreshBuyLimitsPending |= freshBuyLimits;
        }
    }

    private void requestOverviewForScope(boolean force, boolean freshMarket, boolean freshBuyLimits, int focusItemId)
    {
        if (!started || pairingInFlight || !hasDeviceToken())
        {
            return;
        }
        if (overviewInFlight)
        {
            // Repeated ticks while a full retry is already running are served
            // by that retry. Only a different scope or an explicit refresh
            // needs to remain queued after it finishes.
            if (focusItemId > 0 || overviewInFlightFocusItemId > 0 || force || freshMarket || freshBuyLimits)
            {
                rememberOverviewRequest(focusItemId, freshMarket, freshBuyLimits);
            }
            return;
        }
        // Handmatige/focusrefresh omzeilt de foutbackoff niet. Eerst GE-delta's
        // afleveren; de onafhankelijke Wiki-prijsaanvragen blijven beschikbaar.
        SyncHealthTracker.Channel channel = focusItemId > 0
            ? SyncHealthTracker.Channel.FOCUS : SyncHealthTracker.Channel.OVERVIEW;
        if (now() < syncHealth.retryAt(channel) ||
            now() < workerBackoffUntil || workerRequests.isActive() ||
            requestInFlight || snapshotInFlight || slotStateInFlight || statusInFlight ||
            heartbeatInFlight || cashInFlight ||
            (serverStateCheckPending && client.getGameState() == GameState.LOGGED_IN) ||
            hasQueuedEvents() || (snapshotPending && client.getGameState() == GameState.LOGGED_IN))
        {
            rememberOverviewRequest(focusItemId, freshMarket, freshBuyLimits);
            return;
        }
        HttpUrl base = endpoint(OVERVIEW_PATH);
        if (base == null)
        {
            return;
        }

        if (focusItemId == 0)
        {
            freshMarket |= overviewFreshMarketPending;
            freshBuyLimits |= overviewFreshBuyLimitsPending;
            overviewRefreshPending = false;
            overviewFreshMarketPending = false;
            overviewFreshBuyLimitsPending = false;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long dayStart = today.atStartOfDay(zone).toEpochSecond();
        long monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toEpochSecond();
        HttpUrl url = overviewUrl(
            base,
            dayStart,
            monthStart,
            focusItemId,
            freshMarket,
            freshBuyLimits,
            trackedGuidanceItemIds());
        Request request = authorizedRequest(url).get().build();
        long requestAccountHash = activeAccountHash;
        long requestGeneration = ++overviewRequestGeneration;
        long requestContextGeneration = overviewContextGeneration;
        long requestPriceRevision = lastTradePrices.revision();
        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.OVERVIEW, request);
        if (workerCall == null)
        {
            rememberOverviewRequest(focusItemId, freshMarket, freshBuyLimits);
            return;
        }
        overviewInFlight = true;
        overviewInFlightFocusItemId = focusItemId;
        if (focusItemId > 0 && pendingFocusedOverviewItemId == focusItemId) pendingFocusedOverviewItemId = 0;
        overviewInFlightFreshMarket = freshMarket;
        overviewInFlightFreshBuyLimits = freshBuyLimits;
        if (focusItemId == 0) overviewTicks = 0;
        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.OVERVIEW,
                    call,
                    () -> {
                    if (!isCurrentOverviewRequest(requestAccountHash, requestGeneration,
                        requestContextGeneration, activeAccountHash, overviewRequestGeneration,
                        overviewContextGeneration, overviewInFlight))
                    {
                        return;
                    }
                    if (focusItemId == 0 || focusItemId == focusedGeItemId)
                    {
                        healthFailure(channel, "netwerkfout/time-out");
                        rememberOverviewRequest(focusItemId, false, false);
                    }
                    debug("RuneLite-kansen konden niet worden opgehaald: {}", exception.getMessage());
                    finishOverviewRequest(
                        requestAccountHash,
                        requestGeneration,
                        requestContextGeneration);
                    }));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.OVERVIEW,
                    call,
                    () -> handleOverviewResponse(
                        statusCode,
                        body,
                        requestAccountHash,
                        requestGeneration,
                        requestContextGeneration,
                        requestPriceRevision,
                        focusItemId)));
            }
        });
    }

    private void handleOverviewResponse(
        int statusCode,
        String body,
        long requestAccountHash,
        long requestGeneration,
        long requestContextGeneration,
        long requestPriceRevision)
    {
        handleOverviewResponse(statusCode, body, requestAccountHash, requestGeneration,
            requestContextGeneration, requestPriceRevision, 0);
    }

    private void handleOverviewResponse(
        int statusCode, String body, long requestAccountHash, long requestGeneration,
        long requestContextGeneration, long requestPriceRevision, int requestFocusItemId)
    {
        if (!isCurrentOverviewRequest(
            requestAccountHash,
            requestGeneration,
            requestContextGeneration,
            activeAccountHash,
            overviewRequestGeneration,
            overviewContextGeneration,
            overviewInFlight))
        {
            debug("Verouderde overviewrespons voor account {} genegeerd", requestAccountHash);
            return;
        }
        SyncHealthTracker.Channel channel = requestFocusItemId > 0
            ? SyncHealthTracker.Channel.FOCUS : SyncHealthTracker.Channel.OVERVIEW;
        if (statusCode == 401 || statusCode == 403)
        {
            overviewInFlight = false;
            overviewRefreshPending = false;
            overviewFreshMarketPending = false;
            overviewFreshBuyLimitsPending = false;
            clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
            return;
        }
        if (requestFocusItemId > 0 && requestFocusItemId != focusedGeItemId)
        {
            finishOverviewRequest(requestAccountHash, requestGeneration, requestContextGeneration);
            return;
        }
        if (statusCode < 200 || statusCode >= 300)
        {
            healthFailure(channel, "HTTP " + statusCode);
            rememberOverviewRequest(requestFocusItemId, false, false);
            finishOverviewRequest(
                requestAccountHash,
                requestGeneration,
                requestContextGeneration);
            return;
        }

        try
        {
            OverviewResponse response = gson.fromJson(body, OverviewResponse.class);
            if (response == null || !response.isComplete())
            {
                throw new IllegalArgumentException("onvolledig overviewantwoord");
            }
            if (requestFocusItemId > 0 && response.opportunities.focus != null &&
                response.opportunities.focus.item_id != requestFocusItemId)
                throw new IllegalArgumentException("focusantwoord hoort bij een ander item");
            overview = response.toView(overview, requestFocusItemId);
            if (requestFocusItemId == 0 && response.topOpportunitiesAvailable() && focusedGeItemId > 0)
            {
                // A full scan may omit the selected item. Refresh its small,
                // focused view once after this scan, through the same request
                // coordinator, so old cash/buy-limit advice cannot linger.
                if (overview.opportunityForItem(focusedGeItemId) == null)
                {
                    pendingFocusedOverviewItemId = focusedGeItemId;
                }
                else
                {
                    pendingFocusedOverviewItemId = 0;
                    healthSuccess(SyncHealthTracker.Channel.FOCUS);
                }
            }
            Map<Integer, Long> advancedPriceTombstones =
                lastTradePrices.mergeAuthoritative(overview.priceTests, requestPriceRevision);
            for (Map.Entry<Integer, Long> cleared : advancedPriceTombstones.entrySet())
            {
                flipCycles.expireOpenCycles(cleared.getKey(), cleared.getValue());
            }
            Set<Integer> fullyExpiredItems = new HashSet<>(advancedPriceTombstones.keySet());
            // Een oudere tombstone mag een nieuwere lokale prijsproef/cyclus
            // niet alleen qua prijs sparen maar ook haar oorspronkelijke
            // afwezigheidsdeadline niet opnieuw laten beginnen.
            fullyExpiredItems.removeAll(trackedGuidanceItemIds());
            geItemPresence.forget(fullyExpiredItems);
            refreshOpenFlipSellGuidance();
            persistCurrentAccount();
            markWorkerSuccess();
            if (requestFocusItemId > 0 ? response.opportunitiesAvailable() : response.topOpportunitiesAvailable())
            {
                healthSuccess(channel);
            }
            else
            {
                // De Worker heeft cash, statistieken en prijstests wel veilig
                // geleverd. Houd de laatst geldige marktkansen zichtbaar en meld
                // transparant dat alleen de publieke marktlaag tijdelijk ontbreekt.
                healthFailure(
                    channel,
                    "marktdata tijdelijk niet beschikbaar; vorige kansen behouden");
                rememberOverviewRequest(requestFocusItemId, false, false);
            }
            if (panel != null)
            {
                refreshSidePanel();
            }
            // Prijzen en hoeveelheden kunnen arriveren terwijl de invoer al open is.
            refreshGeEditorSuggestions();
        }
        catch (RuntimeException exception)
        {
            healthFailure(channel, "ongeldig/onvolledig serverantwoord");
            rememberOverviewRequest(requestFocusItemId, false, false);
            debug("RuneLite-kansen konden niet worden gelezen: {}", exception.getMessage());
        }
        finally
        {
            finishOverviewRequest(
                requestAccountHash,
                requestGeneration,
                requestContextGeneration);
        }
    }

    private void finishOverviewRequest(
        long requestAccountHash,
        long requestGeneration,
        long requestContextGeneration)
    {
        if (!isCurrentOverviewRequest(
            requestAccountHash,
            requestGeneration,
            requestContextGeneration,
            activeAccountHash,
            overviewRequestGeneration,
            overviewContextGeneration,
            overviewInFlight))
        {
            return;
        }
        overviewInFlight = false;
        overviewInFlightFocusItemId = 0;
        // finishWorkerRequest pumps the next request after this callback. Keep
        // cash and GE delivery ahead of any queued full or focused refresh.
    }

    static boolean isCurrentOverviewRequest(
        long requestAccountHash,
        long requestGeneration,
        long requestContextGeneration,
        long currentAccountHash,
        long currentGeneration,
        long currentContextGeneration,
        boolean inFlight)
    {
        return inFlight &&
            requestAccountHash == currentAccountHash &&
            requestGeneration == currentGeneration &&
            requestContextGeneration == currentContextGeneration;
    }

    static HttpUrl overviewUrl(
        HttpUrl base,
        long dayStart,
        long monthStart,
        int focusItemId,
        boolean freshMarket,
        Set<Integer> trackedItemIds)
    {
        return overviewUrl(
            base,
            dayStart,
            monthStart,
            focusItemId,
            freshMarket,
            false,
            trackedItemIds);
    }

    static HttpUrl overviewUrl(
        HttpUrl base,
        long dayStart,
        long monthStart,
        int focusItemId,
        boolean freshMarket,
        boolean freshBuyLimits,
        Set<Integer> trackedItemIds)
    {
        HttpUrl.Builder url = base.newBuilder()
            .addQueryParameter("day_start", Long.toString(dayStart))
            .addQueryParameter("month_start", Long.toString(monthStart));
        if (focusItemId > 0)
        {
            url.addQueryParameter("focus_item_id", Integer.toString(focusItemId));
        }
        if (freshMarket)
        {
            // Contract met de Worker: alleen een expliciete gebruikersactie
            // mag de marktcache omzeilen. Periodieke requests sturen deze
            // parameter bewust niet mee.
            url.addQueryParameter("fresh_market", "1");
        }
        if (freshBuyLimits)
        {
            // Alleen na een bevestigde slotsync: de Worker leest dan het actuele
            // vieruursverbruik opnieuw, ook wanneer deze request een andere
            // isolate bereikt dan de voorafgaande write.
            url.addQueryParameter("fresh_buy_limits", "1");
        }
        List<Integer> tracked = new ArrayList<>();
        if (trackedItemIds != null)
        {
            for (Integer itemId : trackedItemIds)
            {
                if (itemId != null && itemId > 0)
                {
                    tracked.add(itemId);
                }
            }
        }
        Collections.sort(tracked);
        if (tracked.size() > MAX_TRACKED_OVERVIEW_ITEMS)
        {
            tracked = new ArrayList<>(tracked.subList(0, MAX_TRACKED_OVERVIEW_ITEMS));
        }
        if (!tracked.isEmpty())
        {
            List<String> values = new ArrayList<>();
            for (Integer itemId : tracked)
            {
                values.add(Integer.toString(itemId));
            }
            url.addQueryParameter("tracked_item_ids", String.join(",", values));
        }
        return url.build();
    }

    private void setAccountCash(long value)
    {
        if (value < 0 || value > Integer.MAX_VALUE)
        {
            setConnectionStatus("Cashstack moet een geheel getal tussen 0 en 2147483647 GP zijn");
            return;
        }
        if (started && activeStorageContext != null)
        {
            switchToCurrentAccount();
        }
        if (!started || !hasDeviceToken())
        {
            if (started)
            {
                setConnectionStatus("Koppel RuneLite eerst met de webapp");
            }
            return;
        }
        pendingCashUpdate = PendingCashUpdate.create(value);
        persistCurrentAccount();
        if (cashInFlight || workerRequests.isActive() || hasQueuedEvents() || snapshotPending ||
            (serverStateCheckPending && client.getGameState() == GameState.LOGGED_IN) ||
            now() < workerBackoffUntil)
        {
            setConnectionStatus("Cashstack staat in de synchronisatiewachtrij...");
            return;
        }
        sendPendingCashIfPossible();
    }

    private void sendPendingCashIfPossible()
    {
        if (!started || pendingCashUpdate == null || cashInFlight || !hasDeviceToken() ||
            workerRequests.isActive() || hasQueuedEvents() || snapshotPending ||
            (serverStateCheckPending && client.getGameState() == GameState.LOGGED_IN) ||
            now() < workerBackoffUntil)
        {
            return;
        }
        HttpUrl endpoint = endpoint(CASH_PATH);
        if (endpoint == null)
        {
            setConnectionStatus("Ongeldig webapp-adres");
            return;
        }
        PendingCashUpdate submittedUpdate = pendingCashUpdate;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cash_balance", submittedUpdate.balance);
        payload.put("request_id", submittedUpdate.requestId);
        Request request = authorizedRequest(endpoint)
            .put(RequestBody.create(JSON, gson.toJson(payload)))
            .header("Content-Type", "application/json; charset=utf-8")
            .build();
        Call workerCall = beginWorkerRequest(WorkerRequestCoordinator.Kind.CASH, request);
        if (workerCall == null)
        {
            return;
        }
        cashInFlight = true;
        cashInFlightUpdate = submittedUpdate;
        setConnectionStatus("Cashstack opslaan...");
        workerCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.CASH,
                    call,
                    () -> {
                    cashInFlight = false;
                    cashInFlightUpdate = null;
                    long retryAt = scheduleTransientRetry(++cashRetryAttempts);
                    registerWorkerBackoff(retryAt);
                    setConnectionStatus("Cashstack blijft in wachtrij; automatisch herstel actief");
                    debug("Cashstack opslaan mislukt: {}", exception.getMessage());
                    }));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishWorkerRequest(
                    WorkerRequestCoordinator.Kind.CASH,
                    call,
                    () -> {
                    cashInFlight = false;
                    cashInFlightUpdate = null;
                    if (statusCode >= 200 && statusCode < 300 && WorkerResponseValidation.cash(body))
                    {
                        cashRetryAttempts = 0;
                        clearPendingCashIfSame(submittedUpdate);
                        setConnectionStatus("Cashstack accountbreed opgeslagen");
                        requestOverview(true);
                    }
                    else if (statusCode == 401 || statusCode == 403)
                    {
                        clearStoredPairing("Koppeling ongeldig of ingetrokken; maak een nieuwe code");
                    }
                    else if (retryableWorkerHttpStatus(statusCode) || (statusCode >= 200 && statusCode < 300))
                    {
                        long retryAt = scheduleTransientRetry(++cashRetryAttempts);
                        registerWorkerBackoff(retryAt);
                        setConnectionStatus("Cashstack blijft in wachtrij; " +
                            (statusCode >= 200 && statusCode < 300 ? "ongeldig serverantwoord" : "HTTP " + statusCode));
                        debug("Cashstack kreeg tijdelijke HTTP {}; automatische retry volgt", statusCode);
                    }
                    else
                    {
                        cashRetryAttempts = 0;
                        clearPendingCashIfSame(submittedUpdate);
                        setConnectionStatus("Cashstack kreeg HTTP " + statusCode);
                        debug("Cashstackantwoord: {}", abbreviate(body, 300));
                    }
                    }));
            }
        });
    }

    private void clearPendingCashIfSame(PendingCashUpdate submittedUpdate)
    {
        if (pendingCashUpdate != null && pendingCashUpdate.hasSameIdentity(submittedUpdate))
        {
            pendingCashUpdate = null;
            persistCurrentAccount();
        }
    }

    static boolean retryableWorkerHttpStatus(int statusCode)
    {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
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
        int selectedSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
        GrandExchangeOffer selectedOffer = geOfferAtSlot(selectedSlot);
        int nextItemId = FocusedGeItemResolver.resolve(
            setupVisible,
            setupItem == null ? 0 : setupItem.getItemId(),
            setupVisible ? client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH) : 0,
            detailsVisible,
            firstItemId(detailsWidgets),
            selectedOfferItemId(selectedOffer));
        String nextSide = nextItemId <= 0
            ? ""
            : FocusedGeItemResolver.resolveSide(
                setupVisible,
                widgetTreeText(setup),
                detailsVisible,
                selectedOffer == null ? null : selectedOffer.getState());
        FlipperOfferView exactSelectedOffer = exactSelectedOffer(nextItemId, nextSide, selectedSlot);
        FocusedGeItemResolver.EditorContext nextContext = FocusedGeItemResolver.editorContext(
            setupVisible,
            detailsVisible,
            focusedGeContext,
            selectedSlot,
            focusedExistingSlot,
            exactSelectedOffer != null);
        int nextExistingSlot = nextContext == FocusedGeItemResolver.EditorContext.EXISTING_OFFER
            ? selectedSlot
            : 0;
        if (nextItemId == focusedGeItemId && Objects.equals(nextSide, focusedGeSide) &&
            nextContext == focusedGeContext && nextExistingSlot == focusedExistingSlot)
        {
            return;
        }
        boolean itemChanged = nextItemId != focusedGeItemId;
        boolean sideChanged = !Objects.equals(nextSide, focusedGeSide);
        boolean contextChanged = nextContext != focusedGeContext || nextExistingSlot != focusedExistingSlot;
        focusedGeItemId = nextItemId;
        focusedGeItemName = nextItemId > 0 ? itemName(nextItemId) : "";
        focusedGeSide = nextSide;
        focusedGeContext = nextContext;
        focusedExistingSlot = nextExistingSlot;
        if (itemChanged) overview = overview.withFocus(null);
        refreshSidePanel();
        if (itemChanged)
        {
            pendingFocusedOverviewItemId = 0;
            if (focusedGeItemId > 0) requestFocusedOverview(focusedGeItemId);
            else if (!overview.topOpportunitiesLoaded) requestOverview(true);
        }
        if (focusedGeItemId > 0 && (itemChanged || sideChanged || contextChanged))
        {
            queueMarketPrice(focusedGeItemId, true);
            flushMarketPriceQueue();
        }
    }

    private void showGePriceEditorSuggestion()
    {
        updateFocusedGeItem();
        showGePriceEditorSuggestionFromCache();
    }

    private void refreshGeEditorSuggestions()
    {
        showGePriceEditorSuggestionFromCache();
        showGeQuantityEditorSuggestion();
    }

    private void showGePriceEditorSuggestionFromCache()
    {
        Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
        Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
        if (!isVisible(prompt) || parent == null || !isVisible(setup) ||
            !"Set a price for each item:".equals(prompt.getText()))
        {
            hidePriceEditorSuggestion(parent);
            return;
        }

        Widget setupItem = client.getWidget(InterfaceID.GeOffers.SETUP_GRAPHIC4);
        int itemId = FocusedGeItemResolver.priceEditorItemId(
            setupItem == null ? 0 : setupItem.getItemId(),
            focusedGeItemId,
            client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH));
        String side = FocusedGeItemResolver.resolveSide(true, widgetTreeText(setup), false, null);
        int price = gePriceEditorPrice(itemId, side);
        if (price <= 0 || (!"buy".equals(side) && !"sell".equals(side)))
        {
            hidePriceEditorSuggestion(parent);
            return;
        }

        Widget suggestion = findPriceEditorSuggestion(parent);
        if (suggestion == null)
        {
            suggestion = parent.createChild(-1, WidgetType.TEXT);
        }
        final Widget priceSuggestion = suggestion;
        final int selectedPrice = price;
        final int selectedSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT);
        final long editorLifecycle = lifecycleGeneration;
        final long editorConnection = overviewContextGeneration;
        priceSuggestion.setHidden(false);
        String label = "buy".equals(side) ? "Koopprijs" : "Verkoopprijs";
        priceSuggestion.setText(PRICE_EDITOR_PREFIX + label + ": " +
            String.format(java.util.Locale.US, "%,d", selectedPrice) + " gp");
        priceSuggestion.setTextColor(0xFF981F);
        priceSuggestion.setFontId(FontID.VERDANA_11_BOLD);
        priceSuggestion.setTextShadowed(true);
        priceSuggestion.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        priceSuggestion.setOriginalX(10);
        priceSuggestion.setOriginalY(firstAvailableEditorY(parent, priceSuggestion));
        priceSuggestion.setOriginalHeight(20);
        priceSuggestion.setXTextAlignment(WidgetTextAlignment.LEFT);
        priceSuggestion.setYTextAlignment(WidgetTextAlignment.CENTER);
        priceSuggestion.setWidthMode(WidgetSizeMode.MINUS);
        priceSuggestion.setHasListener(true);
        priceSuggestion.setAction(1, "Gebruik " + label.toLowerCase(java.util.Locale.ROOT));
        priceSuggestion.setOnMouseRepeatListener((JavaScriptCallback) ev -> priceSuggestion.setTextColor(0xFFFFFF));
        priceSuggestion.setOnMouseLeaveListener((JavaScriptCallback) ev -> priceSuggestion.setTextColor(0xFF981F));
        priceSuggestion.setOnOpListener((JavaScriptCallback) ev ->
        {
            if (isCurrentLifecycle(editorLifecycle) && editorConnection == overviewContextGeneration)
            {
                applyCurrentGePrice(itemId, side, selectedSlot);
            }
        });
        priceSuggestion.revalidate();
    }

    private void showGeQuantityEditorSuggestion()
    {
        Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
        Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
        if (!isVisible(prompt) || parent == null || !isVisible(setup) ||
            !"How many do you wish to buy?".equals(prompt.getText()))
        {
            hideEditorSuggestion(parent, true);
            return;
        }

        int itemId = quantityEditorItemId();
        int quantity = overview.maximumQuantityForItem(itemId);
        if (quantity <= 0 || !"buy".equals(
            FocusedGeItemResolver.resolveSide(true, widgetTreeText(setup), false, null)))
        {
            hideEditorSuggestion(parent, false);
            return;
        }

        // Prijs en hoeveelheid gebruiken bewust dezelfde dynamische regel.
        // Daardoor blijft na een snelle wissel van editor nooit een oude regel
        // onder de nieuwe staan.
        Widget suggestion = findPriceEditorSuggestion(parent);
        if (suggestion == null)
        {
            suggestion = parent.createChild(-1, WidgetType.TEXT);
        }
        final Widget quantitySuggestion = suggestion;
        final int selectedQuantity = quantity;
        quantitySuggestion.setHidden(false);
        quantitySuggestion.setText(QUANTITY_EDITOR_PREFIX +
            String.format(java.util.Locale.US, "%,d", selectedQuantity));
        quantitySuggestion.setTextColor(0xFF981F);
        quantitySuggestion.setFontId(FontID.VERDANA_11_BOLD);
        quantitySuggestion.setTextShadowed(true);
        quantitySuggestion.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        quantitySuggestion.setOriginalX(10);
        quantitySuggestion.setOriginalY(firstAvailableEditorY(parent, quantitySuggestion));
        quantitySuggestion.setOriginalHeight(20);
        quantitySuggestion.setXTextAlignment(WidgetTextAlignment.LEFT);
        quantitySuggestion.setYTextAlignment(WidgetTextAlignment.CENTER);
        quantitySuggestion.setWidthMode(WidgetSizeMode.MINUS);
        quantitySuggestion.setHasListener(true);
        quantitySuggestion.setAction(1, "Gebruik aanbevolen aantal");
        quantitySuggestion.setOnMouseRepeatListener((JavaScriptCallback) ev ->
            quantitySuggestion.setTextColor(0xFFFFFF));
        quantitySuggestion.setOnMouseLeaveListener((JavaScriptCallback) ev ->
            quantitySuggestion.setTextColor(0xFF981F));
        quantitySuggestion.setOnOpListener((JavaScriptCallback) ev ->
            applyCurrentGeQuantity(itemId));
        quantitySuggestion.revalidate();
    }

    private int quantityEditorItemId()
    {
        Widget setupItem = client.getWidget(InterfaceID.GeOffers.SETUP_GRAPHIC4);
        return FocusedGeItemResolver.priceEditorItemId(
            setupItem == null ? 0 : setupItem.getItemId(),
            focusedGeItemId,
            client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH));
    }

    private void applyCurrentGeQuantity(int expectedItemId)
    {
        Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
        Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
        if (!isVisible(prompt) || !isVisible(setup) ||
            !"How many do you wish to buy?".equals(prompt.getText()) ||
            !"buy".equals(FocusedGeItemResolver.resolveSide(true, widgetTreeText(setup), false, null)) ||
            expectedItemId <= 0 || quantityEditorItemId() != expectedItemId)
        {
            hideEditorSuggestion(parent, true);
            return;
        }
        int quantity = overview.maximumQuantityForItem(expectedItemId);
        if (quantity <= 0)
        {
            hideEditorSuggestion(parent, true);
            return;
        }
        applyGeEditorValue(quantity);
    }

    private int gePriceEditorPrice(int itemId, String side)
    {
        if (itemId <= 0)
        {
            return 0;
        }
        return resolveSelectedGeOpportunity(itemId, side).price(side);
    }

    private SelectedGeOpportunityResolver.Resolution resolveSelectedGeOpportunity(
        int itemId,
        String side)
    {
        if (itemId <= 0 || (!"buy".equals(side) && !"sell".equals(side)))
        {
            return SelectedGeOpportunityResolver.Resolution.empty();
        }
        boolean focusedSelection = itemId == focusedGeItemId && Objects.equals(side, focusedGeSide);
        FocusedGeItemResolver.EditorContext context = focusedSelection
            ? focusedGeContext
            : FocusedGeItemResolver.EditorContext.NEW_SETUP;
        FlipperOfferView exact = context == FocusedGeItemResolver.EditorContext.EXISTING_OFFER
            ? exactSelectedOffer(itemId, side, focusedExistingSlot)
            : null;
        return SelectedGeOpportunityResolver.resolve(
            context,
            itemId,
            focusedSelection ? focusedGeItemName : "",
            side,
            overview.opportunityForItem(itemId),
            marketPrices.get(itemId),
            lastTradePrices.snapshot().get(itemId),
            exact,
            context == FocusedGeItemResolver.EditorContext.NEW_SETUP && "sell".equals(side)
                ? openCycleOffer(itemId, side)
                : null);
    }

    private FlipperOfferView exactSelectedOffer(int itemId, String side, int selectedSlot)
    {
        SlotSnapshot snapshot = slotSnapshots.get(selectedSlot);
        GrandExchangeOffer selectedOffer = geOfferAtSlot(selectedSlot);
        if (snapshot == null || "empty".equals(snapshot.status))
        {
            return null;
        }
        return FocusedGeItemResolver.exactSelectedOffer(
            selectedSlot,
            selectedOfferItemId(selectedOffer),
            selectedOffer == null ? null : selectedOffer.getState(),
            itemId,
            side,
            Collections.singletonList(offerView(snapshot)));
    }

    private FlipperOfferView offerView(SlotSnapshot snapshot)
    {
        MarketPriceView market = marketPrices.get(snapshot.itemId);
        RuneliteOverviewView.Opportunity liveOpportunity = overview.opportunityForItem(snapshot.itemId);
        int liveInstantBuy = positiveOrFallback(
            SellTargetPriceResolver.wikiInstantBuy(market, liveOpportunity),
            snapshot.startInstabuyPrice);
        int liveInstantSell = market != null && market.instantSellPrice > 0
            ? market.instantSellPrice
            : (liveOpportunity != null && liveOpportunity.instantSell > 0
                ? liveOpportunity.instantSell
                : snapshot.startInstasellPrice);
        return new FlipperOfferView(
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
            snapshot.suggestedBuyPrice,
            snapshot.suggestedSellPrice,
            liveInstantBuy,
            liveInstantSell,
            snapshot.lowestSellPrice);
    }

    private static Widget findPriceEditorSuggestion(Widget parent)
    {
        return findEditorSuggestion(parent, PRICE_EDITOR_PREFIX);
    }

    private static void hideEditorSuggestion(Widget parent, boolean quantityOnly)
    {
        if (parent == null)
        {
            return;
        }
        Widget suggestion = findPriceEditorSuggestion(parent);
        if (suggestion == null || (quantityOnly &&
            !suggestion.getText().startsWith(QUANTITY_EDITOR_PREFIX)))
        {
            return;
        }
        suggestion.setHidden(true);
        suggestion.clearActions();
        suggestion.setOnOpListener((Object[]) null);
        suggestion.setOnMouseRepeatListener((Object[]) null);
        suggestion.setOnMouseLeaveListener((Object[]) null);
        suggestion.setHasListener(false);
        suggestion.revalidate();
    }

    private static void hidePriceEditorSuggestion(Widget parent)
    {
        Widget suggestion = parent == null ? null : findPriceEditorSuggestion(parent);
        if (suggestion != null && !suggestion.getText().startsWith(QUANTITY_EDITOR_PREFIX))
        {
            hideEditorSuggestion(parent, false);
        }
    }

    private static Widget findEditorSuggestion(Widget parent, String prefix)
    {
        Widget[] children = parent.getDynamicChildren();
        if (children == null)
        {
            return null;
        }
        for (Widget child : children)
        {
            if (child != null && child.getText() != null && child.getText().startsWith(prefix))
            {
                return child;
            }
        }
        return null;
    }

    private static int firstAvailableEditorY(Widget parent, Widget ownSuggestion)
    {
        // Y=4 plaatst onze hulpregel duidelijk boven de spelprompt. De ruimere
        // lijst voorkomt de vroegere onveilige terugval naar een reeds bezette
        // y=36 wanneer een andere plugin ook chatboxhulp toont.
        int[] positions = {4, 20, 36, 52, 68, 84, 100};
        Widget[] children = parent.getDynamicChildren();
        if (children == null)
        {
            return positions[0];
        }
        for (int position : positions)
        {
            boolean occupied = false;
            for (Widget child : children)
            {
                if (child == null || child == ownSuggestion || child.getText() == null || child.getText().isEmpty())
                {
                    continue;
                }
                int childTop = child.getOriginalY();
                int childBottom = childTop + Math.max(14, child.getOriginalHeight());
                int candidateBottom = position + 14;
                if (position < childBottom && candidateBottom > childTop)
                {
                    occupied = true;
                    break;
                }
            }
            if (!occupied)
            {
                return position;
            }
        }
        return positions[positions.length - 1] + 16;
    }

    private void applyCurrentGePrice(int expectedItemId, String expectedSide, int expectedSlot)
    {
        Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
        Widget parent = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
        String side = FocusedGeItemResolver.resolveSide(true, widgetTreeText(setup), false, null);
        if (!isVisible(prompt) || !isVisible(setup) ||
            !"Set a price for each item:".equals(prompt.getText()) ||
            !expectedSide.equals(side) || expectedItemId <= 0 ||
            quantityEditorItemId() != expectedItemId ||
            client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) != expectedSlot)
        {
            hidePriceEditorSuggestion(parent);
            return;
        }
        // Clicking resolves the current local advice; it never triggers a
        // focus refresh or an HTTP request.
        int price = gePriceEditorPrice(expectedItemId, side);
        if (price <= 0)
        {
            hidePriceEditorSuggestion(parent);
            return;
        }
        applyGeEditorValue(price);
    }

    private void applyGeEditorValue(int value)
    {
        Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
        if (input != null)
        {
            input.setText(value + "*");
        }
        client.setVarcStrValue(VarClientStr.INPUT_TEXT, Integer.toString(value));
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

    private GrandExchangeOffer geOfferAtSlot(int selectedSlot)
    {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        int offerIndex = FocusedGeItemResolver.selectedOfferIndex(
            selectedSlot,
            offers == null ? 0 : offers.length);
        if (offerIndex < 0)
        {
            return null;
        }
        GrandExchangeOffer offer = offers[offerIndex];
        return offer == null || offer.getState() == GrandExchangeOfferState.EMPTY ? null : offer;
    }

    private static int selectedOfferItemId(GrandExchangeOffer offer)
    {
        return offer == null
            ? 0
            : Math.max(0, offer.getItemId());
    }

    private static String widgetTreeText(Widget widget)
    {
        StringBuilder text = new StringBuilder();
        appendWidgetTreeText(widget, text, 0);
        return text.toString();
    }

    private static void appendWidgetTreeText(Widget widget, StringBuilder text, int depth)
    {
        if (widget == null || depth > 8)
        {
            return;
        }
        if (!isBlank(widget.getText()))
        {
            text.append(' ').append(widget.getText());
        }
        Widget[] children = widget.getChildren();
        if (children == null)
        {
            return;
        }
        for (Widget child : children)
        {
            appendWidgetTreeText(child, text, depth + 1);
        }
    }

    private int initialSuggestedSellPriceFor(int itemId)
    {
        return SellTargetPriceResolver.initial(
            marketPrices.get(itemId),
            overview.opportunityForItem(itemId),
            lastTradePrices.snapshot().get(itemId));
    }

    private int liveWikiSellRaiseCandidateFor(int itemId)
    {
        return SellTargetPriceResolver.liveWikiRaiseCandidate(
            marketPrices.get(itemId),
            overview.opportunityForItem(itemId));
    }

    private void captureStartMarketSnapshot(SlotSnapshot snapshot)
    {
        if (snapshot == null || snapshot.itemId <= 0)
        {
            return;
        }
        MarketPriceView market = marketPrices.get(snapshot.itemId);
        RuneliteOverviewView.Opportunity opportunity = overview.opportunityForItem(snapshot.itemId);
        snapshot.startInstabuyPrice = SellTargetPriceResolver.wikiInstantBuy(
            market,
            opportunity);
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
        if (!started)
        {
            return;
        }
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot != null && !"empty".equals(snapshot.status) && snapshot.itemId > 0)
            {
                queueMarketPrice(snapshot.itemId, force || snapshot.suggestedSellPricePending);
            }
        }
        if (focusedGeItemId > 0)
        {
            queueMarketPrice(focusedGeItemId, force);
        }
        boolean cycleGuidanceChanged = false;
        for (Integer itemId : flipCycles.openItemIds())
        {
            if (itemId == null)
            {
                continue;
            }
            cycleGuidanceChanged |= refreshOpenFlipSellGuidance(itemId);
            queueMarketPrice(itemId, force || flipCycles.needsSellTarget(itemId));
        }
        if (cycleGuidanceChanged)
        {
            persistCurrentAccount();
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

        Request request = wikiMarketPriceRequest(itemId);
        if (request == null)
        {
            return;
        }

        final Call priceCall;
        final long generation;
        final long priceGeneration = marketPriceGeneration;
        synchronized (lifecycleLock)
        {
            if (!started)
            {
                return;
            }
            generation = lifecycleGeneration;
            priceCall = httpClient.newCall(request);
            marketPriceCall = priceCall;
            marketPriceInFlight = true;
        }
        priceCall.enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException exception)
            {
                clientThread.invokeLater(() -> finishMarketPriceRequest(
                    generation, priceGeneration, call, () ->
                {
                    debug("Actuele Wiki-prijs voor item {} kon niet worden opgehaald: {}", itemId, exception.getMessage());
                }));
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                String body = readResponseBody(response);
                int statusCode = response.code();
                response.close();
                clientThread.invokeLater(() -> finishMarketPriceRequest(
                    generation, priceGeneration, call,
                    () -> handleMarketPriceResponse(itemId, statusCode, body)));
            }
        });
    }

    private void invalidateMarketPriceContext()
    {
        marketPriceGeneration++;
        Call oldCall = marketPriceCall;
        marketPriceCall = null;
        marketPriceInFlight = false;
        if (oldCall != null)
        {
            oldCall.cancel();
        }
    }

    private void finishMarketPriceRequest(
        long generation, long priceGeneration, Call call, Runnable responseHandler)
    {
        if (!isCurrentLifecycle(generation) || priceGeneration != marketPriceGeneration ||
            marketPriceCall != call)
        {
            return;
        }
        marketPriceCall = null;
        marketPriceInFlight = false;
        try
        {
            responseHandler.run();
        }
        finally
        {
            flushMarketPriceQueue();
        }
    }

    static Request wikiMarketPriceRequest(int itemId)
    {
        HttpUrl base = HttpUrl.parse(WIKI_LATEST_URL);
        if (itemId <= 0 || base == null)
        {
            return null;
        }
        HttpUrl url = base.newBuilder().addQueryParameter("id", Integer.toString(itemId)).build();
        // De gedeelde RuneLite-client mag de vorige response bewaren; live prijzen
        // moeten bij iedere geplande fetch opnieuw worden gevalideerd.
        return new Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", WIKI_USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build();
    }

    private void handleMarketPriceResponse(int itemId, int statusCode, String body)
    {
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
                    if (refreshOpenFlipSellGuidance(itemId))
                    {
                        persistCurrentAccount();
                    }
                    refreshSidePanel();
                    refreshGeEditorSuggestions();
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
            boolean syncChanged = false;
            if (snapshot.startInstabuyPrice <= 0 && market.instantBuyPrice > 0)
            {
                snapshot.startInstabuyPrice = market.instantBuyPrice;
                syncChanged = true;
            }
            if (snapshot.startInstasellPrice <= 0 && market.instantSellPrice > 0)
            {
                snapshot.startInstasellPrice = market.instantSellPrice;
                syncChanged = true;
            }
            if ("buy".equals(snapshot.side) &&
                snapshot.suggestedSellPricePending && capturedPrice > 0)
            {
                snapshot.suggestedSellPricePending = false;
                changed = true;
            }
            if (syncChanged)
            {
                snapshot.eventSequence = Math.max(1, snapshot.eventSequence + 1);
                snapshot.lastEventAt = nextLogicalTime(snapshot.lastEventAt);
                snapshot.fingerprint = fingerprint(snapshot);
                enqueue(snapshot.toSyncEvent("guidance_updated"));
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
        return !credentialTokenFor(endpoint(STATUS_PATH)).isEmpty();
    }

    private String credentialTokenFor(HttpUrl endpoint)
    {
        PairingCredentials credentials = pairingCredentials;
        return credentials != null && credentials.matches(configProfileKey(), endpoint,
            config.ownerEmail(), config.deviceId()) ? credentials.token : "";
    }

    private PairingCredentialStore credentialStore()
    {
        return new PairingCredentialStore(journalRoot().resolve("credentials"));
    }

    private void initializeCredentials()
    {
        String profile = configProfileKey();
        if (Objects.equals(credentialProfileKey, profile)) return;
        pairingCredentials = null;
        credentialProfileKey = profile;
        try { pairingCredentials = credentialStore().read(profile); }
        catch (IOException exception) { LOG.warn("Lokale tokenopslag onleesbaar; koppel opnieuw"); }
        // Old tokens have no recorded pairing origin. Binding one to the current
        // setting would leak it if that address was edited while RuneLite was off.
        legacyPairingRemoved = configManager != null &&
            !isBlank(configManager.getConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken"));
        if (legacyPairingRemoved)
        {
            updatingPairing = true;
            try { configManager.unsetConfiguration(OsrsFlipperSyncConfig.GROUP, "deviceToken"); }
            finally { updatingPairing = false; }
            if (pairingCredentials == null)
                setConnectionStatus("Koppel eenmaal opnieuw om je token veilig aan dit webapp-adres te binden");
        }
    }

    private String deviceName()
    {
        return "OSRS Flipper RuneLite Sync";
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

    GeSlotTimerView geSlotTimerView(int zeroBasedSlot, GrandExchangeOffer liveOffer)
    {
        if (zeroBasedSlot < 0 || zeroBasedSlot >= SLOT_COUNT || liveOffer == null ||
            liveOffer.getState() == null || liveOffer.getState() == GrandExchangeOfferState.EMPTY)
        {
            return null;
        }

        SlotSnapshot snapshot = slotSnapshots.get(zeroBasedSlot + 1);
        String liveSide = sideFor(liveOffer.getState());
        if (!isSameOffer(
                snapshot,
                liveOffer.getItemId(),
                liveSide,
                liveOffer.getPrice(),
                liveOffer.getTotalQuantity()) ||
            isTerminal(snapshot.status) != isTerminalGeState(liveOffer.getState()))
        {
            return null;
        }

        long displayedTimerStartedAt = isTerminal(snapshot.status)
            ? snapshot.startedAt
            : activeTimerStartedAt(snapshot);
        return GeSlotTimerView.create(
            snapshot.side,
            displayedTimerStartedAt,
            snapshot.endedAt);
    }

    private static boolean isTerminalGeState(GrandExchangeOfferState state)
    {
        return state == GrandExchangeOfferState.BOUGHT ||
            state == GrandExchangeOfferState.SOLD ||
            state == GrandExchangeOfferState.CANCELLED_BUY ||
            state == GrandExchangeOfferState.CANCELLED_SELL;
    }

    private static long activeTimerStartedAt(SlotSnapshot snapshot)
    {
        if (snapshot.timerStartedAt > 0)
        {
            return snapshot.timerStartedAt;
        }
        if (snapshot.lastFillAt > 0)
        {
            return snapshot.lastFillAt;
        }
        return snapshot.startedAt;
    }

    private static boolean sameOfferShape(
        SlotSnapshot previous,
        int itemId,
        String side,
        int totalQuantity)
    {
        return previous != null &&
            !"empty".equals(previous.status) &&
            !isBlank(previous.offerId) &&
            previous.itemId == itemId &&
            Objects.equals(previous.side, side) &&
            previous.totalQuantity == totalQuantity;
    }

    private static boolean continuesCancelledReprice(
        SlotSnapshot previous,
        int itemId,
        String side,
        int price,
        int totalQuantity)
    {
        boolean sameItemAndSide = previous != null &&
            !"empty".equals(previous.status) &&
            !isBlank(previous.offerId) &&
            previous.itemId == itemId &&
            Objects.equals(previous.side, side);
        return OfferGuidanceResolver.continuesCancelledReprice(
            sameItemAndSide,
            previous == null ? "" : previous.status,
            previous == null ? 0 : previous.price,
            price,
            previous == null ? 0 : previous.totalQuantity,
            previous == null ? 0 : previous.filledQuantity,
            totalQuantity);
    }

    private static OfferGuidanceResolver.Guidance guidance(SlotSnapshot snapshot)
    {
        if (snapshot == null)
        {
            return OfferGuidanceResolver.Guidance.empty();
        }
        return new OfferGuidanceResolver.Guidance(
            positiveOrFallback(snapshot.suggestedBuyPrice, snapshot.price),
            snapshot.suggestedSellPrice,
            snapshot.sourceBuyOfferId,
            snapshot.lowestSellPrice);
    }

    private List<OfferGuidanceResolver.BuyCandidate> buyGuidanceCandidates()
    {
        List<OfferGuidanceResolver.BuyCandidate> candidates = new ArrayList<>();
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot == null || !"buy".equals(snapshot.side) ||
                "empty".equals(snapshot.status))
            {
                continue;
            }
            FlipCyclePlanBook.Cycle cycle = cycleForSnapshot(snapshot);
            if (cycle != null)
            {
                // A known cycle owns acquisition timing and reservations.
                // Only recover its missing legacy floor; never reuse the raw
                // slot fill quantity to override a rejected cycle selection.
                if (cycle.lowestSellPrice <= 0 && !cycle.isClosed())
                {
                    candidates.add(OfferGuidanceResolver.frozenBuyCandidate(
                        cycle.slotNumber, cycle.itemId, cycle.availableQuantity(),
                        cycle.startedAt, cycle.lastAcquiredAt, cycle.cycleId,
                        cycle.frozenBuyPrice, cycle.sellTargetPrice, 0));
                }
                continue;
            }
            int frozenBuyPrice = positiveOrFallback(snapshot.suggestedBuyPrice, snapshot.price);
            candidates.add(OfferGuidanceResolver.frozenBuyCandidate(
                snapshot.slotNumber,
                snapshot.itemId,
                snapshot.filledQuantity,
                snapshot.startedAt,
                snapshot.lastEventAt,
                isBlank(snapshot.sourceBuyOfferId) ? snapshot.offerId : snapshot.sourceBuyOfferId,
                frozenBuyPrice,
                snapshot.suggestedSellPrice,
                snapshot.lowestSellPrice));
        }
        return candidates;
    }

    private void releaseReplacedCycleState(SlotSnapshot previous, long eventAt)
    {
        if (previous == null || "empty".equals(previous.status) || isTerminal(previous.status))
        {
            return;
        }
        if ("sell".equals(previous.side) && !isBlank(previous.sourceBuyOfferId))
        {
            flipCycles.releaseSell(
                previous.sourceBuyOfferId,
                previous.offerId,
                previous.filledQuantity,
                eventAt);
            return;
        }
        if ("buy".equals(previous.side))
        {
            SlotSnapshot cancelled = previous.copy();
            cancelled.status = "cancelled";
            cancelled.lastEventAt = Math.max(previous.lastEventAt, eventAt);
            recordFlipCycle(cancelled);
        }
    }

    private static OfferGuidanceResolver.BuyCandidate buyCandidate(FlipCyclePlanBook.Cycle cycle)
    {
        if (cycle == null)
        {
            return null;
        }
        return new OfferGuidanceResolver.BuyCandidate(
            cycle.slotNumber,
            cycle.itemId,
            cycle.availableQuantity(),
            cycle.startedAt,
            cycle.lastEventAt,
            cycle.cycleId,
            cycle.frozenBuyPrice,
            cycle.sellTargetPrice,
            cycle.lowestSellPrice);
    }

    private void recordFlipCycle(SlotSnapshot snapshot)
    {
        if (snapshot == null || snapshot.itemId <= 0 || isBlank(snapshot.offerId))
        {
            return;
        }
        if ("buy".equals(snapshot.side))
        {
            if (isBlank(snapshot.sourceBuyOfferId))
            {
                snapshot.sourceBuyOfferId = snapshot.offerId;
                snapshot.fingerprint = fingerprint(snapshot);
            }
            flipCycles.recordBuy(
                snapshot.sourceBuyOfferId,
                snapshot.offerId,
                snapshot.slotNumber,
                snapshot.itemId,
                snapshot.itemName,
                positiveOrFallback(snapshot.suggestedBuyPrice, snapshot.price),
                snapshot.suggestedSellPrice,
                snapshot.lowestSellPrice,
                snapshot.totalQuantity,
                snapshot.filledQuantity,
                snapshot.status,
                snapshot.startedAt,
                snapshot.lastEventAt);
            return;
        }
        if ("sell".equals(snapshot.side) && !isBlank(snapshot.sourceBuyOfferId))
        {
            flipCycles.recordSell(
                snapshot.sourceBuyOfferId,
                snapshot.offerId,
                snapshot.slotNumber,
                snapshot.itemId,
                snapshot.itemName,
                snapshot.suggestedBuyPrice,
                snapshot.suggestedSellPrice,
                snapshot.lowestSellPrice,
                snapshot.totalQuantity,
                snapshot.filledQuantity,
                snapshot.status,
                snapshot.startedAt,
                snapshot.lastEventAt);
        }
    }

    /**
     * Applies live sell guidance through one path for both the persistent flip
     * cycle and every visible slot that belongs to it. A running cycle may only
     * receive the Wiki instabuy-minus-one candidate; Last buy is intentionally
     * limited to initialSuggestedSellPriceFor().
     */
    private boolean refreshOpenFlipSellGuidance(int itemId)
    {
        int wikiCandidate = liveWikiSellRaiseCandidateFor(itemId);
        boolean changed = flipCycles.raiseSellTarget(itemId, wikiCandidate);

        // Phase 1: merge every linked snapshot target into its own cycle. This
        // must finish before any snapshot is written back; HashMap iteration
        // order may otherwise leave an earlier slot below a later slot's target.
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot == null || snapshot.itemId != itemId ||
                "empty".equals(snapshot.status))
            {
                continue;
            }
            FlipCyclePlanBook.Cycle cycle = openCycleForSnapshot(snapshot);
            if (cycle == null)
            {
                continue;
            }

            // A higher account-synchronised snapshot target belongs only to
            // its linked cycle, not to every parallel cycle of the same item.
            changed |= flipCycles.raiseSellTarget(
                cycle.cycleId,
                snapshot.suggestedSellPrice);
        }

        // Phase 2: copy each cycle's final maximum to all of its visible slots.
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot == null || snapshot.itemId != itemId ||
                "empty".equals(snapshot.status))
            {
                continue;
            }
            FlipCyclePlanBook.Cycle cycle = openCycleForSnapshot(snapshot);
            if (cycle == null || cycle.isClosed() ||
                cycle.sellTargetPrice <= snapshot.suggestedSellPrice)
            {
                continue;
            }

            int previousTarget = snapshot.suggestedSellPrice;
            snapshot.suggestedSellPrice = cycle.sellTargetPrice;
            if (wikiCandidate > previousTarget)
            {
                snapshot.suggestedSellPriceCapturedAt = now();
            }
            snapshot.eventSequence = Math.max(1, snapshot.eventSequence + 1);
            snapshot.lastEventAt = nextLogicalTime(snapshot.lastEventAt);
            snapshot.fingerprint = fingerprint(snapshot);
            enqueue(snapshot.toSyncEvent("guidance_updated"));
            changed = true;
        }
        return changed;
    }

    private boolean refreshOpenFlipSellGuidance()
    {
        boolean changed = false;
        for (Integer itemId : flipCycles.openItemIds())
        {
            if (itemId != null)
            {
                changed |= refreshOpenFlipSellGuidance(itemId);
            }
        }
        return changed;
    }

    private FlipCyclePlanBook.Cycle openCycleForSnapshot(SlotSnapshot snapshot)
    {
        FlipCyclePlanBook.Cycle cycle = cycleForSnapshot(snapshot);
        return cycle == null || cycle.isClosed() ? null : cycle;
    }

    private FlipCyclePlanBook.Cycle cycleForSnapshot(SlotSnapshot snapshot)
    {
        if (snapshot == null || snapshot.itemId <= 0 ||
            "empty".equals(snapshot.status) ||
            !("buy".equals(snapshot.side) || "sell".equals(snapshot.side)))
        {
            return null;
        }
        String cycleId = !isBlank(snapshot.sourceBuyOfferId)
            ? snapshot.sourceBuyOfferId
            : ("buy".equals(snapshot.side) ? snapshot.offerId : "");
        FlipCyclePlanBook.Cycle cycle = flipCycles.cycle(cycleId);
        return cycle == null || cycle.itemId != snapshot.itemId
            ? null
            : cycle;
    }

    private void recoverFlipCyclesFromSlots()
    {
        List<SlotSnapshot> snapshots = new ArrayList<>(slotSnapshots.values());
        snapshots.sort((left, right) ->
        {
            int byStart = Long.compare(left == null ? 0 : left.startedAt, right == null ? 0 : right.startedAt);
            if (byStart != 0)
            {
                return byStart;
            }
            boolean leftBuy = left != null && "buy".equals(left.side);
            boolean rightBuy = right != null && "buy".equals(right.side);
            return leftBuy == rightBuy ? 0 : (leftBuy ? -1 : 1);
        });
        for (SlotSnapshot snapshot : snapshots)
        {
            recordFlipCycle(snapshot);
        }
    }

    private void repairUnlinkedSellCycles()
    {
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot == null || "empty".equals(snapshot.status) ||
                !"sell".equals(snapshot.side) || !isBlank(snapshot.sourceBuyOfferId) ||
                !tryLinkSellToOpenCycle(snapshot))
            {
                continue;
            }
            snapshot.eventSequence = Math.max(1, snapshot.eventSequence + 1);
            snapshot.lastEventAt = nextLogicalTime(snapshot.lastEventAt);
            snapshot.fingerprint = fingerprint(snapshot);
            recordFlipCycle(snapshot);
            enqueue(snapshot.toSyncEvent("guidance_updated"));
        }
    }

    private boolean tryLinkSellToOpenCycle(SlotSnapshot snapshot)
    {
        if (snapshot == null || !"sell".equals(snapshot.side) ||
            !isBlank(snapshot.sourceBuyOfferId) || snapshot.itemId <= 0 ||
            snapshot.totalQuantity <= 0)
        {
            return false;
        }
        FlipCyclePlanBook.Cycle sourceCycle = flipCycles.selectForSell(
            snapshot.itemId,
            snapshot.totalQuantity,
            snapshot.startedAt);
        OfferGuidanceResolver.BuyCandidate source = buyCandidate(sourceCycle);
        if (source == null)
        {
            source = OfferGuidanceResolver.selectBuyForSell(
                snapshot.slotNumber,
                snapshot.itemId,
                snapshot.totalQuantity,
                snapshot.startedAt,
                buyGuidanceCandidates(),
                linkedBuyOfferIds());
        }
        if (source == null)
        {
            return false;
        }
        OfferGuidanceResolver.Guidance linked = OfferGuidanceResolver.linkedSell(
            snapshot.price,
            liveWikiSellRaiseCandidateFor(snapshot.itemId),
            snapshot.startInstasellPrice,
            source);
        snapshot.suggestedBuyPrice = linked.buyPrice;
        snapshot.suggestedSellPrice = SellTargetPriceResolver.raiseOnly(
            snapshot.suggestedSellPrice,
            linked.sellPrice);
        snapshot.sourceBuyOfferId = linked.sourceBuyOfferId;
        snapshot.lowestSellPrice = linked.lowestSellPrice;
        return true;
    }

    private FlipperOfferView openCycleOffer(int itemId, String side)
    {
        FlipCyclePlanBook.Cycle cycle = "buy".equals(side)
            ? flipCycles.selectOpenBuy(itemId)
            : ("sell".equals(side) ? flipCycles.selectForSetup(itemId) : null);
        if (cycle == null)
        {
            return null;
        }
        int quantity = "buy".equals(side)
            ? cycle.displayedBuyQuantity()
            : cycle.availableQuantity();
        if (quantity <= 0)
        {
            return null;
        }
        MarketPriceView market = marketPrices.get(itemId);
        RuneliteOverviewView.Opportunity liveOpportunity = overview.opportunityForItem(itemId);
        int liveInstantBuy = SellTargetPriceResolver.wikiInstantBuy(
            market,
            liveOpportunity);
        int liveInstantSell = market != null && market.instantSellPrice > 0
            ? market.instantSellPrice
            : (liveOpportunity == null ? 0 : liveOpportunity.instantSell);
        return new FlipperOfferView(
            cycle.slotNumber,
            cycle.itemId,
            cycle.itemName,
            side,
            "buy".equals(side) ? cycle.frozenBuyPrice : cycle.sellTargetPrice,
            quantity,
            0,
            "cycle_pending_sell",
            cycle.startedAt,
            0,
            cycle.frozenBuyPrice,
            cycle.sellTargetPrice,
            liveInstantBuy,
            liveInstantSell,
            cycle.lowestSellPrice);
    }

    private Set<String> linkedBuyOfferIds()
    {
        Set<String> linked = new HashSet<>();
        for (SlotSnapshot snapshot : slotSnapshots.values())
        {
            if (snapshot != null && "sell".equals(snapshot.side) &&
                !isBlank(snapshot.sourceBuyOfferId))
            {
                linked.add(snapshot.sourceBuyOfferId);
            }
        }
        return linked;
    }

    private static int positiveOrFallback(int preferred, int fallback)
    {
        return preferred > 0 ? preferred : Math.max(0, fallback);
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
            state != GrandExchangeOfferState.EMPTY)
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

    static boolean shouldRecordPriceTransition(
        boolean reconciliation,
        boolean sameOffer,
        int previousFilled,
        int nextFilled)
    {
        return !reconciliation ||
            (sameOffer && nextFilled > Math.max(0, previousFilled));
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
            state.offerId + "|" + state.startedAt + "|" + state.endedAt + "|" +
            state.startInstabuyPrice + "|" + state.startInstasellPrice + "|" +
            state.suggestedBuyPrice + "|" + state.suggestedSellPrice + "|" +
            state.lowestSellPrice + "|" +
            state.sourceBuyOfferId;
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
        if (!response.isSuccessful())
        {
            LOG.warn("HTTP {} op {}; trace={}", response.code(),
                response.request().url().encodedPath(),
                safeTraceId(response.header("X-Runelite-Trace-Id")));
        }
        try
        {
            ResponseBody body = response.body();
            return body == null ? "" : body.string();
        }
        catch (IOException exception)
        {
            LOG.warn("HTTP-antwoord op {} kon niet volledig worden gelezen", response.request().url().encodedPath());
            return "";
        }
    }

    private static String displayOwner(String value)
    {
        String owner = trim(value);
        return owner.isEmpty() ? "jouw webappaccount" : owner;
    }

    static String safeTraceId(String value)
    {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,80}") ? value : "niet beschikbaar";
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

    private static final class AccountState
    {
        SlotSnapshot[] slots;
        long snapshotSequence;
        PendingSnapshot pendingSnapshot;
        LastTradePriceBook.Entry[] lastTradePrices;
        GeItemPresenceBook.Entry[] itemPresence;
        FlipCyclePlanBook.Cycle[] cycles;
        PendingCashUpdate pendingCash;
        SyncEvent[] unjournaled;
    }

    private static final class PairResponse
    {
        Boolean success;
        String device_id;
        String device_token;
        String owner_email;
        long linked_at;
    }

    private static final class WorkerRequestContext
    {
        final String profile;
        WorkerRequestContext(String profile) { this.profile = profile; }
    }

    private static final class ApiError
    {
        String error;
    }

    private static final class OverviewResponse
    {
        boolean success;
        long generated_at;
        long market_generated_at;
        OpportunityLists opportunities;
        OverviewStats stats;
        List<PriceTestData> price_tests;
        CashData cash;
        OverviewAvailability availability;
        MarketRefresh market_refresh;

        boolean isComplete()
        {
            return success && generated_at > 0 && opportunities != null &&
                opportunities.hourly != null && validOpportunityRows(opportunities.hourly) &&
                validOpportunityRows(opportunities.expected) &&
                (opportunities.focus == null || opportunities.focus.item_id > 0) &&
                stats != null && stats.today != null &&
                stats.month != null && stats.total != null && stats.today.isComplete() &&
                stats.month.isComplete() && stats.total.isComplete() && cash != null &&
                cash.isComplete() && price_tests != null;
        }

        private static boolean validOpportunityRows(List<OpportunityData> rows)
        {
            if (rows == null) return true;
            for (OpportunityData row : rows)
            {
                if (row == null || row.item_id <= 0) return false;
            }
            return true;
        }

        boolean opportunitiesAvailable()
        {
            // Oudere Worker-versies kenden availability nog niet en leverden bij
            // succes altijd volledige kansen. Dat antwoord blijft compatibel.
            return availability == null || availability.opportunities;
        }

        boolean marketStale()
        {
            return (availability != null && availability.degraded) ||
                (market_refresh != null && (market_refresh.stale || market_refresh.degraded)) ||
                (market_generated_at > 0 && generated_at - market_generated_at > 15 * 60);
        }

        boolean topOpportunitiesAvailable()
        {
            // A degraded scanner can successfully return [] after discarding all
            // expired prices. That is not evidence that no profitable flips exist.
            return opportunitiesAvailable() && !(marketStale() &&
                opportunityViews(opportunities == null ? null : opportunities.hourly).isEmpty());
        }

        RuneliteOverviewView toView()
        {
            return toView(null);
        }

        RuneliteOverviewView toView(RuneliteOverviewView previous)
        {
            return toView(previous, 0);
        }

        RuneliteOverviewView toView(RuneliteOverviewView previous, int requestFocusItemId)
        {
            RuneliteOverviewView prior = previous == null ? RuneliteOverviewView.empty() : previous;
            boolean focused = requestFocusItemId > 0;
            boolean available = topOpportunitiesAvailable();
            boolean replaceTop = !focused && available;
            // The request scope is captured before HTTP starts. A focused scan is
            // only advice for that item, even when its hourly list happens to be empty.
            List<RuneliteOverviewView.Opportunity> expected = replaceTop
                ? opportunityViews(opportunities == null ? null : opportunities.expected) : prior.expected;
            List<RuneliteOverviewView.Opportunity> hourly = replaceTop
                ? opportunityViews(opportunities == null ? null : opportunities.hourly) : prior.hourly;
            RuneliteOverviewView.Opportunity focus = replaceTop ? null : prior.focus;
            if (opportunitiesAvailable() && opportunities != null && opportunities.focus != null)
            {
                focus = opportunities.focus.toView();
            }
            else if (focused && opportunitiesAvailable())
            {
                focus = null;
            }
            return new RuneliteOverviewView(
                expected,
                hourly,
                focus,
                periodView(stats == null ? null : stats.today),
                periodView(stats == null ? null : stats.month),
                periodView(stats == null ? null : stats.total),
                priceTestViews(price_tests),
                cash == null
                    ? RuneliteOverviewView.CashBalance.empty()
                    : cash.toView(),
                replaceTop ? (market_generated_at > 0 ? market_generated_at : generated_at) : prior.generatedAt,
                replaceTop || prior.topOpportunitiesLoaded,
                focused ? prior.marketAvailable : available,
                focused ? prior.marketStale : !available || marketStale());
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
            if (row != null && Boolean.FALSE.equals(row.attribution_complete))
            {
                // Een expliciet onbetrouwbare periode is geen kapotte overview.
                // Negeer eventuele gedeeltelijke cijfers zonder de andere data te blokkeren.
                return new RuneliteOverviewView.PeriodStats(
                    0, 0, 0, 0, 0, 0, Collections.emptyList(), false, row.attribution_error);
            }
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
        Integer maximum_quantity;
        int official_buy_limit;
        int used_buy_limit;
        int remaining_buy_limit;
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
                maximum_quantity == null ? -1 : maximum_quantity,
                maximum_profit_per_hour,
                maximum_cycle_profit,
                price_updated_at,
                official_buy_limit,
                used_buy_limit,
                remaining_buy_limit);
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
        Long realized_profit;
        Double roi_percent;
        Long profit_per_hour;
        Long ge_tax;
        Long trading_volume;
        Integer completed_flips;
        List<PeriodItemData> items;
        Boolean attribution_complete;
        String attribution_error;

        boolean isComplete()
        {
            return Boolean.FALSE.equals(attribution_complete) ||
                realized_profit != null && roi_percent != null && Double.isFinite(roi_percent) &&
                profit_per_hour != null && ge_tax != null && trading_volume != null &&
                completed_flips != null && items != null;
        }
    }

    private static final class OverviewAvailability
    {
        boolean personal_data;
        boolean market_data;
        boolean opportunities;
        boolean degraded;
        String error_code;
    }

    private static final class MarketRefresh
    {
        boolean stale;
        boolean degraded;
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
        long cleared_at;

        LastTradePriceView toView()
        {
            return new LastTradePriceView(
                item_id,
                last_buy_price,
                last_sell_price,
                last_buy_at,
                last_sell_at,
                cleared_at);
        }
    }

    private static final class CashData
    {
        Long available;
        Long reserved;
        Long available_plus_reserved;
        Long updated_at;

        boolean isComplete()
        {
            return available != null && reserved != null && available_plus_reserved != null && updated_at != null;
        }

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
        long timerStartedAt;
        long lastFillAt;
        int timerFillHighWaterMark;
        long eventSequence;
        long lastEventAt;
        long serverVersion;
        String fingerprint;
        int suggestedBuyPrice;
        int suggestedSellPrice;
        int lowestSellPrice;
        boolean suggestedSellPricePending;
        long suggestedSellPriceCapturedAt;
        int startInstabuyPrice;
        int startInstasellPrice;
        String sourceBuyOfferId;

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
            copy.timerStartedAt = timerStartedAt;
            copy.lastFillAt = lastFillAt;
            copy.timerFillHighWaterMark = timerFillHighWaterMark;
            copy.eventSequence = eventSequence;
            copy.lastEventAt = lastEventAt;
            copy.serverVersion = serverVersion;
            copy.fingerprint = fingerprint;
            copy.suggestedBuyPrice = suggestedBuyPrice;
            copy.suggestedSellPrice = suggestedSellPrice;
            copy.lowestSellPrice = lowestSellPrice;
            copy.suggestedSellPricePending = suggestedSellPricePending;
            copy.suggestedSellPriceCapturedAt = suggestedSellPriceCapturedAt;
            copy.startInstabuyPrice = startInstabuyPrice;
            copy.startInstasellPrice = startInstasellPrice;
            copy.sourceBuyOfferId = sourceBuyOfferId;
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
            event.suggestedBuyPrice = suggestedBuyPrice;
            event.suggestedSellPrice = suggestedSellPrice;
            event.lowestSellPrice = lowestSellPrice;
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
            result.put("suggested_buy_price", Math.max(0, suggestedBuyPrice));
            result.put("suggested_sell_price", Math.max(0, suggestedSellPrice));
            result.put("lowest_sell_price", Math.max(0, lowestSellPrice));
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
        int suggestedBuyPrice;
        int suggestedSellPrice;
        int lowestSellPrice;

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
            result.put("suggested_buy_price", Math.max(0, suggestedBuyPrice));
            result.put("suggested_sell_price", Math.max(0, suggestedSellPrice));
            result.put("lowest_sell_price", Math.max(0, lowestSellPrice));
            result.put("source", "automatic");
            return Collections.unmodifiableMap(result);
        }
    }

    private static final class SyncResponse
    {
        Boolean success;
        SyncSummary summary;
        List<SyncResult> results;

        boolean isCompleteFor(String eventId)
        {
            if (success == null || summary == null || summary.received != 1 ||
                results == null || results.size() != 1)
            {
                return false;
            }
            SyncResult result = results.get(0);
            if (result == null || !Objects.equals(eventId, result.event_id))
            {
                return false;
            }
            // A semantic rejection is a complete terminal acknowledgement,
            // not a transport failure: retrying its immutable event ID would
            // permanently block the FIFO and the reconciliation snapshot.
            if ("rejected".equals(result.outcome))
            {
                return !success && summary.rejected == 1;
            }
            return success && summary.rejected == 0 &&
                ("applied".equals(result.outcome) || "duplicate".equals(result.outcome));
        }
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
        String event_id;
        String outcome;
        String classification;
        ServerSlotState slot;
    }

    private static final class ServerStateResponse
    {
        boolean success;
        String code;
        Boolean reconcile_required;
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
        int suggested_buy_price;
        int suggested_sell_price;
        int lowest_sell_price;
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
