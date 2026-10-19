package de.pixelstats.core.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Cached line/point geometry shared by painting and precise point hit testing. */
public record DashboardChartLayout(
    float left,
    float top,
    float right,
    float bottom,
    BigDecimal maximum,
    List<Point> points,
    List<Segment> segments,
    List<Float> centers) {
  public DashboardChartLayout {
    points = List.copyOf(points);
    segments = List.copyOf(segments);
    centers = List.copyOf(centers);
  }

  public static DashboardChartLayout create(
      List<DashboardTimeline.Bucket> buckets, float width, float height) {
    float left = Math.min(38, Math.max(0, width * 0.22f));
    float right = Math.max(left, width - 6);
    float top = Math.min(8, Math.max(0, height * 0.1f));
    float bottom = Math.max(top, height - 22);
    BigDecimal maximum = BigDecimal.ONE;
    for (var bucket : buckets) {
      if (bucket.available()) {
        maximum = maximum.max(bucket.totals().income()).max(bucket.totals().expenses());
      }
    }
    var points = new ArrayList<Point>();
    var segments = new ArrayList<Segment>();
    var centers = new ArrayList<Float>();
    boolean hourly = !buckets.isEmpty() && buckets.getFirst().hour() >= 0;
    for (int index = 0; index < buckets.size(); index++) {
      float fraction =
          hourly ? index / 24f : buckets.size() == 1 ? 0.5f : index / (float) (buckets.size() - 1);
      float x = left + fraction * (right - left);
      centers.add(x);
      var bucket = buckets.get(index);
      BigDecimal[] values = {bucket.totals().income(), bucket.totals().expenses()};
      for (int series = 0; series < 2; series++) {
        float ratio = values[series].divide(maximum, 12, RoundingMode.HALF_UP).floatValue();
        float y = bottom - Math.max(0, Math.min(1, ratio)) * (bottom - top);
        var point = new Point(index, series == 0, x, y, bucket.available());
        if (index > 0
            && point.available()
            && !bucket.archived()
            && !buckets.get(index - 1).archived()) {
          var previous = points.get((index - 1) * 2 + series);
          if (previous.available()) {
            segments.add(new Segment(previous.x(), previous.y(), x, y, series == 0));
          }
        }
        points.add(point);
      }
    }
    return new DashboardChartLayout(left, top, right, bottom, maximum, points, segments, centers);
  }

  public Point pointAt(float x, float y) {
    Point closest = null;
    float distance = 25;
    for (int index = 0; index < this.points.size(); index++) {
      Point point = this.points.get(index);
      float dx = x - point.x();
      float dy = y - point.y();
      float squared = dx * dx + dy * dy;
      if (point.available() && squared <= distance) {
        closest = point;
        distance = squared;
      }
    }
    return closest;
  }

  public record Point(int bucket, boolean income, float x, float y, boolean available) {}

  public record Segment(float x1, float y1, float x2, float y2, boolean income) {}
}
