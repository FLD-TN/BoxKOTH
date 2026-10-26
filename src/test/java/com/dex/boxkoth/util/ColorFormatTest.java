package com.dex.boxkoth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorFormatTest {

    @Test
    void singleHexFormats() {
        assertEquals("&#FA912E&lB&#f99932&la", ColorFormat.normalize("<#FA912E>&lB<#f99932>&la"));
        assertEquals("&#FA912Ea", ColorFormat.normalize("{#FA912E}a"));
        assertEquals("&#FA912Ea", ColorFormat.normalize("&x&F&A&9&1&2&Ea"));
        assertEquals("&#fa912ea", ColorFormat.normalize("§x§f§a§9§1§2§ea"));
        assertEquals("&#FA912E", ColorFormat.normalize("&#FA912E"));
        assertEquals("<#XYZ> &a #1 {clan}", ColorFormat.normalize("<#XYZ> &a #1 {clan}"));
    }

    @Test
    void gradientColoursEveryCharacterAndKeepsFormats() {
        assertEquals("&#FF0000&lA&#800080&lB&#0000FF&lC",
                ColorFormat.normalize("<gradient:#FF0000:#0000FF>&lABC</gradient>"));
        assertEquals("x &#FF0000A&#00FF00B&#0000FFC y",
                ColorFormat.normalize("x <gradient:#FF0000:#00FF00:#0000FF>ABC</gradient> y"));
        assertEquals("&#FF0000A", ColorFormat.normalize("<gradient:#FF0000:#0000FF>A</gradient>"));
    }

    @Test
    void resetInsideGradientDropsFormats() {
        assertEquals("&#000000&lA&#FFFFFFB", ColorFormat.normalize("<gradient:#000000:#FFFFFF>&lA&rB</gradient>"));
    }
}
