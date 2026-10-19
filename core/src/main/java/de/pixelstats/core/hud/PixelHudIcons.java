package de.pixelstats.core.hud;

import net.labymod.api.client.gui.icon.Icon;
import net.labymod.api.client.resources.ResourceLocation;

/** Uses the public Icon API; lazy sprites follow theme, icon-pack and resource changes. */
final class PixelHudIcons {

  private PixelHudIcons() {}

  static Icon create(PixelHudLayout.CardIcon icon) {
    var location = ResourceLocation.create("labymod", icon.path());
    return icon.themeSprite() ? Icon.lazyThemeGuiSprite(location) : Icon.texture(location);
  }
}
