package net.tfminecraft.geigercounters.models;

public class ItemReward {
    private final String outputItem;
    private final int outputAmount;
    
    public ItemReward(String outputItem, int outputAmount) {
        this.outputItem = outputItem;
        this.outputAmount = outputAmount;
    }
    
    public String getOutputItem() {
        return outputItem;
    }
    
    public int getOutputAmount() {
        return outputAmount;
    }
}
