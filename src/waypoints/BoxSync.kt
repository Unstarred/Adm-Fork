package com.engineerclient.waypoints

import com.engineerclient.EngineerClient
import com.engineerclient.adr.ADR
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object BoxSync {

    private const val URL = "https://${ADR.SITE}/adr/api/brboxes"
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun pull(done: (String) -> Unit) {
        Thread.ofPlatform().daemon().name("brboxes-pull").start {
            runCatching {
                val res = http.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
                EngineerClient.logger.info("[ec] brboxes pull: HTTP ${res.statusCode()}, ${res.body().length} chars")
                if (res.statusCode() == 200) done(res.body())
            }.onFailure { EngineerClient.logger.warn("[ec] brboxes pull failed: ${it.message}") }
        }
    }

    private const val ROLES_URL = "https://${ADR.SITE}/adr/api/brroles"

    fun pullRoles(done: (String) -> Unit) {
        Thread.ofVirtual().name("brroles-pull").start {
            runCatching {
                val res = http.send(HttpRequest.newBuilder(URI.create(ROLES_URL)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() == 200) done(res.body())
            }.onFailure { EngineerClient.logger.warn("[ec] brroles pull failed: ${it.message}") }
        }
    }
}
