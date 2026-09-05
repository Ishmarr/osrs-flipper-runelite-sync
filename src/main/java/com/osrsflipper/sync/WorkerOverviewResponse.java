package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Worker overview contract: validation and conversion without client or network state. */
final class WorkerOverviewResponse
{
    private boolean success;
    private long generated_at;
    private long market_generated_at;
    private Integer refresh_after_seconds;
    private OpportunityLists opportunities;
    private OverviewStats stats;
    private List<PriceTestData> price_tests;
    private CashData cash;
    private OverviewAvailability availability;
    private MarketRefresh market_refresh;

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

    boolean matchesFocusItem(int itemId)
    {
        return itemId <= 0 || opportunities == null || opportunities.focus == null ||
            opportunities.focus.item_id == itemId;
    }

    int refreshAfterSeconds()
    {
        return refresh_after_seconds == null ? 60 : Math.max(15, Math.min(60, refresh_after_seconds));
    }

    boolean opportunitiesAvailable()
    {
        // Oudere Worker-versies kenden availability nog niet en leverden bij
        // succes altijd volledige kansen. Dat antwoord blijft compatibel.
        return availability == null || availability.opportunities;
    }

    private boolean marketStale()
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
}
