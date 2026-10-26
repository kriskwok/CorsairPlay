package com.shilapi.xcertplay.lincoln

/**
 * The HUD socket side of the navigation output, so the publisher can be replaced in tests without
 * binding the car's own loopback ports. [LincolnHudSocketServer] is the real implementation.
 */
internal interface LincolnHudSocket {
    /**
     * Offer the socket; binding a port that is taken is fine and skipped. It is offered for the
     * whole CarPlay session, so the car's navigation client is connected before a route starts.
     */
    fun start()

    /**
     * Publish one maneuver to every connected client. The coordinates are the junction identity the
     * car's navigation adapter uses to decide when a new progress baseline starts; they are not a
     * position and the adapter never shows them.
     */
    fun update(index: Int, metres: Int, street: String, latitude: Double? = null, longitude: Double? = null)

    /** The route ended: tell the clients, but keep the socket offered for the next route. */
    fun end()

    /** The session dropped or the output was switched off: end guidance and release the ports. */
    fun stop()

    /** A short line for the settings screen. */
    fun diagnostics(): String
}
