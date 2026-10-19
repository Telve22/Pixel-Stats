package de.pixelstats.core.dashboard;

import de.pixelstats.core.hud.PixelHudLayout;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.gui.lss.property.annotation.AutoWidget;
import net.labymod.api.client.gui.screen.ScreenContext;
import net.labymod.api.client.gui.screen.widget.SimpleWidget;
import net.labymod.api.client.gui.screen.widget.attributes.bounds.BoundsType;
import net.labymod.api.client.gui.tooltip.Tooltip;
import net.labymod.api.client.render.font.RenderableComponent;
import net.labymod.api.util.bounds.Rectangle;

/** Custom chart within the native Activity; all other UI uses standard LabyMod widgets. */
@AutoWidget
public final class DashboardChartWidget extends SimpleWidget {
  private List<DashboardTimeline.Bucket> days = List.of();
  private Locale locale = Locale.US;
  private DashboardChartLayout layout;
  private RenderableComponent[] dates = new RenderableComponent[0];
  private RenderableComponent[] levels = new RenderableComponent[0];
  private Tooltip[] pointTooltips = new Tooltip[0];
  private DashboardTimeline timeline;
  private RenderableComponent endOfDay;
  private float endOfDayX;
  private float[] labelX = new float[0];
  private float[] labelScales = new float[0];
  private float[] levelScales = new float[0];
  private float width = -1;
  private float height = -1;

  public void update(DashboardTimeline timeline, Locale locale) {
    this.days = timeline.buckets();
    this.timeline = timeline;
    this.locale = locale;
    this.endOfDay = RenderableComponent.of(Component.text("24:00"));
    var days = this.days;
    this.dates = new RenderableComponent[days.size()];
    this.pointTooltips = new Tooltip[days.size()];
    this.tooltip(null);
    for (int index = 0; index < days.size(); index++) {
      var bucket = days.get(index);
      Component label =
          bucket.archived()
              ? Component.translatable("pixelstats.dashboard.earlier")
              : Component.text(bucket.label(locale));
      Component axis =
          bucket.archived()
              ? Component.translatable("pixelstats.dashboard.earlierShort")
              : Component.text(bucket.axisLabel(timeline, locale));
      this.dates[index] = RenderableComponent.of(axis);
      var totals = bucket.totals();
      this.pointTooltips[index] =
          Tooltip.builder()
              .text(
                  Component.translatable(
                      "pixelstats.dashboard.pointTooltip",
                      label,
                      Component.text(PixelHudLayout.signed(totals.income(), locale)),
                      Component.text(PixelHudLayout.signed(totals.expenses().negate(), locale)),
                      Component.text(PixelHudLayout.signed(totals.net(), locale))))
              .delay(0)
              .showOnFocus(false)
              .build();
    }
    rebuildChart();
  }

  @Override
  public void render(ScreenContext context) {
    // Set the cached tooltip before AbstractWidget offers it. Native hover/scroll/overlay
    // handling still decides whether it is eligible to show; only data points are targets.
    this.tooltip(
        tooltipAt(
            (float) context.mouse().getXDouble() - this.bounds().getX(BoundsType.INNER),
            (float) context.mouse().getYDouble() - this.bounds().getY(BoundsType.INNER)));
    super.render(context);
  }

  Tooltip tooltipAt(float x, float y) {
    if (this.layout == null || this.width < 80 || this.height < 40) {
      return null;
    }
    var point = this.layout.pointAt(x, y);
    return point == null ? null : this.pointTooltips[point.bucket()];
  }

  @Override
  public void onBoundsChanged(Rectangle previous, Rectangle next) {
    super.onBoundsChanged(previous, next);
    float width = this.bounds().getWidth(BoundsType.INNER);
    float height = this.bounds().getHeight(BoundsType.INNER);
    if (width != this.width || height != this.height) {
      rebuildChart();
    }
  }

  private void rebuildChart() {
    this.width = Math.max(0, this.bounds().getWidth(BoundsType.INNER));
    this.height = Math.max(0, this.bounds().getHeight(BoundsType.INNER));
    this.layout = DashboardChartLayout.create(this.days, this.width, this.height);
    this.endOfDayX =
        this.endOfDay == null ? 0 : this.layout.right() - this.endOfDay.getWidth() * 0.8f;
    this.levels = new RenderableComponent[5];
    this.levelScales = new float[5];
    for (int index = 0; index < 5; index++) {
      BigDecimal value =
          this.layout.maximum().multiply(BigDecimal.valueOf(index)).divide(BigDecimal.valueOf(4));
      this.levels[index] =
          RenderableComponent.of(
              Component.text(
                  java.text.NumberFormat.getCompactNumberInstance(
                          this.locale, java.text.NumberFormat.Style.SHORT)
                      .format(value)));
      this.levelScales[index] =
          Math.max(
              0,
              Math.min(
                  0.8f, (this.layout.left() - 4) / Math.max(1, this.levels[index].getWidth())));
    }
    this.labelX = new float[this.dates.length];
    this.labelScales = new float[this.dates.length];
    int tickCount = Math.max(2, (int) ((this.layout.right() - this.layout.left()) / 58));
    int stride = Math.max(1, (int) Math.ceil(this.dates.length / (double) tickCount));
    for (int index = 0; index < this.dates.length; index++) {
      if (index % stride != 0
          && (this.timeline.granularity() == DashboardTimeline.Granularity.HOUR
              || index != this.dates.length - 1)) {
        continue;
      }
      float slot =
          (this.layout.right() - this.layout.left())
              / Math.min(tickCount, Math.max(1, this.dates.length));
      this.labelScales[index] =
          Math.max(0, Math.min(0.8f, (slot - 2) / Math.max(1, this.dates[index].getWidth())));
      float labelWidth = this.dates[index].getWidth() * this.labelScales[index];
      this.labelX[index] =
          Math.max(
              0,
              Math.min(this.width - labelWidth, this.layout.centers().get(index) - labelWidth / 2));
    }
  }

  @Override
  public void renderWidget(ScreenContext context) {
    super.renderWidget(context);
    if (this.timeline == null || this.layout == null || this.width < 80 || this.height < 40) {
      return;
    }
    var canvas = context.canvas();
    float x = this.bounds().getX(BoundsType.INNER);
    float y = this.bounds().getY(BoundsType.INNER);
    for (int index = 0; index < 5; index++) {
      float line = this.layout.bottom() - index * (this.layout.bottom() - this.layout.top()) / 4;
      canvas.submitRelativeRect(
          x + this.layout.left(),
          y + line,
          this.layout.right() - this.layout.left(),
          0.5f,
          0xff304157);
      canvas.submitRenderableComponent(
          this.levels[index], x, y + line - 4, 0xff9badc1, this.levelScales[index], 0);
    }
    for (int index = 0; index < this.layout.segments().size(); index++) {
      var line = this.layout.segments().get(index);
      canvas.submitLine(
          x + line.x1(),
          y + line.y1(),
          x + line.x2(),
          y + line.y2(),
          1.3f,
          line.income() ? 0xff42b5f5 : 0xffff8490);
    }
    for (int index = 0; index < this.layout.points().size(); index++) {
      var point = this.layout.points().get(index);
      if (!point.available()) {
        continue;
      }
      canvas.submitCircle(x + point.x(), y + point.y(), 2.8f, 0xff111b29);
      canvas.submitCircle(
          x + point.x(), y + point.y(), 1.9f, point.income() ? 0xff42b5f5 : 0xffff8490);
    }
    for (int index = 0; index < this.dates.length; index++) {
      if (this.labelScales[index] <= 0) {
        continue;
      }
      canvas.submitRenderableComponent(
          this.dates[index],
          x + this.labelX[index],
          y + this.layout.bottom() + 7,
          0xffa8bad0,
          this.labelScales[index],
          0);
    }
    if (this.timeline.granularity() == DashboardTimeline.Granularity.HOUR) {
      canvas.submitRenderableComponent(
          this.endOfDay, x + this.endOfDayX, y + this.layout.bottom() + 7, 0xffa8bad0, 0.8f, 0);
    }
  }
}
