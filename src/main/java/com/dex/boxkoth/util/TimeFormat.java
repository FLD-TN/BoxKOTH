package com.dex.boxkoth.util;

/** Tick to text conversion. Pure, no Bukkit dependencies. */
public final class TimeFormat {

    public static final int TICKS_PER_SECOND = 20;

    private TimeFormat() {
    }

    /** Elapsed time, rounded down to the second: 0..19 ticks -> "00:00". */
    public static String mmss(long ticks) {
        return formatSeconds(Math.max(0L, ticks) / TICKS_PER_SECOND);
    }

    /** Remaining time, rounded up to the second so a countdown never shows 00:00 while running. */
    public static String mmssCeil(long ticks) {
        long t = Math.max(0L, ticks);
        return formatSeconds((t + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
    }

    public static String formatSeconds(long seconds) {
        long s = Math.max(0L, seconds);
        long minutes = s / 60;
        long rest = s % 60;
        return (minutes < 10 ? "0" : "") + minutes + ":" + (rest < 10 ? "0" : "") + rest;
    }

    /** "HH:mm:ss"; hours are not wrapped at 24 (e.g. 36:00:00). */
    public static String hhmmss(long seconds) {
        long s = Math.max(0L, seconds);
        long hours = s / 3600;
        long minutes = (s % 3600) / 60;
        long rest = s % 60;
        return (hours < 10 ? "0" : "") + hours + ":" + (minutes < 10 ? "0" : "") + minutes + ":" + (rest < 10 ? "0" : "") + rest;
    }

    /**
     * Human-readable duration with configurable unit words, omitting zero parts:
     * 90 -> "1 phút 30 giây", 3600 -> "1 giờ", 0 -> "0 giây".
     */
    public static String human(long seconds, String hourWord, String minuteWord, String secondWord) {
        long s = Math.max(0L, seconds);
        long hours = s / 3600;
        long minutes = (s % 3600) / 60;
        long rest = s % 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append(' ').append(hourWord);
        if (minutes > 0) sb.append(sb.isEmpty() ? "" : " ").append(minutes).append(' ').append(minuteWord);
        if (rest > 0 || sb.isEmpty()) sb.append(sb.isEmpty() ? "" : " ").append(rest).append(' ').append(secondWord);
        return sb.toString();
    }

    public static long secondsToTicks(long seconds) {
        return seconds * TICKS_PER_SECOND;
    }
}
