package bg.spotyourslot.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Characterisation of the public availability policy (two hours of notice, 30 Business-local
 * dates) across a deterministic matrix of zones, instants around daylight-saving transitions,
 * durations, schedules, exceptions, closures and busy intervals. The expected digests in
 * {@code golden/public-availability.txt} were recorded from the engine before the Business-side
 * parameterisation (ADR-0029), so this test proves that the public path is unchanged. Regenerate
 * only deliberately with {@code -Dgolden.regenerate=true}.
 */
class PublicAvailabilityCharacterisationTests {
    private static final String RESOURCE = "/golden/public-availability.txt";
    private static final List<String> ZONES = List.of(
            "Europe/Sofia", "UTC", "Pacific/Auckland", "America/St_Johns");
    private static final List<Instant> NOWS = List.of(
            Instant.parse("2026-03-27T21:30:00Z"),
            Instant.parse("2026-03-28T23:59:59Z"),
            Instant.parse("2026-09-29T08:00:00Z"),
            Instant.parse("2026-10-24T20:59:59Z"),
            Instant.parse("2026-10-25T00:30:00Z"),
            Instant.parse("2026-10-25T01:07:00Z"),
            Instant.parse("2026-12-31T22:30:00Z"));
    private static final List<Integer> MINUTES = List.of(15, 30, 45, 90, 480);

    @Test
    void thePublicPolicyProducesExactlyTheRecordedResults() throws IOException {
        List<String> actual = scenarios();

        if (Boolean.getBoolean("golden.regenerate")) {
            Files.write(
                    Path.of("src/test/resources" + RESOURCE),
                    String.join("\n", actual).concat("\n").getBytes(StandardCharsets.UTF_8));
        }
        List<String> expected;
        try (InputStream stream = PublicAvailabilityCharacterisationTests.class
                .getResourceAsStream(RESOURCE)) {
            assertThat(stream).isNotNull();
            expected = List.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
        assertThat(actual).hasSize(ZONES.size() * NOWS.size() * MINUTES.size());
        assertThat(actual).isEqualTo(expected);
    }

    private static List<String> scenarios() {
        List<String> lines = new ArrayList<>();
        int seed = 0;
        for (String zoneId : ZONES) {
            ZoneId zone = ZoneId.of(zoneId);
            for (Instant now : NOWS) {
                for (int minutes : MINUTES) {
                    AvailabilityRequest request = request(zone, now, minutes, new Random(++seed));
                    List<AvailableSlot> slots = AvailabilityEngine.calculate(request);
                    StringBuilder listing = new StringBuilder();
                    for (AvailableSlot slot : slots) {
                        listing.append(slot.start()).append('|').append(slot.end()).append('|')
                                .append(slot.startOffset()).append('|')
                                .append(slot.staffMemberIds()).append('\n');
                    }
                    lines.add(zoneId + "|" + now + "|" + minutes + "|" + slots.size() + "|"
                            + sha256(listing.toString()));
                }
            }
        }
        return lines;
    }

    private static AvailabilityRequest request(ZoneId zone, Instant now, int minutes, Random random) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        AvailabilityTestSupport.Builder first = AvailabilityTestSupport.staff(
                AvailabilityTestSupport.STAFF_A)
                .everyDay(AvailabilityTestSupport.period("09:00", "18:00"));
        AvailabilityTestSupport.Builder second = AvailabilityTestSupport.staff(
                AvailabilityTestSupport.STAFF_B)
                .recurring(DayOfWeek.MONDAY,
                        AvailabilityTestSupport.period("00:00", "03:30"),
                        AvailabilityTestSupport.period("22:00", "23:59"))
                .recurring(DayOfWeek.SATURDAY, AvailabilityTestSupport.period("08:00", "12:00"))
                .recurring(DayOfWeek.SUNDAY,
                        AvailabilityTestSupport.period("01:00", "05:00"),
                        AvailabilityTestSupport.period("13:00", "17:30"));
        for (DayOfWeek day : List.of(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) {
            second.recurring(day,
                    AvailabilityTestSupport.period("08:00", "12:00"),
                    AvailabilityTestSupport.period("13:00", "17:30"));
        }
        second.override(today.plusDays(3), AvailabilityTestSupport.period("10:00", "11:00"));
        second.override(today.plusDays(4));
        second.additional(today.plusDays(5), AvailabilityTestSupport.period("19:00", "21:15"));
        second.timeOff(new LocalBlock.FullDays(today.plusDays(7), today.plusDays(8)));
        second.timeOff(new LocalBlock.PartialDay(
                today.plusDays(2), AvailabilityTestSupport.period("09:00", "10:30")));
        first.timeOff(LocalBlock.FullDays.singleDay(today.plusDays(29)));
        for (int index = 0; index < 40; index++) {
            Instant start = now.plus(Duration.ofMinutes(random.nextInt(30 * 24 * 60)));
            Instant end = start.plus(Duration.ofMinutes(15L * (1 + random.nextInt(12))));
            if (index % 2 == 0) {
                first.busy(start, end);
            } else {
                second.busy(start, end);
            }
        }
        List<LocalBlock> closures = List.of(
                new LocalBlock.PartialDay(
                        today.plusDays(1), AvailabilityTestSupport.period("12:00", "13:00")),
                LocalBlock.FullDays.singleDay(today.plusDays(10)));
        return new AvailabilityRequest(
                zone,
                Duration.ofMinutes(minutes),
                now,
                closures,
                List.of(first.build(), second.build()));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
