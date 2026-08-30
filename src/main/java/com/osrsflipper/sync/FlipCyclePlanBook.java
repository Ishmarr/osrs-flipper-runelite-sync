package com.osrsflipper.sync;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the prices chosen for a flip separate from the GE slot that currently
 * contains its buy or sell offer. A slot can become EMPTY or be reused while
 * the bought items still belong to the same unfinished flip cycle.
 */
final class FlipCyclePlanBook
{
    private static final int MAX_RETAINED_CYCLES = 128;

    private final Map<String, Cycle> cycles = new HashMap<>();

    void clear()
    {
        cycles.clear();
    }

    void restore(Cycle[] persisted)
    {
        cycles.clear();
        if (persisted == null)
        {
            return;
        }
        for (Cycle raw : persisted)
        {
            Cycle cycle = sanitizedCopy(raw);
            if (cycle != null)
            {
                cycles.put(cycle.cycleId, cycle);
            }
        }
        trimClosedCycles();
    }

    Cycle[] persistedCycles()
    {
        List<Cycle> persisted = new ArrayList<>();
        for (Cycle cycle : cycles.values())
        {
            if (cycle != null)
            {
                persisted.add(cycle.copy());
            }
        }
        persisted.sort(Comparator
            .comparingLong((Cycle cycle) -> cycle.lastEventAt)
            .thenComparing(cycle -> cycle.cycleId));
        return persisted.toArray(new Cycle[0]);
    }

    void recordBuy(
        String cycleId,
        String buyOfferId,
        int slotNumber,
        int itemId,
        String itemName,
        int frozenBuyPrice,
        int sellTargetPrice,
        int lowestSellPrice,
        int totalQuantity,
        int filledQuantity,
        String status,
        long startedAt,
        long lastEventAt)
    {
        String safeCycleId = clean(cycleId);
        String safeOfferId = clean(buyOfferId);
        if (safeCycleId.isEmpty() || safeOfferId.isEmpty() || itemId <= 0)
        {
            return;
        }

        Cycle cycle = cycles.get(safeCycleId);
        if (cycle == null && ("empty".equals(status) ||
            (filledQuantity <= 0 && !isOpenStatus(status))))
        {
            return;
        }
        if (cycle == null)
        {
            cycle = new Cycle();
            cycles.put(safeCycleId, cycle);
        }
        int previousFill = cycle.buyFills.getOrDefault(safeOfferId, 0);
        boolean lifecycleChanged = clean(cycle.cycleId).isEmpty() ||
            !safeOfferId.equals(clean(cycle.currentBuyOfferId)) ||
            cycle.buyTotalQuantity != Math.max(cycle.buyTotalQuantity, Math.max(0, totalQuantity)) ||
            previousFill < Math.max(0, filledQuantity) ||
            !clean(cycle.buyStatus).equals(clean(status));
        cycle.cycleId = safeCycleId;
        cycle.currentBuyOfferId = safeOfferId;
        cycle.slotNumber = positiveOrFallback(cycle.slotNumber, slotNumber);
        cycle.itemId = itemId;
        if (!clean(itemName).isEmpty())
        {
            cycle.itemName = itemName.trim();
        }
        if (cycle.frozenBuyPrice <= 0 && frozenBuyPrice > 0)
        {
            cycle.frozenBuyPrice = frozenBuyPrice;
        }
        cycle.sellTargetPrice = SellTargetPriceResolver.raiseOnly(
            cycle.sellTargetPrice,
            sellTargetPrice);
        if (cycle.lowestSellPrice <= 0 && lowestSellPrice > 0)
        {
            cycle.lowestSellPrice = lowestSellPrice;
        }
        cycle.startedAt = cycle.startedAt > 0 ? cycle.startedAt : Math.max(0, startedAt);
        if (lifecycleChanged)
        {
            cycle.lastEventAt = Math.max(cycle.lastEventAt, Math.max(0, lastEventAt));
        }
        cycle.buyTotalQuantity = Math.max(cycle.buyTotalQuantity, Math.max(0, totalQuantity));
        cycle.buyStatus = clean(status);
        int acquiredBefore = cycle.acquiredQuantity();
        cycle.buyFills.put(
            safeOfferId,
            Math.max(cycle.buyFills.getOrDefault(safeOfferId, 0), Math.max(0, filledQuantity)));
        if (cycle.acquiredQuantity() > acquiredBefore)
        {
            cycle.lastAcquiredAt = Math.max(cycle.lastAcquiredAt, Math.max(0, lastEventAt));
        }
        if (cycle.acquiredQuantity() <= 0 && !cycle.isBuyOpen())
        {
            cycles.remove(safeCycleId);
            return;
        }
        trimClosedCycles();
    }

    void recordSell(
        String cycleId,
        String sellOfferId,
        int slotNumber,
        int itemId,
        String itemName,
        int frozenBuyPrice,
        int sellTargetPrice,
        int lowestSellPrice,
        int totalQuantity,
        int filledQuantity,
        String status,
        long startedAt,
        long lastEventAt)
    {
        String safeCycleId = clean(cycleId);
        String safeOfferId = clean(sellOfferId);
        if (safeCycleId.isEmpty() || safeOfferId.isEmpty() || itemId <= 0)
        {
            return;
        }

        Cycle cycle = cycles.computeIfAbsent(safeCycleId, ignored -> new Cycle());
        Sale existingAllocation = cycle.sales.get(safeOfferId);
        boolean lifecycleChanged = clean(cycle.cycleId).isEmpty() ||
            existingAllocation == null ||
            existingAllocation.totalQuantity != Math.max(
                existingAllocation.totalQuantity,
                Math.max(0, totalQuantity)) ||
            existingAllocation.filledQuantity < Math.max(0, filledQuantity) ||
            !clean(existingAllocation.status).equals(clean(status));
        cycle.cycleId = safeCycleId;
        cycle.slotNumber = positiveOrFallback(cycle.slotNumber, slotNumber);
        cycle.itemId = itemId;
        if (!clean(itemName).isEmpty())
        {
            cycle.itemName = itemName.trim();
        }
        if (cycle.frozenBuyPrice <= 0 && frozenBuyPrice > 0)
        {
            cycle.frozenBuyPrice = frozenBuyPrice;
        }
        cycle.sellTargetPrice = SellTargetPriceResolver.raiseOnly(
            cycle.sellTargetPrice,
            sellTargetPrice);
        if (cycle.lowestSellPrice <= 0 && lowestSellPrice > 0)
        {
            cycle.lowestSellPrice = lowestSellPrice;
        }
        cycle.startedAt = cycle.startedAt > 0 ? cycle.startedAt : Math.max(0, startedAt);
        if (lifecycleChanged)
        {
            cycle.lastEventAt = Math.max(cycle.lastEventAt, Math.max(0, lastEventAt));
        }

        if (cycle.acquiredQuantity() <= 0)
        {
            cycle.recoveredAcquiredQuantity = Math.max(
                cycle.recoveredAcquiredQuantity,
                Math.max(0, totalQuantity));
            cycle.lastAcquiredAt = Math.max(
                cycle.lastAcquiredAt,
                Math.max(0, startedAt));
        }
        Sale allocation = cycle.sales.computeIfAbsent(safeOfferId, ignored -> new Sale());
        allocation.offerId = safeOfferId;
        allocation.totalQuantity = Math.max(allocation.totalQuantity, Math.max(0, totalQuantity));
        allocation.filledQuantity = Math.max(allocation.filledQuantity, Math.max(0, filledQuantity));
        allocation.status = clean(status);
        allocation.lastEventAt = Math.max(allocation.lastEventAt, Math.max(0, lastEventAt));
        trimClosedCycles();
    }

    void releaseSell(String cycleId, String sellOfferId, int filledQuantity, long lastEventAt)
    {
        Cycle cycle = cycles.get(clean(cycleId));
        if (cycle == null)
        {
            return;
        }
        Sale allocation = cycle.sales.get(clean(sellOfferId));
        if (allocation == null)
        {
            return;
        }
        allocation.filledQuantity = Math.max(allocation.filledQuantity, Math.max(0, filledQuantity));
        allocation.status = "cancelled";
        allocation.lastEventAt = Math.max(allocation.lastEventAt, Math.max(0, lastEventAt));
        cycle.lastEventAt = Math.max(cycle.lastEventAt, allocation.lastEventAt);
    }

    Cycle selectForSetup(int itemId)
    {
        return select(itemId, 0, 0, false);
    }

    Cycle selectOpenBuy(int itemId)
    {
        if (itemId <= 0)
        {
            return null;
        }
        return cycles.values().stream()
            .filter(cycle -> cycle != null &&
                cycle.itemId == itemId &&
                cycle.frozenBuyPrice > 0 &&
                cycle.lowestSellPrice > 0 &&
                cycle.isBuyOpen())
            .max(cycleRanking(0))
            .map(Cycle::copy)
            .orElse(null);
    }

    Cycle selectForSell(int itemId, int quantity, long sellStartedAt)
    {
        return select(itemId, quantity, sellStartedAt, true);
    }

    Cycle cycle(String cycleId)
    {
        Cycle cycle = cycles.get(clean(cycleId));
        return cycle == null ? null : cycle.copy();
    }

    int size()
    {
        return cycles.size();
    }

    Set<Integer> openItemIds()
    {
        Set<Integer> itemIds = new HashSet<>();
        for (Cycle cycle : cycles.values())
        {
            if (cycle != null && cycle.itemId > 0 && !cycle.isClosed())
            {
                itemIds.add(cycle.itemId);
            }
        }
        return itemIds;
    }

    boolean needsSellTarget(int itemId)
    {
        if (itemId <= 0)
        {
            return false;
        }
        return cycles.values().stream()
            .anyMatch(cycle -> cycle != null &&
                cycle.itemId == itemId &&
                !cycle.isClosed() &&
                cycle.sellTargetPrice <= 0);
    }

    boolean raiseSellTarget(int itemId, int candidatePrice)
    {
        int safeCandidate = Math.max(0, candidatePrice);
        if (itemId <= 0 || safeCandidate <= 0)
        {
            return false;
        }
        boolean changed = false;
        for (Cycle cycle : cycles.values())
        {
            if (cycle == null || cycle.itemId != itemId || cycle.isClosed() ||
                safeCandidate <= cycle.sellTargetPrice)
            {
                continue;
            }
            cycle.sellTargetPrice = safeCandidate;
            changed = true;
        }
        return changed;
    }

    boolean raiseSellTarget(String cycleId, int candidatePrice)
    {
        Cycle cycle = cycles.get(clean(cycleId));
        int safeCandidate = Math.max(0, candidatePrice);
        if (cycle == null || cycle.isClosed() || safeCandidate <= cycle.sellTargetPrice)
        {
            return false;
        }
        cycle.sellTargetPrice = safeCandidate;
        return true;
    }

    int expireOpenCycles(int itemId, long clearedAt)
    {
        if (itemId <= 0 || clearedAt <= 0)
        {
            return 0;
        }
        int removed = 0;
        Iterator<Map.Entry<String, Cycle>> iterator = cycles.entrySet().iterator();
        while (iterator.hasNext())
        {
            Cycle cycle = iterator.next().getValue();
            // Bij gelijke seconden kan de lokale cyclus al de nieuwe generatie
            // zijn terwijl de oudere Workerrespons nog onderweg was. Alleen
            // aantoonbaar oudere (of legacy ongedateerde) cycli vervallen.
            if (cycle == null || cycle.itemId != itemId || cycle.isClosed() ||
                (cycle.startedAt > 0 && cycle.startedAt >= clearedAt))
            {
                continue;
            }
            iterator.remove();
            removed++;
        }
        return removed;
    }

    private Cycle select(int itemId, int quantity, long sellStartedAt, boolean requireQuantity)
    {
        if (itemId <= 0 || quantity < 0)
        {
            return null;
        }
        return cycles.values().stream()
            .filter(cycle -> cycle != null &&
                cycle.itemId == itemId &&
                cycle.frozenBuyPrice > 0 &&
                cycle.lowestSellPrice > 0 &&
                !cycle.isClosed() &&
                cycle.availableQuantity() > 0 &&
                (!requireQuantity || cycle.availableQuantity() >= quantity) &&
                (sellStartedAt <= 0 ||
                    (cycle.startedAt <= sellStartedAt &&
                        cycle.lastAcquiredAt <= sellStartedAt)))
            .max(cycleRanking(quantity))
            .map(Cycle::copy)
            .orElse(null);
    }

    private static Comparator<Cycle> cycleRanking(int quantity)
    {
        return Comparator
            .comparingInt((Cycle cycle) -> quantity > 0 && cycle.availableQuantity() == quantity ? 1 : 0)
            .thenComparingLong(cycle -> cycle.lastEventAt)
            .thenComparingLong(cycle -> cycle.startedAt)
            .thenComparingInt(cycle -> -cycle.slotNumber)
            .thenComparing(cycle -> cycle.cycleId);
    }

    private void trimClosedCycles()
    {
        if (cycles.size() <= MAX_RETAINED_CYCLES)
        {
            return;
        }
        List<Cycle> closed = new ArrayList<>();
        for (Cycle cycle : cycles.values())
        {
            if (cycle != null && cycle.isClosed())
            {
                closed.add(cycle);
            }
        }
        closed.sort(Comparator
            .comparingLong((Cycle cycle) -> cycle.lastEventAt)
            .thenComparing(cycle -> cycle.cycleId));
        int removeCount = Math.min(closed.size(), cycles.size() - MAX_RETAINED_CYCLES);
        for (int index = 0; index < removeCount; index++)
        {
            cycles.remove(closed.get(index).cycleId);
        }
    }

    private static Cycle sanitizedCopy(Cycle raw)
    {
        if (raw == null || clean(raw.cycleId).isEmpty() || raw.itemId <= 0)
        {
            return null;
        }
        Cycle copy = new Cycle();
        copy.cycleId = clean(raw.cycleId);
        copy.currentBuyOfferId = clean(raw.currentBuyOfferId);
        copy.slotNumber = Math.max(0, raw.slotNumber);
        copy.itemId = raw.itemId;
        copy.itemName = clean(raw.itemName);
        copy.frozenBuyPrice = Math.max(0, raw.frozenBuyPrice);
        copy.sellTargetPrice = Math.max(0, raw.sellTargetPrice);
        copy.lowestSellPrice = Math.max(0, raw.lowestSellPrice);
        copy.buyTotalQuantity = Math.max(0, raw.buyTotalQuantity);
        copy.buyStatus = clean(raw.buyStatus);
        copy.recoveredAcquiredQuantity = Math.max(0, raw.recoveredAcquiredQuantity);
        copy.lastAcquiredAt = Math.max(0, raw.lastAcquiredAt);
        copy.startedAt = Math.max(0, raw.startedAt);
        copy.lastEventAt = Math.max(0, raw.lastEventAt);
        if (raw.buyFills != null)
        {
            for (Map.Entry<String, Integer> fill : raw.buyFills.entrySet())
            {
                String offerId = clean(fill.getKey());
                if (!offerId.isEmpty())
                {
                    copy.buyFills.put(offerId, Math.max(0, fill.getValue() == null ? 0 : fill.getValue()));
                }
            }
        }
        if (raw.sales != null)
        {
            for (Map.Entry<String, Sale> sale : raw.sales.entrySet())
            {
                Sale value = Sale.sanitized(sale.getKey(), sale.getValue());
                if (value != null)
                {
                    copy.sales.put(value.offerId, value);
                }
            }
        }
        if (copy.lastAcquiredAt <= 0 && copy.acquiredQuantity() > 0)
        {
            copy.lastAcquiredAt = copy.startedAt;
        }
        return copy;
    }

    private static int positiveOrFallback(int preferred, int fallback)
    {
        return preferred > 0 ? preferred : Math.max(0, fallback);
    }

    private static String clean(String value)
    {
        return value == null ? "" : value.trim();
    }

    private static boolean isOpenStatus(String status)
    {
        return "active".equals(status) || "partially_filled".equals(status);
    }

    static final class Cycle
    {
        String cycleId = "";
        String currentBuyOfferId = "";
        int slotNumber;
        int itemId;
        String itemName = "";
        int frozenBuyPrice;
        int sellTargetPrice;
        int lowestSellPrice;
        int buyTotalQuantity;
        String buyStatus = "";
        int recoveredAcquiredQuantity;
        long lastAcquiredAt;
        long startedAt;
        long lastEventAt;
        Map<String, Integer> buyFills = new HashMap<>();
        Map<String, Sale> sales = new HashMap<>();

        int acquiredQuantity()
        {
            long acquired = Math.max(0, recoveredAcquiredQuantity);
            long observed = 0;
            for (Integer quantity : buyFills.values())
            {
                observed += Math.max(0, quantity == null ? 0 : quantity);
            }
            return clampQuantity(Math.max(acquired, observed));
        }

        int soldQuantity()
        {
            long sold = 0;
            for (Sale sale : sales.values())
            {
                if (sale != null)
                {
                    sold += Math.max(0, sale.filledQuantity);
                }
            }
            return clampQuantity(sold);
        }

        int reservedQuantity()
        {
            long reserved = 0;
            for (Sale sale : sales.values())
            {
                if (sale != null && sale.isOpen())
                {
                    reserved += Math.max(0, sale.totalQuantity - sale.filledQuantity);
                }
            }
            return clampQuantity(reserved);
        }

        int availableQuantity()
        {
            return Math.max(0, acquiredQuantity() - soldQuantity() - reservedQuantity());
        }

        int displayedBuyQuantity()
        {
            return isBuyOpen()
                ? Math.max(buyTotalQuantity, acquiredQuantity())
                : acquiredQuantity();
        }

        boolean isBuyOpen()
        {
            return isOpenStatus(buyStatus);
        }

        boolean isClosed()
        {
            int acquired = acquiredQuantity();
            return !isBuyOpen() && acquired > 0 && soldQuantity() >= acquired;
        }

        Cycle copy()
        {
            Cycle copy = new Cycle();
            copy.cycleId = cycleId;
            copy.currentBuyOfferId = currentBuyOfferId;
            copy.slotNumber = slotNumber;
            copy.itemId = itemId;
            copy.itemName = itemName;
            copy.frozenBuyPrice = frozenBuyPrice;
            copy.sellTargetPrice = sellTargetPrice;
            copy.lowestSellPrice = lowestSellPrice;
            copy.buyTotalQuantity = buyTotalQuantity;
            copy.buyStatus = buyStatus;
            copy.recoveredAcquiredQuantity = recoveredAcquiredQuantity;
            copy.lastAcquiredAt = lastAcquiredAt;
            copy.startedAt = startedAt;
            copy.lastEventAt = lastEventAt;
            copy.buyFills.putAll(buyFills);
            for (Map.Entry<String, Sale> sale : sales.entrySet())
            {
                copy.sales.put(sale.getKey(), sale.getValue().copy());
            }
            return copy;
        }

        private static int clampQuantity(long value)
        {
            return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
        }
    }

    static final class Sale
    {
        String offerId = "";
        int totalQuantity;
        int filledQuantity;
        String status = "";
        long lastEventAt;

        boolean isOpen()
        {
            return "active".equals(status) || "partially_filled".equals(status);
        }

        Sale copy()
        {
            Sale copy = new Sale();
            copy.offerId = offerId;
            copy.totalQuantity = totalQuantity;
            copy.filledQuantity = filledQuantity;
            copy.status = status;
            copy.lastEventAt = lastEventAt;
            return copy;
        }

        static Sale sanitized(String key, Sale raw)
        {
            if (raw == null)
            {
                return null;
            }
            String offerId = clean(raw.offerId).isEmpty() ? clean(key) : clean(raw.offerId);
            if (offerId.isEmpty())
            {
                return null;
            }
            Sale copy = new Sale();
            copy.offerId = offerId;
            copy.totalQuantity = Math.max(0, raw.totalQuantity);
            copy.filledQuantity = Math.max(0, raw.filledQuantity);
            copy.status = clean(raw.status);
            copy.lastEventAt = Math.max(0, raw.lastEventAt);
            return copy;
        }
    }
}
