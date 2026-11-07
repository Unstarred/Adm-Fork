package com.engineerclient.util

import java.net.http.HttpClient
import java.time.Duration

/**
 * The one HTTP client ADM's network features share: ADR's uploads and sign-in, the online link, ADS parties, BR box
 * sync and launch stats. Every HttpClient keeps a selector thread and a connection pool of its own, and each of those
 * built one: six threads idle for the whole session. A connect gives up after 15 s (three of them used 10).
 */
object Http {
    val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
}
