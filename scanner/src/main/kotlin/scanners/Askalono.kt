/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.scanner.scanners

import ch.frankel.slf4k.*

import com.here.ort.model.EMPTY_JSON_NODE
import com.here.ort.model.Provenance
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.yamlMapper
import com.here.ort.scanner.LocalScanner
import com.here.ort.scanner.Main
import com.here.ort.scanner.ScanException
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.OS
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.getCommandVersion
import com.here.ort.utils.log

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant

import okhttp3.Request

import okio.Okio

object Askalono : LocalScanner() {
    private val platform = when {
        OS.isLinux -> "linux"
        OS.isMac -> "mac"
        OS.isWindows -> "exe"
        else -> throw IllegalArgumentException("Unsupported operating system.")
    }

    override val scannerExe = "askalono.$platform"
    override val scannerVersion = "0.2.0-beta.1"
    override val resultFileExt = "txt"

    override fun bootstrap(): File {
        val url = when {
            OS.isWindows ->
                // See https://github.com/amzn/askalono/issues/23.
                "https://ci.appveyor.com/api/buildjobs/fsnas6tqv3bmkbvx/artifacts/target/release/$scannerExe"
            else -> "https://github.com/amzn/askalono/releases/download/$scannerVersion/$scannerExe"
        }

        log.info { "Downloading $this from '$url'... " }

        val request = Request.Builder().get().url(url).build()

        return OkHttpClientHelper.execute(Main.HTTP_CACHE_PATH, request).use { response ->
            val body = response.body()

            if (response.code() != HttpURLConnection.HTTP_OK || body == null) {
                throw IOException("Failed to download $this from $url.")
            }

            if (response.cacheResponse() != null) {
                log.info { "Retrieved $this from local cache." }
            }

            val scannerDir = createTempDir()
            scannerDir.deleteOnExit()

            val scannerFile = File(scannerDir, scannerExe)
            Okio.buffer(Okio.sink(scannerFile)).use { it.writeAll(body.source()) }

            if (!OS.isWindows) {
                // Ensure the executable Unix mode bit to be set.
                scannerFile.setExecutable(true)
            }

            scannerDir
        }
    }

    override fun getConfiguration() = ""

    override fun getVersion(dir: File) =
            getCommandVersion(dir.resolve(scannerExe).absolutePath, transform = {
                // "askalono --version" returns a string like "askalono 0.2.0-beta.1", so simply remove the prefix.
                it.substringAfter("askalono ")
            })

    override fun scanPath(path: File, resultsFile: File, provenance: Provenance, scannerDetails: ScannerDetails)
            : ScanResult {
        val startTime = Instant.now()

        val process = ProcessCapture(
                scannerPath.absolutePath,
                "crawl", path.absolutePath
        )

        val endTime = Instant.now()

        if (process.stderr().isNotBlank()) {
            log.debug { process.stderr() }
        }

        with(process) {
            if (isSuccess()) {
                stdoutFile.copyTo(resultsFile)
                val result = getResult(resultsFile)
                val summary = ScanSummary(startTime, endTime, result.fileCount, result.licenses, result.errors)
                return ScanResult(provenance, scannerDetails, summary, result.rawResult)
            } else {
                throw ScanException(failMessage)
            }
        }
    }

    override fun getResult(resultsFile: File): Result {
        var fileCount = 0
        val licenses = sortedSetOf<String>()
        val errors = sortedSetOf<String>()

        val json = if (resultsFile.isFile && resultsFile.length() > 0) {
            val rawResult = yamlMapper.createArrayNode()

            val yamlNodes = resultsFile.readLines().chunked(3) { (path, license, score) ->
                val licenseNoOriginalText = license.substringBeforeLast(" (original text)")
                licenses += licenseNoOriginalText.substringAfter("License: ")
                val yamlString = listOf("Path: $path", licenseNoOriginalText, score).joinToString("\n")
                yamlMapper.readTree(yamlString)
            }

            fileCount = yamlNodes.size
            rawResult.addAll(yamlNodes)
        } else {
            EMPTY_JSON_NODE
        }

        return Result(fileCount, licenses, errors, json)
    }
}
