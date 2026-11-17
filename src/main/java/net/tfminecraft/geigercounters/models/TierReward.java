package net.tfminecraft.geigercounters.models;

import java.util.ArrayList;
import java.util.List;

// ====================================
// Represents a reward tier with its weight and list of possible entries.
// One entry is drawn per cache; an entry gives one or more items together.
// ====================================
public class TierReward {
    // An entry's items fill one row of the cache window
    public static final int MAX_ENTRY_ITEMS = 9;

    private final String tierName;
    private final double weight;
    private final List<List<ItemReward>> entries;

    public TierReward(String tierName, double weight) {
        this.tierName = tierName;
        this.weight = weight;
        this.entries = new ArrayList<>();
    }

    public void addItem(ItemReward item) {
        addEntry(List.of(item));
    }

    public void addEntry(List<ItemReward> entry) {
        entries.add(List.copyOf(entry));
    }

    public String getTierName() {
        return tierName;
    }

    public double getWeight() {
        return weight;
    }

    public List<List<ItemReward>> getEntries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
