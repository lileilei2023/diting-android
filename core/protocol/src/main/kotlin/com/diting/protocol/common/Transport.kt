package com.diting.protocol.common

import kotlinx.coroutines.flow.Flow

/**
 * A bidirectional packet channel. On Android this is backed by a BLE GATT
 * characteristic pair (write + notify); in tests it is backed by an in-memory
 * fake. Keeping the protocol layer free of `android.*` is what lets the whole
 * MR20 command set be unit-tested on the JVM.
 */
interface PacketTransport {

    /** Frames as they arrive from the peer. One emission == one BLE notification. */
    val incoming: Flow<ByteArray>

    /** Whether the underlying link is currently usable. */
    val isConnected: Boolean

    /**
     * Writes one frame. Implementations must not split the payload: the MR20
     * command grammar is frame-oriented and the firmware parses one command per
     * write.
     */
    suspend fun write(frame: ByteArray)

    /** Largest payload the link accepts in a single [write] (ATT_MTU - 3 for BLE). */
    val maxFrameSize: Int
        get() = DEFAULT_MAX_FRAME

    companion object {
        /**
         * 244 bytes is what the MR20 spec mandates for OTA frames, which implies a
         * negotiated ATT_MTU of 247. We use it as the default ceiling everywhere.
         */
        const val DEFAULT_MAX_FRAME = 244
    }
}

/** A receive-only byte stream (BLE audio notifications, or the Wi-Fi AP socket). */
interface ByteStreamTransport {
    val incoming: Flow<ByteArray>
    val isConnected: Boolean
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
