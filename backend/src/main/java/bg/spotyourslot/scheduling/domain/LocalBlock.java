package bg.spotyourslot.scheduling.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A blocking local-time exception. It is used for Business closures and for
 * StaffMember time off; the owning input determines its scope.
 */
public sealed interface LocalBlock {
    /** Blocks every local date from {@code firstDate} through {@code lastDate}, inclusive. */
    record FullDays(LocalDate firstDate, LocalDate lastDate) implements LocalBlock {
        public FullDays {
            Objects.requireNonNull(firstDate, "firstDate");
            Objects.requireNonNull(lastDate, "lastDate");
            if (lastDate.isBefore(firstDate)) {
                throw new IllegalArgumentException("Full-day block last date must not precede its first date");
            }
        }

        public static FullDays singleDay(LocalDate date) {
            return new FullDays(date, date);
        }

        boolean covers(LocalDate date) {
            return !date.isBefore(firstDate) && !date.isAfter(lastDate);
        }
    }

    /** Blocks one local range on one local date. */
    record PartialDay(LocalDate date, LocalPeriod period) implements LocalBlock {
        public PartialDay {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(period, "period");
        }
    }
}
