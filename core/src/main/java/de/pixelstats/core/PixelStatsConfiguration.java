package de.pixelstats.core;

import net.labymod.api.addon.AddonConfig;
import net.labymod.api.client.gui.screen.key.Key;
import net.labymod.api.client.gui.screen.widget.widgets.input.KeybindWidget.KeyBindSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.SwitchWidget.SwitchSetting;
import net.labymod.api.client.gui.screen.widget.widgets.input.TextFieldWidget.TextFieldSetting;
import net.labymod.api.configuration.loader.annotation.ConfigName;
import net.labymod.api.configuration.loader.property.ConfigProperty;

@ConfigName("settings")
public class PixelStatsConfiguration extends AddonConfig {

  @SwitchSetting private final ConfigProperty<Boolean> enabled = new ConfigProperty<>(true);

  @SwitchSetting private final ConfigProperty<Boolean> automaticRent = new ConfigProperty<>(true);

  @SwitchSetting private final ConfigProperty<Boolean> netEarnings = new ConfigProperty<>(false);

  @SwitchSetting
  private final ConfigProperty<Boolean> provisionalRewards = new ConfigProperty<>(true);

  @TextFieldSetting(maxLength = 100)
  private final ConfigProperty<String> extraServer = new ConfigProperty<>("");

  @KeyBindSetting private final ConfigProperty<Key> dashboardKey = new ConfigProperty<>(Key.F8);

  @Override
  public ConfigProperty<Boolean> enabled() {
    return this.enabled;
  }

  public ConfigProperty<Boolean> automaticRent() {
    return this.automaticRent;
  }

  public ConfigProperty<Boolean> netEarnings() {
    return this.netEarnings;
  }

  public ConfigProperty<Boolean> provisionalRewards() {
    return this.provisionalRewards;
  }

  public ConfigProperty<String> extraServer() {
    return this.extraServer;
  }

  public ConfigProperty<Key> dashboardKey() {
    return this.dashboardKey;
  }
}
