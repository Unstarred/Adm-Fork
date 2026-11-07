package com.engineerclient.util

import java.net.http.HttpClient
import java.time.Duration

object Http {
    val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
}
