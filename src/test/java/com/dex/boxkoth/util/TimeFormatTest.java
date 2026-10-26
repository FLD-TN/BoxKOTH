package com.dex.boxkoth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {

    @Test
    void floorsElapsedTime() {
        assertEquals("00:00", TimeFormat.mmss(0));
        assertEquals("00:00", TimeFormat.mmss(19));
        assertEquals("00:01", TimeFormat.mmss(20));
        assertEquals("05:00", TimeFormat.mmss(6000));
        assertEquals("100:00", TimeFormat.mmss(120_000));
    }

    @Test
    void hoursMinutesSeconds() {
        assertEquals("00:00:00", TimeFormat.hhmmss(0));
        assertEquals("01:01:01", TimeFormat.hhmmss(3661));
        assertEquals("36:00:00", TimeFormat.hhmmss(36 * 3600));
    }

    @Test
    void humanReadable() {
        assertEquals("0 giây", TimeFormat.human(0, "giờ", "phút", "giây"));
        assertEquals("45 giây", TimeFormat.human(45, "giờ", "phút", "giây"));
        assertEquals("5 phút", TimeFormat.human(300, "giờ", "phút", "giây"));
        assertEquals("1 phút 30 giây", TimeFormat.human(90, "giờ", "phút", "giây"));
        assertEquals("1 giờ", TimeFormat.human(3600, "giờ", "phút", "giây"));
        assertEquals("2 giờ 5 phút", TimeFormat.human(7500, "giờ", "phút", "giây"));
    }

    @Test
    void ceilsRemainingTime() {
        assertEquals("00:00", TimeFormat.mmssCeil(0));
        assertEquals("00:01", TimeFormat.mmssCeil(1));
        assertEquals("05:00", TimeFormat.mmssCeil(6000));
        assertEquals("05:00", TimeFormat.mmssCeil(5981));
    }
}
