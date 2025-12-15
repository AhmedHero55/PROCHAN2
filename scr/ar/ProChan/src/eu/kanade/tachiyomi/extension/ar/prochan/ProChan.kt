package eu.kanade.tachiyomi.extension.ar.prochan

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class ProChan : ParsedHttpSource(), ConfigurableSource {

    override val name = "ProChan"
    private val defaultBaseUrl = "https://prochan.net"
    override val baseUrl by lazy { getPrefBaseUrl() }
    override val lang = "ar"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(10, 1, TimeUnit.SECONDS)
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    companion object {
        private const val RESTART_APP = "لتطبيق الإعدادات الجديدة أعد تشغيل التطبيق"
        private const val BASE_URL_PREF_TITLE = "تعديل الرابط"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_SUMMARY = "للاستخدام المؤقت. إعادة تشغيل التطبيق ستحفظ التغييرات"
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
    }

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    // ========================================
    // POPULAR
    // ========================================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/manga-list" + if (page > 1) "?page=$page" else ""
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "div.manga-card"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            title = element.select("h3.title").text()
            setUrlWithoutDomain(element.select("a").attr("href"))
            thumbnail_url = element.select("img").attr("src")
        }
    }

    override fun popularMangaNextPageSelector() = "a.next"

    // ========================================
    // LATEST
    // ========================================
    private val titlesAdded = mutableSetOf<String>()

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) titlesAdded.clear()
        val url = "$baseUrl/latest" + if (page > 1) "?page=$page" else ""
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangaList = document.select(latestUpdatesSelector())
            .map { latestUpdatesFromElement(it) }
            .distinctBy { it.title }
            .filter { !titlesAdded.contains(it.title) }

        titlesAdded.addAll(mangaList.map { it.title })

        val hasNextPage = document.select(latestUpdatesNextPageSelector()).isNotEmpty()
        return MangasPage(mangaList, hasNextPage)
    }

    override fun latestUpdatesSelector() = "div.manga-card"
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ========================================
    // SEARCH
    // ========================================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search?query=$query&page=$page"
        return GET(url, headers)
    }

    override fun searchMangaSelector() = "div.manga-card"
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ========================================
    // DETAILS
    // ========================================
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1.manga-title").text()
            description = document.select("div.manga-description").text()
            genre = document.select("div.manga-genres a").joinToString { it.text() }
            thumbnail_url = document.select("img.manga-thumbnail").attr("src")
            status = document.select("span.status").text().toStatus()
        }
    }

    private fun String?.toStatus(): Int = when (this) {
        "مستمرة" -> SManga.ONGOING
        "قادم قريبًا" -> SManga.ONGOING
        "مكتمل" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // ========================================
    // CHAPTERS
    // ========================================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("ul.chapter-list li a").map { chapterFromElement(it) }
    }

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            name = element.text()
            setUrlWithoutDomain(element.attr("href"))
        }
    }

    // ========================================
    // PAGES
    // ========================================
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.page img").mapIndexed { index, element ->
            Page(index, "", element.attr("src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ========================================
    // CONFIGURABLE BASE URL
    // ========================================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, RESTART_APP, Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(baseUrlPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!
}
