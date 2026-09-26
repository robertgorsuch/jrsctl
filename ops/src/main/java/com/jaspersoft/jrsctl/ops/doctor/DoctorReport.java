package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.ops.Report;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.util.List;
import java.util.Objects;

/**
 * The {@code doctor} result (spec §12.1, §18). Invariants: items are sorted FAIL, WARN, PASS, SKIP;
 * {@code exitCode} is 0 when nothing failed, 6 when the only failures are compat-matrix or
 * unsupported-deployment failures (ADR-0043), otherwise 2; nothing was mutated in producing it.
 */
public record DoctorReport(List<ReportItem> items, Report.Counts counts, int exitCode) {

  public static final String COMPAT = "compat";

  /** The deployment item (ADR-0043); like compat, its failure alone means exit 6. */
  public static final String DEPLOYMENT = "deployment";

  public static final int EXIT_OK = 0;
  public static final int EXIT_PRECHECK = 2;
  public static final int EXIT_UNSUPPORTED = 6;

  public DoctorReport {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(counts, "counts");
    items = List.copyOf(items);
  }

  public static DoctorReport of(List<ReportItem> unsorted) {
    Report report = Report.of(unsorted);
    return new DoctorReport(report.items(), report.counts(), exitCodeFor(report.items()));
  }

  static int exitCodeFor(List<ReportItem> items) {
    boolean anyFail = false;
    boolean onlyCompat = true;
    for (ReportItem item : items) {
      if (item.status() == ReportItem.Status.FAIL) {
        anyFail = true;
        if (!item.name().equals(COMPAT) && !item.name().equals(DEPLOYMENT)) {
          onlyCompat = false;
        }
      }
    }
    if (!anyFail) {
      return EXIT_OK;
    }
    return onlyCompat ? EXIT_UNSUPPORTED : EXIT_PRECHECK;
  }

  public boolean ok() {
    return counts.fail() == 0;
  }
}
