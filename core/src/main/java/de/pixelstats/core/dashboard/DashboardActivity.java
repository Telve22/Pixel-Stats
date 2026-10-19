package de.pixelstats.core.dashboard;

import de.pixelstats.core.PixelStatsAddon;
import de.pixelstats.core.data.StatsLedger;
import de.pixelstats.core.hud.PixelHudLayout;
import de.pixelstats.core.hud.PixelHudLocalization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.labymod.api.client.component.Component;
import net.labymod.api.client.component.format.TextColor;
import net.labymod.api.client.gui.screen.Parent;
import net.labymod.api.client.gui.screen.activity.AutoActivity;
import net.labymod.api.client.gui.screen.activity.Link;
import net.labymod.api.client.gui.screen.activity.types.SimpleActivity;
import net.labymod.api.client.gui.screen.widget.Widget;
import net.labymod.api.client.gui.screen.widget.widgets.ComponentWidget;
import net.labymod.api.client.gui.screen.widget.widgets.DivWidget;
import net.labymod.api.client.gui.screen.widget.widgets.input.ButtonWidget;
import net.labymod.api.client.gui.screen.widget.widgets.input.dropdown.DropdownWidget;
import net.labymod.api.client.gui.screen.widget.widgets.layout.ScrollWidget;
import net.labymod.api.client.gui.screen.widget.widgets.layout.list.VerticalListWidget;

/** Native, themeable Activity. The dashboard contains text, panels and a chart, with no artwork. */
@AutoActivity
@Link("dashboard.lss")
public final class DashboardActivity extends SimpleActivity {
  private static final String PREFIX = "pixelstats.dashboard.";
  private static final int CREDIT = 0xff78e6a0;
  private static final int DEBIT = 0xffff8b94;
  private final PixelStatsAddon addon;
  private final UUID account;
  private ComponentWidget balance;
  private ComponentWidget notice;
  private ComponentWidget coverage;
  private ComponentWidget chartTitle;
  private final PeriodCard[] cards = new PeriodCard[3];
  private final List<ComponentWidget[]> rows = new ArrayList<>();
  private final List<DivWidget> historyRows = new ArrayList<>();
  private VerticalListWidget<Widget> table;
  private LocalDateTime displayedHour;
  private StatsLedger displayedLedger;
  private long displayedRevision = -1;
  private DashboardChartWidget chart;
  private DashboardSnapshot snapshot;
  private DashboardPeriod period = DashboardPeriod.WEEK;
  private DashboardPeriod displayedPeriod;
  private String language;
  private long revision;
  private long nextRefresh;
  private boolean translationsPending;

  public DashboardActivity(PixelStatsAddon addon, UUID account) {
    this.addon = addon;
    this.account = account;
  }

  @Override
  public void initialize(Parent parent) {
    super.initialize(parent);
    this.snapshot = null;
    this.language = this.addon.labyAPI().minecraft().options().getCurrentLanguage();
    this.revision = PixelHudLocalization.revision();
    DivWidget panel = new DivWidget().addId("dashboard");
    DivWidget header = new DivWidget().addId("dashboard-header");
    header.addChild(label("title", "dashboard-title"));
    header.addChild(label("subtitle", "dashboard-subtitle"));
    var close = ButtonWidget.i18n(PREFIX + "close", () -> this.displayPreviousScreen());
    close.addId("dashboard-close");
    header.addChild(close);
    panel.addChild(header);

    var content = new VerticalListWidget<net.labymod.api.client.gui.screen.widget.Widget>();
    content.addId("dashboard-content");
    DivWidget status = new DivWidget().addId("dashboard-status");
    this.balance = ComponentWidget.empty().addId("dashboard-balance");
    status.addChild(this.balance);
    this.coverage = ComponentWidget.empty().addId("dashboard-coverage");
    status.addChild(this.coverage);
    content.addChild(status);

    DivWidget periods = new DivWidget().addId("dashboard-periods");
    String[] names = {"today", "week", "total"};
    for (int index = 0; index < 3; index++) {
      this.cards[index] = new PeriodCard(names[index]);
      this.cards[index].panel.addId("period-" + index);
      periods.addChild(this.cards[index].panel);
    }
    content.addChild(periods);
    DivWidget controls = new DivWidget().addId("dashboard-chart-controls");
    this.notice = ComponentWidget.empty().addId("dashboard-notice");
    controls.addChild(this.notice);
    var selector =
        periodSelector(
            this.period,
            selected -> {
              this.period = selected;
              refresh();
            });
    selector.addId("dashboard-period-selector");
    controls.addChild(selector);
    content.addChild(controls);

    DivWidget graph = new DivWidget().addId("dashboard-chart-panel");
    this.chartTitle = label("chartWeek", "section-title");
    graph.addChild(this.chartTitle);
    graph.addChild(label("chartLegend", "chart-legend"));
    this.chart = new DashboardChartWidget();
    this.chart.addId("dashboard-chart");
    graph.addChild(this.chart);
    content.addChild(graph);

    DivWidget history = new DivWidget().addId("dashboard-history");
    history.addChild(label("historyTitle", "section-title"));
    String[] columns = {"date", "income", "expenses", "net"};
    DivWidget tableHeader = new DivWidget().addId("history-header");
    for (int column = 0; column < 4; column++) {
      tableHeader.addChild(label(columns[column], "table-label", "column-" + column));
    }
    history.addChild(tableHeader);
    this.rows.clear();
    this.historyRows.clear();
    this.table = new VerticalListWidget<>();
    this.table.addId("history-rows");
    history.addChild(this.table);
    content.addChild(history);
    content.addChild(label("limits", "dashboard-limits"));
    var scroll = new ScrollWidget(content);
    scroll.addId("dashboard-scroll");
    panel.addChild(scroll);
    this.document().addChild(panel);
    refresh();
  }

  @Override
  public void tick() {
    super.tick();
    if (!available()) {
      this.displayPreviousScreen();
      return;
    }
    long now = System.currentTimeMillis();
    if (now < this.nextRefresh) {
      return;
    }
    this.nextRefresh = now + 500;
    String current = this.addon.labyAPI().minecraft().options().getCurrentLanguage();
    if (!java.util.Objects.equals(current, this.language)
        || this.revision != PixelHudLocalization.revision()) {
      this.reload();
      return;
    }
    refresh();
  }

  private boolean available() {
    return this.addon.dashboardAvailable()
        && this.account.equals(this.addon.labyAPI().getUniqueId());
  }

  static DropdownWidget<DashboardPeriod> periodSelector(
      DashboardPeriod selected, Consumer<DashboardPeriod> changed) {
    var selector = new DropdownWidget<DashboardPeriod>();
    selector.addAll(DashboardPeriod.values());
    selector.setTranslationKeyPrefix(PREFIX + "period");
    selector.setSelected(selected, false);
    selector.setChangeListener(
        value -> {
          if (value != null) {
            changed.accept(value);
          }
        });
    return selector;
  }

  private void refresh() {
    if (!available()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    LocalDate today = now.toLocalDate();
    var ledger = this.addon.ledger();
    LocalDateTime hour = now.truncatedTo(ChronoUnit.HOURS);
    if (this.snapshot != null
        && !this.translationsPending
        && ledger == this.displayedLedger
        && ledger.revision() == this.displayedRevision
        && this.displayedPeriod == this.period
        && hour.equals(this.displayedHour)) {
      return;
    }
    this.translationsPending = false;
    this.displayedLedger = ledger;
    this.displayedRevision = ledger.revision();
    var next = DashboardSnapshot.from(ledger, today);
    this.snapshot = next;
    this.displayedPeriod = this.period;
    this.displayedHour = hour;
    var timeline = DashboardTimeline.from(this.addon.ledger(), now, this.period);
    Locale locale = PixelHudLocalization.locale(this.language);
    var dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale);
    this.balance.setText(
        translate(
            "balance",
            next.balance() == null ? translate("unknown") : number(next.balance(), locale)));
    this.coverage.setText(
        next.since() == null
            ? translate("noHistory")
            : translate("coverage", dates.format(next.since())));
    StatsLedger.DayTotals[] totals = {next.today(), next.week(), next.total()};
    for (int index = 0; index < 3; index++) {
      this.cards[index].update(totals[index], locale);
    }
    this.notice.setText(
        next.pending().signum() == 0
            ? translate("verified")
            : translate("pending", number(next.pending(), locale)));
    this.notice.textColor().set(next.pending().signum() == 0 ? 0xff9badc1 : 0xffffc96b);
    if (timeline.unallocated().income().signum() != 0
        || timeline.unallocated().net().signum() != 0) {
      this.notice.setText(this.noticeText(next, timeline, locale));
    }
    this.chartTitle.setText(
        translate(
            switch (this.period) {
              case DAY -> "chartDay";
              case WEEK -> "chartWeek";
              case TOTAL ->
                  switch (timeline.granularity()) {
                    case DAY -> "chartTotalDays";
                    case WEEK -> "chartTotalWeeks";
                    case MONTH -> "chartTotalMonths";
                    case YEAR -> "chartTotalYears";
                    default -> "chartTotal";
                  };
            }));
    while (this.rows.size() < timeline.buckets().size()) {
      addHistoryRow();
    }
    for (int row = 0; row < this.rows.size(); row++) {
      boolean visible = row < timeline.buckets().size();
      this.historyRows.get(row).visible().set(visible);
      if (!visible) {
        continue;
      }
      var day =
          timeline
              .buckets()
              .get(this.period == DashboardPeriod.DAY ? row : timeline.buckets().size() - 1 - row);
      var cells = this.rows.get(row);
      cells[0].setText(day.archived() ? translate("earlier") : day.label(locale));
      if (!day.available()) {
        for (int column = 1; column < 4; column++) {
          cells[column].setText(translate("unknown"));
        }
        continue;
      }
      setAmount(cells[1], day.totals().income(), locale, CREDIT, true);
      setAmount(cells[2], day.totals().expenses().negate(), locale, DEBIT, true);
      setAmount(
          cells[3],
          day.totals().net(),
          locale,
          day.totals().net().signum() < 0 ? DEBIT : CREDIT,
          true);
    }
    this.chart.update(timeline, locale);
  }

  private String noticeText(DashboardSnapshot next, DashboardTimeline timeline, Locale locale) {
    return (next.pending().signum() == 0
            ? translate("verified")
            : translate("pending", number(next.pending(), locale)))
        + "\n"
        + translate(
            "hourlyUnassigned",
            number(timeline.unallocated().income(), locale),
            number(timeline.unallocated().expenses(), locale));
  }

  private void addHistoryRow() {
    DivWidget row = new DivWidget().addId("history-row");
    var cells = new ComponentWidget[4];
    for (int column = 0; column < 4; column++) {
      cells[column] = ComponentWidget.empty().addId("table-value", "column-" + column);
      row.addChild(cells[column]);
    }
    this.rows.add(cells);
    this.historyRows.add(row);
    this.table.addChild(row);
  }

  private ComponentWidget label(String key, String... ids) {
    return ComponentWidget.i18n(PREFIX + key).addId(ids);
  }

  private String translate(String key, Object... arguments) {
    String fullKey = PREFIX + key;
    String translated = this.addon.labyAPI().internationalization().translate(fullKey, arguments);
    this.translationsPending |= translated == null || translated.equals(fullKey);
    return translated == null ? fullKey : translated;
  }

  private static String number(BigDecimal amount, Locale locale) {
    return PixelHudLayout.format(amount, locale);
  }

  static void setAmount(
      ComponentWidget widget, BigDecimal amount, Locale locale, int color, boolean signed) {
    String text =
        signed && amount.signum() != 0
            ? PixelHudLayout.signed(amount, locale)
            : number(amount, locale);
    // Keep semantic colors inside the component; theme hover patches may replace the
    // widget's base text-color, but cannot turn these explicit spans white.
    widget.setComponent(Component.text(text, TextColor.color(color)));
    widget.allowColors().set(true);
    widget.renderHover().set(false);
  }

  private final class PeriodCard {
    private final DivWidget panel = new DivWidget().addId("period-card");
    private final ComponentWidget net = ComponentWidget.empty().addId("period-net");
    private final ComponentWidget income =
        ComponentWidget.empty().addId("period-income", "period-amount");
    private final ComponentWidget expenses =
        ComponentWidget.empty().addId("period-expenses", "period-amount");

    PeriodCard(String period) {
      this.panel.addChild(label(period, "period-title"));
      this.panel.addChild(label("profitLoss", "period-caption"));
      this.panel.addChild(this.net);
      this.panel.addChild(label("income", "period-income", "period-label"));
      this.panel.addChild(this.income);
      this.panel.addChild(label("expenses", "period-expenses", "period-label"));
      this.panel.addChild(this.expenses);
    }

    void update(StatsLedger.DayTotals totals, Locale locale) {
      setAmount(this.net, totals.net(), locale, totals.net().signum() < 0 ? DEBIT : CREDIT, true);
      setAmount(this.income, totals.income(), locale, CREDIT, true);
      setAmount(this.expenses, totals.expenses().negate(), locale, DEBIT, true);
    }
  }
}
