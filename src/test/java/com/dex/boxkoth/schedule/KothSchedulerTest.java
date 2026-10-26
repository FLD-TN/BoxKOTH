package com.dex.boxkoth.schedule;

import com.dex.boxkoth.config.PluginConfig;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KothSchedulerTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Set<DayOfWeek> ALL = EnumSet.allOf(DayOfWeek.class);
    private static final List<Integer> ANNOUNCE = List.of(300, 60, 10);

    private static ZonedDateTime vn(int day, int h, int m, int s) {
        return ZonedDateTime.of(2026, 9, day, h, m, s, 0, VN); // 2026-09-24 is a Thursday
    }

    private static KothScheduler.Slot slot(Set<DayOfWeek> days, String time, ZonedDateTime now) {
        PluginConfig.Schedule schedule = new PluginConfig.Schedule("campus", days, List.of(LocalTime.parse(time)), null);
        KothScheduler.Slot slot = new KothScheduler.Slot(schedule, LocalTime.parse(time));
        slot.recompute(now, ANNOUNCE);
        return slot;
    }

    @Test
    void vietnamTimeIndependentOfHostZone() {
        // Host clock in UTC: 11:59:59Z is 18:59:59 in Vietnam.
        ZonedDateTime nowUtc = ZonedDateTime.of(2026, 9, 24, 11, 59, 59, 0, UTC);
        ZonedDateTime next = KothScheduler.nextFire(ALL, LocalTime.of(19, 0), nowUtc.withZoneSameInstant(VN));
        assertEquals(ZonedDateTime.of(2026, 9, 24, 12, 0, 0, 0, UTC).toInstant(), next.toInstant());
    }

    @Test
    void strictlyAfterNowAndNoCatchUp() {
        // Restart at 19:05 -> the 19:00 slot moves to tomorrow, the 12:00 slot too.
        assertEquals(vn(25, 19, 0, 0), KothScheduler.nextFire(ALL, LocalTime.of(19, 0), vn(24, 19, 5, 0)));
        assertEquals(vn(25, 12, 0, 0), KothScheduler.nextFire(ALL, LocalTime.of(12, 0), vn(24, 19, 5, 0)));
        // Exactly at the time -> strictly after, so next day.
        assertEquals(vn(25, 19, 0, 0), KothScheduler.nextFire(ALL, LocalTime.of(19, 0), vn(24, 19, 0, 0)));
    }

    @Test
    void respectsDaysOfWeek() {
        // Thursday 20:00 -> next Monday 19:00
        ZonedDateTime next = KothScheduler.nextFire(EnumSet.of(DayOfWeek.MONDAY), LocalTime.of(19, 0), vn(24, 20, 0, 0));
        assertEquals(vn(28, 19, 0, 0), next);
        // Same weekday, time already passed -> one week later
        ZonedDateTime weekly = KothScheduler.nextFire(EnumSet.of(DayOfWeek.THURSDAY), LocalTime.of(19, 0), vn(24, 20, 0, 0));
        assertEquals(ZonedDateTime.of(2026, 10, 1, 19, 0, 0, 0, VN), weekly);
    }

    @Test
    void lagSpikeStillFires() {
        KothScheduler.Slot s = slot(ALL, "19:00:00", vn(24, 18, 59, 58));
        assertFalse(s.isDue(vn(24, 18, 59, 59)));
        // Server frozen over 19:00:00; the next check runs at 19:00:03.
        assertTrue(s.isDue(vn(24, 19, 0, 3)));
        s.recompute(vn(24, 19, 0, 3), ANNOUNCE);
        assertEquals(vn(25, 19, 0, 0), s.nextFire());
    }

    @Test
    void twoTimesPerDayBothFire() {
        KothScheduler.Slot noon = slot(ALL, "12:00:00", vn(24, 11, 0, 0));
        KothScheduler.Slot evening = slot(ALL, "19:00:00", vn(24, 11, 0, 0));
        assertTrue(noon.isDue(vn(24, 12, 0, 0)));
        assertFalse(evening.isDue(vn(24, 12, 0, 0)));
        assertTrue(evening.isDue(vn(24, 19, 0, 0)));
    }

    @Test
    void announcementsFireOnceEach() {
        KothScheduler.Slot s = slot(ALL, "19:00:00", vn(24, 18, 0, 0));
        assertEquals(List.of(), s.dueAnnouncements(vn(24, 18, 54, 59), ANNOUNCE));
        assertEquals(List.of(300), s.dueAnnouncements(vn(24, 18, 55, 0), ANNOUNCE));
        assertEquals(List.of(), s.dueAnnouncements(vn(24, 18, 55, 1), ANNOUNCE));
        assertEquals(List.of(60), s.dueAnnouncements(vn(24, 18, 59, 0), ANNOUNCE));
        assertEquals(List.of(10), s.dueAnnouncements(vn(24, 18, 59, 52), ANNOUNCE));
        assertEquals(List.of(), s.dueAnnouncements(vn(24, 18, 59, 55), ANNOUNCE));
    }

    @Test
    void passedThresholdsAreNotAnnouncedAfterEnable() {
        // Enabled 30 s before the start: 300 and 60 are already passed, only 10 remains.
        KothScheduler.Slot s = slot(ALL, "19:00:00", vn(24, 18, 59, 30));
        assertEquals(List.of(), s.dueAnnouncements(vn(24, 18, 59, 31), ANNOUNCE));
        assertEquals(List.of(10), s.dueAnnouncements(vn(24, 18, 59, 50), ANNOUNCE));
    }

    @Test
    void announcementsResetForNextOccurrence() {
        KothScheduler.Slot s = slot(ALL, "19:00:00", vn(24, 18, 0, 0));
        s.dueAnnouncements(vn(24, 18, 59, 55), ANNOUNCE);
        s.recompute(vn(24, 19, 0, 0), ANNOUNCE);
        assertEquals(List.of(300), s.dueAnnouncements(vn(25, 18, 55, 0), ANNOUNCE));
    }
}
