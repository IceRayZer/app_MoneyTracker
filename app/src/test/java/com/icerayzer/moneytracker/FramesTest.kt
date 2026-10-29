package com.icerayzer.moneytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class FramesTest {
    private fun bytes(block: (DataOutputStream) -> Unit) = ByteArrayOutputStream().also { block(DataOutputStream(it)) }.toByteArray()

    @Test fun allerRetourAvecAccents() {
        val data = bytes { writeFrame(it, "{\"nom\":\"Épargne €\"}"); writeFrame(it, "ok") }
        val input = DataInputStream(ByteArrayInputStream(data))
        assertEquals("{\"nom\":\"Épargne €\"}", readFrame(input))
        assertEquals("ok", readFrame(input))
    }

    @Test fun trameVide() = assertEquals("", readFrame(DataInputStream(ByteArrayInputStream(bytes { writeFrame(it, "") }))))

    @Test fun tropGrandeRefusee() {
        val data = bytes { it.writeInt(MAX_FRAME + 1) }
        assertThrows(IOException::class.java) { readFrame(DataInputStream(ByteArrayInputStream(data))) }
    }

    @Test fun tailleNegativeRefusee() {
        val data = bytes { it.writeInt(-1) }
        assertThrows(IOException::class.java) { readFrame(DataInputStream(ByteArrayInputStream(data))) }
    }

    @Test fun fluxCoupeEnRoute() {
        val data = bytes { writeFrame(it, "bonjour") }.copyOf(6) // 4 octets de taille + 2 sur 7
        assertThrows(IOException::class.java) { readFrame(DataInputStream(ByteArrayInputStream(data))) }
    }
}
