package tv.cypher.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import tv.cypher.core.*
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage: tools <command> [--key value ...]
  extract-fta     --xlsx FILE --out data/fta.json
  build-channels  --fta data/fta.json --streams in.m3u [--index countries-in.m3u] [--overrides data/overrides.json]
                  [--numbers data/numbers.json] --out dist [--report dist/match-report.md]
  epg-channels    --sites-dir epg/sites --sites tataplay.com,dishtv.in --channels dist/channels.json --out channels.xml
  make-config     --repo owner/name [--branch dist] [--previous config.json] --out dist/config.json
                  [--version-code N --apk-url URL --sha256 HEX [--notes TEXT]]
  build-epg       --xmltv guide.xml[,guide2.xml] --channels dist/channels.json --out dist/epg.json.gz [--now EPOCH]"""

fun main(args: Array<String>) {
    if (args.isEmpty()) { System.err.println(USAGE); exitProcess(2) }
    val opts = parseOpts(args.drop(1))
    fun req(k: String) = opts[k] ?: run { System.err.println("Missing --$k\n$USAGE"); exitProcess(2) }
    when (args[0]) {
        "extract-fta" -> {
            val list = File(req("xlsx")).inputStream().use(ExtractFta::parse)
            File(req("out")).also { it.absoluteFile.parentFile.mkdirs() }
                .writeText(Formats.pretty.encodeToString(ListSerializer(FtaChannel.serializer()), list) + "\n")
            println("Wrote ${list.size} FTA channels to ${req("out")}")
        }
        "build-channels" -> {
            val fta = Formats.compact.decodeFromString(ListSerializer(FtaChannel.serializer()), File(req("fta")).readText())
            val streams = M3u.parse(File(req("streams")).readText())
            val index = opts["index"]?.let { M3u.parse(File(it).readText()) }.orEmpty()
            val overrides = opts["overrides"]?.let(::File)?.takeIf { it.exists() }
                ?.let { Formats.compact.decodeFromString(Overrides.serializer(), it.readText()) } ?: Overrides()
            val numbersFile = opts["numbers"]?.let(::File)
            val numSer = MapSerializer(String.serializer(), Int.serializer())
            val existing = numbersFile?.takeIf { it.exists() }?.let { Formats.compact.decodeFromString(numSer, it.readText()) }.orEmpty()

            val r = BuildChannels.build(fta, streams, index, overrides, existing)
            val out = File(req("out")).also { it.mkdirs() }
            File(out, "channels.json").writeText(Formats.compact.encodeToString(ListSerializer(Channel.serializer()), r.channels))
            File(opts["report"] ?: File(out, "match-report.md").path).writeText(r.report)
            numbersFile?.writeText(
                Formats.pretty.encodeToString(numSer, r.numbers.entries.sortedBy { it.value }.associate { it.key to it.value }) + "\n",
            )
            println("Published ${r.channels.size} channels")
        }
        "epg-channels" -> {
            val channels = Formats.compact.decodeFromString(ListSerializer(Channel.serializer()), File(req("channels")).readText())
            val dir = File(req("sites-dir"))
            val sites = req("sites").split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { site ->
                File(dir, site).listFiles { f -> f.name.endsWith(".channels.xml") }.orEmpty().sortedBy { it.name }
                    .map { site to it.readText() }
            }
            val r = EpgChannels.build(sites, channels.map { it.id }.toSet())
            File(req("out")).also { it.absoluteFile.parentFile.mkdirs() }.writeText(r.xml)
            println("Grabber channels: ${r.covered.size} of ${channels.size} published channels have a guide source")
        }
        "make-config" -> {
            val previous = opts["previous"]?.let(::File)?.takeIf { it.exists() }?.readText()?.let(MakeConfig::parse)
            val app = opts["version-code"]?.let {
                AppInfo(it.toInt(), req("apk-url"), req("sha256").lowercase(), opts["notes"].orEmpty())
            }
            val cfg = MakeConfig.build(req("repo"), opts["branch"] ?: "dist", previous, app)
            File(req("out")).also { it.absoluteFile.parentFile.mkdirs() }
                .writeText(Formats.pretty.encodeToString(Config.serializer(), cfg) + "\n")
            println("Wrote ${req("out")} (app versionCode ${cfg.app?.versionCode ?: "none"})")
        }
        "build-epg" -> {
            val channels = Formats.compact.decodeFromString(ListSerializer(Channel.serializer()), File(req("channels")).readText())
            val now = opts["now"]?.toLong() ?: (System.currentTimeMillis() / 1000)
            val files = req("xmltv").split(',').map { File(it.trim()) }.filter { it.exists() }
            val n = File(req("out")).outputStream().use { out ->
                val streams = files.map { it.inputStream() }
                try { BuildEpg.convert(streams, channels, now, out) } finally { streams.forEach { it.close() } }
            }
            println("Guide for $n of ${channels.size} channels")
        }
        else -> { System.err.println(USAGE); exitProcess(2) }
    }
}

private fun parseOpts(a: List<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < a.size) {
        require(a[i].startsWith("--") && i + 1 < a.size) { "Bad arguments near '${a[i]}'" }
        m[a[i].removePrefix("--")] = a[i + 1]; i += 2
    }
    return m
}
