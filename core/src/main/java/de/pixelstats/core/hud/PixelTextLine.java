package de.pixelstats.core.hud;

import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.gui.hud.hudwidget.text.TextHudWidget;
import net.labymod.api.client.gui.hud.hudwidget.text.TextLine;

/** Native TextLine with a conditional value color; formatting and rendering stay in LabyMod. */
final class PixelTextLine extends TextLine {

  private TextColor warningColor;
  private int warningRgb;

  PixelTextLine(TextHudWidget<?> widget, Component key, Object value) {
    super(widget, key, value);
  }

  void updateValue(String value, boolean warning, int color, boolean forceFlush) {
    boolean colorChanged =
        warning != (this.warningColor != null) || (warning && this.warningRgb != color);
    if (colorChanged) {
      this.warningColor = warning ? TextColor.color(color) : null;
      this.warningRgb = color;
    }
    if (update(value) || colorChanged || forceFlush) {
      refresh();
    }
  }

  void refresh() {
    flushInternal();
  }

  @Override
  protected Component updateColor(Component component, TextColor color) {
    return super.updateColor(
        component,
        component == this.valueComponent && this.warningColor != null ? this.warningColor : color);
  }
}
