package com.icerayzer.moneytracker

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Taille maximale d'une trame reçue : un téléphone jumelé ne peut pas nous faire allouer n'importe quoi. */
const val MAX_FRAME = 8 * 1024 * 1024

/** Une trame de la synchro : 4 octets de taille (grand-boutiste), puis le texte en UTF-8. */
fun writeFrame(out: DataOutputStream, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    out.writeInt(bytes.size)
    out.write(bytes)
    out.flush()
}

/** Lève une IOException si le flux est coupé ou si la trame dépasse [MAX_FRAME]. */
fun readFrame(input: DataInputStream): String {
    val size = input.readInt()
    if (size !in 0..MAX_FRAME) throw IOException("trame refusée : $size octets")
    val bytes = ByteArray(size)
    input.readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}
