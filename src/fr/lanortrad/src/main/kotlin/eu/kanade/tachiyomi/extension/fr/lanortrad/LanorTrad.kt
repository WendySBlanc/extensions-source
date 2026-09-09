package eu.kanade.tachiyomi.extension.fr.lanortrad

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.Normalizer
import java.util.Locale

@Source
abstract class LanorTrad : HttpSource() {

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val botHeaders = headers.newBuilder()
        .set("User-Agent", "Googlebot")
        .build()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/js/data/series.js", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = parseMangaData(response.body.string()).map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page)

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = client.newCall(searchMangaRequest(page, query, filters)).asObservableSuccess()
        .map { response ->
            // Source stores all manga metadata in a single JS file
            val allMangas = parseMangaData(response.body.string())
            val filtered = allMangas.filter { it.title.contains(query, ignoreCase = true) }
                .map { it.toSManga() }
            MangasPage(filtered, false)
        }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = client.newCall(GET("$baseUrl/js/data/series.js", headers)).asObservableSuccess()
        .map { response ->
            val mangaList = parseMangaData(response.body.string())
            val mangaData = mangaList.find { it.id == manga.url } ?: throw Exception("Manga not found")
            mangaData.toSManga().apply {
                url = manga.url
            }
        }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), botHeaders)

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url.toSlug()}/"

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = client.newCall(chapterListRequest(manga)).asObservableSuccess()
        .map { response ->
            val document = response.asJsoup()
            document.select("#series-root article li a[href*=/chapitre-]").map { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    val number = link.absUrl("href").substringAfterLast("/chapitre-").substringBefore("/")
                    name = "Chapitre $number"
                    chapter_number = number.toFloatOrNull() ?: -1f
                }
            }
        }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val parts = chapter.url.trim('/').split("/")
        val slug = parts.getOrNull(1) ?: return Observable.just(emptyList())
        val number = parts.getOrNull(2)?.removePrefix("chapitre-") ?: return Observable.just(emptyList())

        return client.newCall(GET("$baseUrl/js/data/chapters.js", headers)).asObservableSuccess()
            .flatMap { response ->
                val chapterPath = parseChapterPath(response.body.string(), slug, number)
                    ?: return@flatMap Observable.just(emptyList())
                val pageFile = chapterPath.seriesId.toSlug()
                    .split("-")
                    .joinToString("-") { word -> word.replaceFirstChar { it.uppercase() } }

                client.newCall(GET("$baseUrl/js/data/pages/$pageFile.js", headers)).asObservableSuccess()
                    .map { pageResponse ->
                        parsePageFiles(pageResponse.body.string(), number).mapIndexed { index, file ->
                            val imageUrl = baseUrl.toHttpUrl().newBuilder()
                                .addPathSegment("Manga")
                                .addPathSegment(chapterPath.seriesId)
                                .addPathSegments(chapterPath.folder.trim('/'))
                                .addPathSegment(file)
                                .build()
                                .toString()
                            Page(index, imageUrl = imageUrl)
                        }
                    }
            }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun parseMangaData(jsString: String): List<LanorMangaDto> {
        val jsonString = jsString.substringAfter("window.SERIES =").substringBeforeLast(";")
        if (jsonString.isBlank() || !jsonString.contains("[")) return emptyList()

        val fixedJson = jsonString
            .replace(commentRegex, "")
            .replace(unquotedKeyRegex) { match ->
                "${match.groupValues[1]}\"${match.groupValues[2]}\":"
            }
            .replace(trailingCommaRegex, "$1")

        return runCatching {
            fixedJson.parseAs<List<LanorMangaDto>>()
        }.getOrElse { emptyList() }
    }

    private fun LanorMangaDto.toSManga() = SManga.create().also { manga ->
        manga.title = title
        val imgToUse = cover.ifEmpty {
            if (type.equals("oneshot", true)) image else coverImage
        }
        manga.thumbnail_url = if (imgToUse.startsWith("http")) {
            imgToUse
        } else {
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(imgToUse.removePrefix("/"))
                .build()
                .toString()
        }
        manga.url = id
        manga.description = description
        manga.status = when (status.lowercase()) {
            "en cours" -> SManga.ONGOING
            "terminé" -> SManga.COMPLETED
            "en pause" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        manga.genre = genres.joinToString { it.trim() }
        manga.author = author.ifBlank { "LanorTrad" }
    }

    private fun parseChapterPath(js: String, slug: String, number: String): ChapterPath? {
        val compactJson = compactChapterDataRegex.find(js)?.groupValues?.get(1) ?: return null
        val allSeries = json.parseToJsonElement(compactJson).jsonObject
        val seriesEntry = allSeries.entries.firstOrNull { it.key.toSlug() == slug } ?: return null
        val series = seriesEntry.value.jsonObject
        val prefix = series["p"]?.jsonPrimitive?.content.orEmpty()
        val chapter = series["c"]?.jsonArray
            ?.firstOrNull { it.jsonArray.firstOrNull()?.jsonPrimitive?.content == number }
            ?.jsonArray
            ?: return null
        val options = chapter.getOrNull(2)?.jsonObject ?: JsonObject(emptyMap())
        val folder = options["f"]?.jsonPrimitive?.content
            ?: (options["p"]?.jsonPrimitive?.content ?: prefix) + number
        return ChapterPath(seriesEntry.key, folder)
    }

    private fun parsePageFiles(js: String, number: String): List<String> {
        val pagesJson = pageDataRegex.find(js)?.groupValues?.get(1) ?: return emptyList()
        return json.parseToJsonElement(pagesJson).jsonObject[number]
            ?.jsonObject
            ?.get("f")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
    }

    private fun String.toSlug(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(combiningMarkRegex, "")
        .replace(nonSlugRegex, "-")
        .replace(repeatedDashRegex, "-")
        .trim('-')
        .lowercase(Locale.ROOT)

    companion object {
        private val commentRegex = Regex("""^\s*//.*$""", RegexOption.MULTILINE)
        private val unquotedKeyRegex = Regex("""([,{]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:""")
        private val trailingCommaRegex = Regex(""",\s*([}\]])""")
        private val compactChapterDataRegex = Regex(
            """return\s+expand\((\{.*\})\);\s*\}\)\(\);""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val pageDataRegex = Regex(
            """window\.CHAPTER_FILES\[[^]]+]\s*=\s*(\{.*});\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val combiningMarkRegex = Regex("""[\u0300-\u036f]""")
        private val nonSlugRegex = Regex("""[^A-Za-z0-9]+""")
        private val repeatedDashRegex = Regex("""-{2,}""")
    }
}

private data class ChapterPath(
    val seriesId: String,
    val folder: String,
)
