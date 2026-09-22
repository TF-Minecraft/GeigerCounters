package net.tfminecraft.geigercounters;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.commands.GeigerCommand;
import net.tfminecraft.geigercounters.config.ConfigMigrator;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.metrics.UsageStats;
import net.tfminecraft.geigercounters.managers.GeigerManager;

public class geiger_counter extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 33437;

    private Metrics metrics;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // saveDefaultConfig() only writes a missing file, so an upgraded
        // install needs new keys merged in before anything reads them
        new ConfigMigrator(this).migrate();

        GeigerManager.getInstance(this).initialize();

        GeigerCommand geigerCommand = new GeigerCommand(GeigerManager.getInstance());
        getCommand("geiger").setExecutor(geigerCommand);
        getCommand("geiger").setTabCompleter(geigerCommand);

        setupMetrics();

        getLogger().info("geiger_counter has been enabled!");
    }

    @Override
    public void onDisable() {
        if (GeigerManager.getInstance() != null) {
            GeigerManager.getInstance().shutdown();
        }
        if (metrics != null) {
            metrics.shutdown();
        }
        getLogger().info("geiger_counter has been disabled!");
    }

    // ====================================
    // bStats metrics (https://bstats.org/plugin/bukkit/geiger_counter/33437)
    // Players can opt out globally in plugins/bStats/config.yml
    // ====================================
    private void setupMetrics() {
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        metrics.addCustomChart(new SimplePie("reward_tiers_configured",
            () -> String.valueOf(GeigerManager.getInstance().getConfiguration().getTierRewards().size())));

        metrics.addCustomChart(new SimplePie("max_detection_distance",
            () -> bucketDistance(GeigerManager.getInstance().getConfiguration().getMaxDetectionDistance())));

        // Counter is drained per submission, so the value is the delta
        // for that 30-minute interval
        metrics.addCustomChart(new SingleLineChart("sources_collected",
            () -> UsageStats.getInstance().drainSourcesCollected()));

        metrics.addCustomChart(new SingleLineChart("search_area_size",
            () -> {
                GeigerConfiguration config = GeigerManager.getInstance().getConfiguration();
                double width = config.getMaxX() - config.getMinX();
                double depth = config.getMaxZ() - config.getMinZ();
                return (int) Math.round(width * depth / 1_000_000.0); // in square megablocks
            }));
    }

    private static String bucketDistance(double distance) {
        if (distance <= 500) return "0-500";
        if (distance <= 1000) return "501-1000";
        if (distance <= 2500) return "1001-2500";
        if (distance <= 5000) return "2501-5000";
        return "5000+";
    }

}