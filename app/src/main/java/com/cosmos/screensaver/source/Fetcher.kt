package com.cosmos.screensaver.source

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class Download(val sha256: String, val byteCount: Long)

internal interface Fetcher {
    /** Fetches a UTF-8 document, failing if it exceeds [maxBytes]. */
    fun fetchText(url: String, maxBytes: Int = MAX_DOCUMENT_BYTES): String

    /** Streams a binary resource to [destination], failing if it exceeds [maxBytes]. */
    fun download(url: String, destination: File, maxBytes: Long = MAX_IMAGE_BYTES): Download

    companion object {
        const val MAX_DOCUMENT_BYTES = 3 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 30L * 1024 * 1024
    }
}

internal class EndpointNotApprovedException(url: String) : IOException("Endpoint not approved: $url")

/** The resource is larger than Cosmos is willing to download; retrying will not help. */
internal class ResourceTooLargeException(message: String) : IOException(message)

/**
 * HTTPS-only fetcher restricted to [ApprovedEndpoints]. Redirects are followed manually so that
 * every hop is re-checked against the allow-list.
 */
internal class HttpsFetcher(private val userAgent: String) : Fetcher {
    override fun fetchText(url: String, maxBytes: Int): String {
        val connection = open(url)
        try {
            val bytes = connection.inputStream.use { readLimited(it, maxBytes.toLong()) }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, destination: File, maxBytes: Long): Download {
        val connection = open(url)
        try {
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (declared > maxBytes) throw ResourceTooLargeException("Resource too large: $declared bytes")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw ResourceTooLargeException("Resource exceeded $maxBytes bytes")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (total == 0L) throw IOException("Empty resource")
            return Download(digest.digest().toHex(), total)
        } catch (error: IOException) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun open(initialUrl: String): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            if (!ApprovedEndpoints.isApproved(url)) throw EndpointNotApprovedException(url)
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IOException("Redirect without location from $url")
                    url = URI(url).resolve(location).toString()
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code from $url")
                }
            }
        }
        throw IOException("Too many redirects from $initialUrl")
    }

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("Document exceeded $maxBytes bytes")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_REDIRECTS = 4
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}
