package de.pixelstats.core.hud;

import net.labymod.api.event.Subscribe;
import net.labymod.api.event.labymod.config.ConfigurationVersionUpdateEvent;

/** Preserves card colors while introducing the genuine TextHudWidgetConfig defaults. */
public final class PixelWidgetConfigMigration {

  @Subscribe
  public void onConfigVersionUpdate(ConfigurationVersionUpdateEvent event) {
    if (!PixelStatsHudWidget.Config.class.isAssignableFrom(event.getConfigClass())
        || event.getUsedVersion() >= 2) {
      return;
    }
    var json = event.getJsonObject();
    if (json.has("valueColor") && !json.has("cardValueColor")) {
      json.add("cardValueColor", json.get("valueColor"));
    }
    json.remove("valueColor"); // Native text uses its own white value default, like FPS.
    event.setJsonObject(json);
  }
}
