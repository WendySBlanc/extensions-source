package eu.kanade.tachiyomi.extension.fr.novelfrance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@Source
abstract class NovelFrance : KeiSource() {

    override val supportsLatest = true

    private val pageCache = ConcurrentHashMap<String, List<String>>()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        if (request.url.encodedPath == RENDER_PATH) {
            renderImageResponse(request)
        } else {
            chain.proceed(request)
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchNovels(page, sort = "popular").toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchNovels(page, sort = "latest").toMangasPage()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val data = client.get("$baseUrl/api/search?q=${query.urlEncode()}").parseAs<NFSearchResponse>()
            return MangasPage(data.novels.map(::toSManga), data.hasMore)
        }

        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selectedValue ?: "popular"
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedValue
        return fetchNovels(page, sort, genre).toMangasPage()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(SortFilter(), GenreFilter())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "novel") return null
        val slug = url.pathSegments.getOrNull(1) ?: return null
        return client.get("$baseUrl/api/novels/$slug").parseAs<NFNovel>().let(::toSManga)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.url.slugFromNovelPath()
        val detailsDeferred = if (fetchDetails) {
            async {
                client.get("$baseUrl/api/novels/$slug").parseAs<NFNovel>().let(::toSManga)
            }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) async { fetchAllChapters(slug) } else null

        SMangaUpdate(
            detailsDeferred?.await() ?: manga,
            chaptersDeferred?.await() ?: chapters,
        )
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (novelSlug, chapterSlug) = chapter.url.chapterPathParts()
        return "$baseUrl/novel/$novelSlug/$chapterSlug"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (novelSlug, chapterSlug) = chapter.url.chapterPathParts()
        val content = client.get("$baseUrl/api/chapters/$novelSlug/$chapterSlug").parseAs<NFChapterContent>()
        val chapterUrl = "/novel/$novelSlug/${content.slug}"
        val chunks = content.toChunks()
        pageCache[chapterUrl] = chunks
        return chunks.toPages(chapterUrl)
    }

    private fun renderImageResponse(request: Request): Response {
        val chapterUrl = request.url.queryParameter("chapter").orEmpty()
        val pageIndex = request.url.queryParameter("page")?.toIntOrNull() ?: 0
        val chunks = pageCache.computeIfAbsent(chapterUrl) { fetchChapterChunks(it) }
        val text = chunks.getOrNull(pageIndex).orEmpty().ifBlank { "Chapitre indisponible." }
        val bytes = renderTextPage(text)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody("image/png".toMediaType()))
            .build()
    }

    // =============================== Utils ================================

    private suspend fun fetchNovels(page: Int, sort: String, genre: String? = null): NFNovelsResponse {
        val params = buildList {
            add("sort=${sort.urlEncode()}")
            add("page=$page")
            if (!genre.isNullOrBlank() && genre != "all") {
                add("genre=${genre.urlEncode()}")
            }
        }.joinToString("&")
        return client.get("$baseUrl/api/novels?$params").parseAs()
    }

    private fun NFNovelsResponse.toMangasPage(): MangasPage = MangasPage(
        novels.map(::toSManga),
        page < totalPages,
    )

    private suspend fun fetchAllChapters(slug: String): List<SChapter> {
        val first = fetchChapterPage(slug, skip = 0)
        val chapters = first.chapters.toMutableList()
        if (first.hasMore) {
            chapters += fetchChapterPages(slug, (PAGE_SIZE until first.total step PAGE_SIZE).toList())
        }
        return chapters.toSChapterList(slug)
    }

    private suspend fun fetchChapterPage(slug: String, skip: Int): NFChaptersResponse = client.get("$baseUrl/api/chapters/$slug?skip=$skip&take=$PAGE_SIZE").parseAs()

    private suspend fun fetchChapterPages(slug: String, skips: List<Int>): List<NFChapter> = coroutineScope {
        skips
            .chunked(MAX_CONCURRENT_CHAPTER_PAGE_REQUESTS)
            .flatMap { chunk ->
                chunk
                    .map { skip ->
                        async { fetchChapterPageOrNull(slug, skip)?.chapters.orEmpty() }
                    }
                    .awaitAll()
                    .flatten()
            }
    }

    private suspend fun fetchChapterPageOrNull(slug: String, skip: Int): NFChaptersResponse? = runCatching { fetchChapterPage(slug, skip) }.getOrNull()

    private fun fetchChapterChunks(chapterUrl: String): List<String> = runBlocking {
        val (novelSlug, chapterSlug) = chapterUrl.chapterPathParts()
        client.get("$baseUrl/api/chapters/$novelSlug/$chapterSlug").parseAs<NFChapterContent>().toChunks()
    }

    private fun NFChapterContent.toChunks(): List<String> {
        val paragraphs = paragraphs
            .sortedBy { it.index }
            .mapNotNull { paragraph ->
                Jsoup.parse(paragraph.content).text().trim().takeIf(String::isNotBlank)
            }

        if (paragraphs.isEmpty()) return listOf(title.ifBlank { "Chapitre $chapterNumber" })

        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        paragraphs.forEach { paragraph ->
            if (current.length + paragraph.length + 2 > CHUNK_CHAR_LIMIT && current.isNotBlank()) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
            if (paragraph.length > CHUNK_CHAR_LIMIT) {
                paragraph.chunked(CHUNK_CHAR_LIMIT).forEach { part ->
                    if (current.isNotBlank()) {
                        chunks += current.toString().trim()
                        current = StringBuilder()
                    }
                    chunks += part.trim()
                }
            } else {
                current.append(paragraph).append("\n\n")
            }
        }
        if (current.isNotBlank()) {
            chunks += current.toString().trim()
        }
        return chunks.ifEmpty { listOf(title.ifBlank { "Chapitre $chapterNumber" }) }
    }

    private fun List<String>.toPages(chapterUrl: String): List<Page> = mapIndexed { index, _ ->
        val renderUrl = "$baseUrl$RENDER_PATH?chapter=${chapterUrl.urlEncode()}&page=$index"
        Page(index, renderUrl, renderUrl)
    }

    private fun toSManga(novel: NFNovel): SManga = SManga.create().apply {
        url = "/novel/${novel.slug}"
        title = novel.title
        thumbnail_url = novel.coverImage?.takeIf(String::isNotBlank)?.let { cover ->
            if (cover.startsWith("http")) cover else baseUrl + cover
        }
        author = buildString {
            append(novel.author.orEmpty())
            novel.translatorName?.takeIf(String::isNotBlank)?.let {
                if (isNotBlank()) append(" - ")
                append("Trad. ").append(it)
            }
        }.ifBlank { null }
        description = novel.description
        genre = novel.genres.joinToString(", ") { it.name }
        status = when (novel.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun NFChapter.toSChapter(novelSlug: String): SChapter = SChapter.create().apply {
        url = "/novel/$novelSlug/$slug"
        name = title?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { "${chapterNumber.cleanNumber()} - $it" }
            ?: "Chapitre ${chapterNumber.cleanNumber()}"
        chapter_number = chapterNumber
        date_upload = createdAt?.toEpochMillis() ?: 0L
    }

    private fun List<NFChapter>.toSChapterList(novelSlug: String): List<SChapter> {
        val seenPaths = mutableSetOf<String>()
        return filter { chapter ->
            val path = "/novel/$novelSlug/${chapter.slug}"
            seenPaths.add(path)
        }
            .sortedBy { it.chapterNumber }
            .map { it.toSChapter(novelSlug) }
    }

    private fun renderTextPage(text: String): ByteArray {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = 38f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val contentWidth = IMAGE_WIDTH - HORIZONTAL_PADDING * 2
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(10f, 1.16f)
            .setIncludePad(true)
            .build()
        val height = max(MIN_IMAGE_HEIGHT, layout.height + VERTICAL_PADDING * 2)
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND_COLOR)
        canvas.save()
        canvas.translate(HORIZONTAL_PADDING.toFloat(), VERTICAL_PADDING.toFloat())
        layout.draw(canvas)
        canvas.restore()

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun String.slugFromNovelPath(): String = trim('/').substringAfter("novel/").substringBefore("/")

    private fun String.chapterPathParts(): Pair<String, String> {
        val parts = trim('/').split("/")
        return parts.getOrNull(1).orEmpty() to parts.getOrNull(2).orEmpty()
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.toEpochMillis(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

    private fun Float.cleanNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    private class SortFilter : Filter.Select<String>("Trier par", SORT_LABELS) {
        val selectedValue: String
            get() = SORT_VALUES.getOrElse(state) { "popular" }
    }

    private class GenreFilter : Filter.Select<String>("Genre", GENRE_LABELS) {
        val selectedValue: String
            get() = GENRE_VALUES.getOrElse(state) { "all" }
    }

    @Serializable
    private data class NFNovelsResponse(
        val novels: List<NFNovel> = emptyList(),
        val totalPages: Int = 1,
        val page: Int = 1,
    )

    @Serializable
    private data class NFSearchResponse(
        val novels: List<NFNovel> = emptyList(),
        val hasMore: Boolean = false,
    )

    @Serializable
    private data class NFNovel(
        val title: String = "",
        val slug: String = "",
        val description: String? = null,
        val coverImage: String? = null,
        val author: String? = null,
        val translatorName: String? = null,
        val status: String? = null,
        val genres: List<NFGenre> = emptyList(),
    )

    @Serializable
    private data class NFGenre(
        val name: String = "",
        val slug: String = "",
    )

    @Serializable
    private data class NFChaptersResponse(
        val chapters: List<NFChapter> = emptyList(),
        val total: Int = 0,
        val hasMore: Boolean = false,
    )

    @Serializable
    private data class NFChapter(
        val chapterNumber: Float = -1f,
        val title: String? = null,
        val slug: String = "",
        val createdAt: String? = null,
    )

    @Serializable
    private data class NFChapterContent(
        val chapterNumber: Float = -1f,
        val title: String = "",
        val slug: String = "",
        val paragraphs: List<NFParagraph> = emptyList(),
    )

    @Serializable
    private data class NFParagraph(
        val index: Int = 0,
        val content: String = "",
    )

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_CONCURRENT_CHAPTER_PAGE_REQUESTS = 6
        private const val CHUNK_CHAR_LIMIT = 1800
        private const val IMAGE_WIDTH = 1080
        private const val MIN_IMAGE_HEIGHT = 1600
        private const val HORIZONTAL_PADDING = 72
        private const val VERTICAL_PADDING = 82
        private const val RENDER_PATH = "/novel-france-rendered-page.png"
        private val BACKGROUND_COLOR = Color.rgb(13, 13, 13)
        private val TEXT_COLOR = Color.rgb(235, 232, 225)

        private val SORT_LABELS = arrayOf("Plus populaires", "Mieux notes", "Nouveautes")
        private val SORT_VALUES = arrayOf("popular", "rating", "latest")
        private val GENRE_LABELS = arrayOf(
            "Tous",
            "Action",
            "Adulte",
            "Anti-Heros",
            "Arts Martiaux",
            "Aventure",
            "Comedie",
            "Drama",
            "Ecchi",
            "Fantaisie",
            "Harem",
            "Horreur",
            "Mature",
            "Mystere",
            "Psychologique",
            "Reincarnation",
            "Romance",
            "School Life",
            "Sci-fi",
            "Seinen",
            "Slice of Life",
            "Surnaturel",
            "Tragedie",
            "Wuxia",
            "Xianxia",
            "Xuanhuan",
            "Yaoi",
        )
        private val GENRE_VALUES = arrayOf(
            "all",
            "action",
            "adulte",
            "anti-h-ros",
            "arts-martiaux",
            "aventure",
            "com-die",
            "drama",
            "ecchi",
            "fantaisie",
            "harem",
            "horreur",
            "mature",
            "myst-re",
            "psychologique",
            "r-incarnation",
            "romance",
            "school-life",
            "sci-fi",
            "seinen",
            "slice-of-life",
            "surnaturel",
            "trag-die",
            "wuxia",
            "xianxia",
            "xuanhuan",
            "yaoi",
        )
    }
}
