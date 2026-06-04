package com.rfidunlock.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Bundle
import android.util.Log

/**
 * Контроллер NFC на базе Reader Mode.
 *
 * Отвечает за:
 *  - обнаружение метки и чтение её UID (событие onTagAttached);
 *  - удержание ссылки на текущий тег для последующей проверки присутствия
 *    (используется акселерометром-триггером для детекции снятия).
 */
class NfcController(
    private val onTagAttached: (uid: String) -> Unit,
    private val onTagRemoved: () -> Unit,
) {
    private val tag = "NfcController"
    private var adapter: NfcAdapter? = null

    @Volatile
    private var currentTag: Tag? = null

    @Volatile
    var currentUid: String? = null
        private set

    private val readerCallback = NfcAdapter.ReaderCallback { discovered ->
        val uid = discovered.id.toHex()
        currentTag = discovered
        currentUid = uid
        Log.i(tag, "Метка обнаружена: $uid")
        onTagAttached(uid)
    }

    fun enable(activity: Activity) {
        adapter = NfcAdapter.getDefaultAdapter(activity)
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val options = Bundle().apply {
            // Период опроса присутствия (мс) — компромисс отзывчивость/энергия.
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        }
        adapter?.enableReaderMode(activity, readerCallback, flags, options)
    }

    fun disable(activity: Activity) {
        adapter?.disableReaderMode(activity)
    }

    /**
     * Проверить, присутствует ли ранее обнаруженная метка.
     * Вызывается по триггеру акселерометра: пытается на короткое время
     * подключиться к тегу. Если соединение невозможно — метка снята.
     *
     * @return true, если метка по-прежнему на месте.
     */
    fun checkTagPresence(): Boolean {
        val t = currentTag ?: return false
        val present = runCatching {
            IsoDep.get(t)?.let { connectProbe(it::connect, it::close) }
                ?: NfcA.get(t)?.let { connectProbe(it::connect, it::close) }
                ?: false
        }.getOrDefault(false)

        if (!present) {
            Log.i(tag, "Метка снята (presence check провален)")
            currentTag = null
            currentUid = null
            onTagRemoved()
        }
        return present
    }

    private inline fun connectProbe(connect: () -> Unit, close: () -> Unit): Boolean =
        try {
            connect()
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { close() }
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
