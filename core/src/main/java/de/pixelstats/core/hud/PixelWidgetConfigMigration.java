package de.pixelstats.core.hud;

import net.labymod.api.event.Subscribe;
import net.labymod.api.event.labymod.config.ConfigurationVersionUpdateEvent;

/** Preserves HUD settings while migrating card colors and retiring the today chart options. */
public final class PixelWidgetConfigMigration {

  @Subscribe
  public void onConfigVersionUpdate(ConfigurationVersionUpdateEvent event) {
    if (!PixelStatsHudWidget.Config.class.isAssignableFrom(event.getConfigClass())) {
      return;
    }
    boolean migrateCardColor = event.getUsedVersion() < 2;
    boolean retireTodayChart =
        PixelStatsHudWidget.TodayConfig.class.isAssignableFrom(event.getConfigClass())
            && event.getUsedVersion() < 3;
    if (!migrateCardColor && !retireTodayChart) {
      return;
    }
    var json = event.getJsonObject();
    if (migrateCardColor) {
      if (json.has("valueColor") && !json.has("cardValueColor")) {
        json.add("cardValueColor", json.get("valueColor"));
      }
      json.remove("valueColor"); // Native text uses its own white value default, like FPS.
    }
    if (retireTodayChart) {
      json.remove("showChart");
      json.remove("graphColor");
    }
    event.setJsonObject(json);
  }
}
