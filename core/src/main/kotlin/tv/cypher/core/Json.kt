package tv.cypher.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
object Formats {
    /** Compact, for files the TV downloads. */
    val compact = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** Readable and diff-friendly, for files committed to the repo. */
    val pretty = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = true
    }
}
