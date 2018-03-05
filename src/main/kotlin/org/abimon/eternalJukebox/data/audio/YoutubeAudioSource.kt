package org.abimon.eternalJukebox.data.audio

import com.github.kittinunf.fuel.Fuel
import org.abimon.eternalJukebox.*
import org.abimon.eternalJukebox.objects.*
import org.abimon.visi.io.DataSource
import org.abimon.visi.io.FileDataSource
import java.io.File
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

object YoutubeAudioSource : IAudioSource {
    val apiKey: String?
    val uuid: String
        get() = UUID.randomUUID().toString()
    val format: String
    val command: List<String>

    val mimes = mapOf(
            "m4a" to "audio/m4a",
            "aac" to "audio/aac",
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav"
    )

    override fun provide(info: JukeboxInfo, clientInfo: ClientInfo?): DataSource? {
        if (apiKey == null)
            return null

        log("[${clientInfo?.userUID}] Attempting to provide audio for $info")

        val artistTitle = getMultiContentDetailsWithKey(searchYoutubeWithKey("${info.artist} - ${info.title}", 10).map { it.id.videoId })
        val artistTitleLyrics = getMultiContentDetailsWithKey(searchYoutubeWithKey("${info.artist} - ${info.title} lyrics", 10).map { it.id.videoId })
        val both = ArrayList<YoutubeContentItem>().apply {
            addAll(artistTitle)
            addAll(artistTitleLyrics)
        }.sortedWith(Comparator { o1, o2 -> Math.abs(info.duration - o1.contentDetails.duration.toMillis()).compareTo(Math.abs(info.duration - o2.contentDetails.duration.toMillis())) })

        val closest = both.firstOrNull()
                ?: return logNull("[${clientInfo?.userUID}] Searches for both \"${info.artist} - ${info.title}\" and \"${info.artist} - ${info.title} lyrics\" turned up nothing")

        log("[${clientInfo?.userUID}] Settled on ${closest.snippet.title} (https://youtu.be/${closest.id})")

        val tmpFile = File("$uuid.tmp")
        val tmpLog = File("${info.id}-$uuid.log")
        val ffmpegLog = File("${info.id}-$uuid.log")
        val endGoalTmp = File(tmpFile.absolutePath.replace(".tmp", ".$format"))

        try {
            val downloadProcess = ProcessBuilder().command(ArrayList(command).apply {
                add("https://youtu.be/${closest.id}")
                add(tmpFile.absolutePath)
                add(format)
            }).redirectErrorStream(true).redirectOutput(tmpLog).start()

            if (!downloadProcess.waitFor(90, TimeUnit.SECONDS)) {
                downloadProcess.destroyForcibly().waitFor()
                log("[${clientInfo?.userUID}] Forcibly destroyed the download process for ${closest.id}", true)
            }

            if (!endGoalTmp.exists()) {
                log("[${clientInfo?.userUID}] $endGoalTmp does not exist, attempting to convert with ffmpeg")

                if (!tmpFile.exists())
                    return logNull("[${clientInfo?.userUID}] $tmpFile does not exist, what happened?", true)

                if (MediaWrapper.ffmpeg.installed) {
                    if (!MediaWrapper.ffmpeg.convert(tmpFile, endGoalTmp, ffmpegLog))
                        return logNull("[${clientInfo?.userUID}] Failed to convert $tmpFile to $endGoalTmp", true)

                    if (!endGoalTmp.exists())
                        return logNull("[${clientInfo?.userUID}] $endGoalTmp still does not exist, what happened?", true)
                } else
                    return logNull("[${clientInfo?.userUID}] ffmpeg not installed, nothing we can do", true)
            }

            endGoalTmp.useThenDelete { EternalJukebox.storage.store("${info.id}.$format", EnumStorageType.AUDIO, FileDataSource(it), mimes[format] ?: "audio/mpeg", clientInfo) }

            return EternalJukebox.storage.provide("${info.id}.$format", EnumStorageType.AUDIO, clientInfo)
        } finally {
            tmpFile.guaranteeDelete()
            File(tmpFile.absolutePath + ".part").guaranteeDelete()
            tmpLog.useThenDelete { EternalJukebox.storage.store(it.name, EnumStorageType.LOG, FileDataSource(it), "text/plain", clientInfo) }
            ffmpegLog.useThenDelete { EternalJukebox.storage.store(it.name, EnumStorageType.LOG, FileDataSource(it), "text/plain", clientInfo) }
            endGoalTmp.useThenDelete { EternalJukebox.storage.store("${info.id}.$format", EnumStorageType.AUDIO, FileDataSource(it), mimes[format] ?: "audio/mpeg", clientInfo) }
        }
    }

    override fun provideLocation(info: JukeboxInfo, clientInfo: ClientInfo?): URL? {
        if (apiKey == null)
            return null

        log("[${clientInfo?.userUID}] Attempting to provide a location for $info")

        val artistTitle = getMultiContentDetailsWithKey(searchYoutubeWithKey("${info.artist} - ${info.title}", 10).map { it.id.videoId })
        val artistTitleLyrics = getMultiContentDetailsWithKey(searchYoutubeWithKey("${info.artist} - ${info.title} lyrics", 10).map { it.id.videoId })
        val both = ArrayList<YoutubeContentItem>().apply {
            addAll(artistTitle)
            addAll(artistTitleLyrics)
        }.sortedWith(Comparator { o1, o2 -> Math.abs(info.duration - o1.contentDetails.duration.toMillis()).compareTo(Math.abs(info.duration - o2.contentDetails.duration.toMillis())) })

        val closest = both.firstOrNull()
                ?: return logNull("[${clientInfo?.userUID}] Searches for both \"${info.artist} - ${info.title}\" and \"${info.artist} - ${info.title} lyrics\" turned up nothing")

        return URL("https://youtu.be/${closest.id}")
    }

    fun getContentDetailsWithKey(id: String): YoutubeContentItem? {
        val (_, _, r) = Fuel.get("https://www.googleapis.com/youtube/v3/videos", listOf("part" to "contentDetails,snippet", "id" to id, "key" to (apiKey
                ?: return null)))
                .header("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:44.0) Gecko/20100101 Firefox/44.0")
                .responseString()

        val (result, error) = r

        if (error != null)
            return null

        return EternalJukebox.jsonMapper.readValue(result, YoutubeContentResults::class.java).items.firstOrNull()
    }

    fun getMultiContentDetailsWithKey(ids: List<String>): List<YoutubeContentItem> {
        val (_, _, r) = Fuel.get("https://www.googleapis.com/youtube/v3/videos", listOf("part" to "contentDetails,snippet", "id" to ids.joinToString(), "key" to (apiKey
                ?: return emptyList())))
                .header("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:44.0) Gecko/20100101 Firefox/44.0")
                .responseString()

        val (result, error) = r

        if (error != null)
            return emptyList()

        return EternalJukebox.jsonMapper.readValue(result, YoutubeContentResults::class.java).items
    }

    fun searchYoutubeWithKey(query: String, maxResults: Int = 5): List<YoutubeSearchItem> {
        val (_, _, r) = Fuel.get("https://www.googleapis.com/youtube/v3/search", listOf("part" to "snippet", "q" to query, "maxResults" to "$maxResults", "key" to (apiKey
                ?: return emptyList()), "type" to "video"))
                .header("User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:44.0) Gecko/20100101 Firefox/44.0")
                .responseString()

        val (result, error) = r

        if (error != null)
            return ArrayList()

        return EternalJukebox.jsonMapper.readValue(result, YoutubeSearchResults::class.java).items
    }

    init {
        apiKey = EternalJukebox.config.audioSourceOptions["API_KEY"] as? String
        format = EternalJukebox.config.audioSourceOptions["AUDIO_FORMAT"] as? String ?: "m4a"
        command = (EternalJukebox.config.audioSourceOptions["AUDIO_COMMAND"] as? List<*>)?.map { "$it" } ?: (EternalJukebox.config.audioSourceOptions["AUDIO_COMMAND"] as? String)?.split("\\s+".toRegex()) ?: if (System.getProperty("os.name").toLowerCase().contains("windows")) listOf("yt.bat") else listOf("bash", "yt.sh")

        if (apiKey == null)
            log("Warning: No API key provided. We're going to scrape the Youtube search page which is a not great thing to do.\nTo obtain an API key, follow the guide here (https://developers.google.com/youtube/v3/getting-started) or over on the EternalJukebox Github page!", true)
    }
}