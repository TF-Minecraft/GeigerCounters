package net.tfminecraft.geigercounters.models;

import java.util.ArrayList;
import java.util.List;

// ====================================
// Represents a reward tier with its weight and list of possible items
// ====================================
public class TierReward {
    private final String tierName;
    private final double weight;
    private final List<ItemReward> items;
    
    public TierReward(String tierName, double weight) {
        this.tierName = tierName;
        this.weight = weight;
        this.items = new ArrayList<>();
    }
    
    public void addItem(ItemReward item) {
        items.add(item);
    }
    
    public String getTierName() {
        return tierName;
    }
    
    public double getWeight() {
        return weight;
    }
    
    public List<ItemReward> getItems() {
        return items;
    }
    
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
