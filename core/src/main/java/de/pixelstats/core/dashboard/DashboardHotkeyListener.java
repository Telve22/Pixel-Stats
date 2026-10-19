package de.pixelstats.core.dashboard;

import de.pixelstats.core.PixelStatsAddon;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.event.Subscribe;
import net.labymod.api.event.client.input.KeyEvent;

/** Opens only from gameplay; typing in chat, menus and key assignment is left to the client. */
public final class DashboardHotkeyListener {
  private final PixelStatsAddon addon;
  private Key held;

  public DashboardHotkeyListener(PixelStatsAddon addon) {
    this.addon = addon;
  }

  @Subscribe
  public void onKey(KeyEvent event) {
    if (event.state() == KeyEvent.State.UNPRESSED) {
      if (event.key().equals(this.held)) {
        this.held = null;
      }
      return;
    }
    Key binding = this.addon.configuration().dashboardKey().get();
    if (event.isCancelled()
        || event.state() != KeyEvent.State.PRESS
        || binding == null
        || binding == Key.NONE
        || binding.isUnknown()
        || !binding.equals(event.key())
        || event.key().equals(this.held)
        || !this.addon.dashboardAvailable()
        || this.addon.labyAPI().minecraft().minecraftWindow().currentScreen() != null) {
      return;
    }
    if (this.addon.openDashboard(true)) {
      this.held = event.key();
      event.setCancelled(true);
    }
  }
}
