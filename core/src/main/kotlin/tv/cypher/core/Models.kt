package tv.cypher.core

import kotlinx.serialization.Serializable

/** One playable URL for a channel, with the optional HTTP headers it needs. */
@Serializable
data class Stream(val url: String, val ua: String? = null, val referer: String? = null)

/** A channel as published to `channels.json` and read by the TV app. */
@Serializable
data class Channel(
    val id: String,
    val num: Int,
    val name: String,
    val lang: String,
    val cat: String,
    val logo: String? = null,
    val streams: List<Stream>,
)

@Serializable
data class AppInfo(val versionCode: Int, val apkUrl: String, val sha256: String, val notes: String = "")

/** `config.json`: the single URL baked into the APK points here. */
@Serializable
data class Config(
    val schema: Int = 1,
    val channelsUrl: String,
    val epgUrl: String,
    val app: AppInfo? = null,
)

/** One row of the MIB free-to-air list (`data/fta.json`). */
@Serializable
data class FtaChannel(
    val name: String,
    val aliases: List<String> = emptyList(),
    val cat: String,
    val languages: List<String> = emptyList(),
    val company: String? = null,
)

/** Hand-maintained corrections (`data/overrides.json`). */
@Serializable
data class Overrides(
    /** FTA name -> extra playlist names to try. */
    val aliases: Map<String, List<String>> = emptyMap(),
    /** FTA name -> exact playlist channel id. */
    val map: Map<String, String> = emptyMap(),
    /** FTA names or playlist ids never to publish. */
    val exclude: List<String> = emptyList(),
    /** Playlist ids to publish even though they are not in the FTA list. */
    val extra: List<ExtraChannel> = emptyList(),
    /** Playlist id -> substring of the stream URL to try first. */
    val preferred: Map<String, String> = emptyMap(),
    /** Playlist id -> language, when the FTA data is ambiguous. */
    val lang: Map<String, String> = emptyMap(),
)

@Serializable
data class ExtraChannel(val id: String, val lang: String? = null, val cat: String? = null)

data class Programme(val start: Long, val stop: Long, val title: String)
