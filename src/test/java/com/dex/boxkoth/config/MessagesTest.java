package com.dex.boxkoth.config;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    @Test
    void colorizeReadsAngleBracketHex() {
        var c = Messages.colorize("<#FA912E>&lB");
        assertEquals("B", PlainTextComponentSerializer.plainText().serialize(c));
        var first = c.children().isEmpty() ? c : c.children().get(0);
        assertEquals(TextColor.fromHexString("#FA912E"), first.color());
        assertEquals(TextDecoration.State.TRUE, first.decoration(TextDecoration.BOLD));
    }
}
