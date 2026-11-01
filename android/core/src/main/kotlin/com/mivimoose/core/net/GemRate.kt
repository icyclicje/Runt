package com.mivimoose.core.net

import org.json.JSONObject
import java.net.URLEncoder

/**
 * GemRate's API (api.gemrate.com, a key from the GemRate team): one population report across
 * PSA, Beckett, SGC and CGC, with half grades, qualifiers and each grader's gem rate.
 * Their website can't be read by apps (it's behind a bot check), so this needs the key.
 */
class GemRate(private val http: Http, private val key: String) {
    class Hit(val id: String, val description: String, val year: String?, val set: String?, val name: String?, val number: String?, val total: Int)

    /** One grader's population: grade label ("10", "9.5", "10 Pristine"...) to copies. */
    class GraderPop(val total: Int, val gemTotal: Int, val gemRate: Double?, val grades: Map<String, Int>)

    class Pop(val description: String, val url: String?, val total: Int, val gemTotal: Int, val gemRate: Double?, val graders: Map<String, GraderPop>)

    private val headers get() = mapOf("x-api-key" to key, "Accept" to "application/json")

    fun search(query: String): List<Hit> {
        val q = URLEncoder.encode(query.take(200), "UTF-8")
        val data = JSONObject(http.get("$BASE/cards/search/simple?query=$q", headers)).getJSONObject("data")
        val a = data.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            val p = r.optJSONObject("parsed_description")
            Hit(r.optString("universal_gemrate_id").takeIf { it.isNotEmpty() && it != "null" } ?: r.getString("gemrate_id"),
                r.optString("description"), p?.optString("year"), p?.optString("set_name"), p?.optString("name"), p?.optString("card_number"),
                r.optInt("total_population"))
        }
    }

    fun population(id: String): Pop {
        val d = JSONObject(http.get("$BASE/cards/$id/population", headers)).getJSONObject("data")
        val pd = d.getJSONObject("population").getJSONObject("population_data")
        val by = pd.optJSONObject("by_grader")
        val graders = LinkedHashMap<String, GraderPop>()
        if (by != null) for (g in by.keys()) {
            val o = by.getJSONObject(g)
            val grades = LinkedHashMap<String, Int>()
            o.optJSONObject("grades")?.let { gr -> for (k in gr.keys()) gr.optInt(k).takeIf { it > 0 }?.let { grades[label(g, k)] = it } }
            graders[g] = GraderPop(o.optInt("total"), o.optInt("gem_total"), o.optDouble("gem_rate").takeIf { !it.isNaN() }, grades)
        }
        return Pop(d.optString("description"), d.optString("gemrate_url").takeIf { it.isNotEmpty() }, pd.optInt("total"), pd.optInt("gem_total"),
            pd.optDouble("gem_rate").takeIf { !it.isNaN() }, graders)
    }

    /** The card's report: searched by year, set, name and number, then checked by number. */
    fun find(year: Int?, setName: String, name: String, number: String, japanese: Boolean): Pop? {
        val num = number.trimStart('0').ifEmpty { "0" }
        val query = listOfNotNull(year?.toString(), "Pokemon", if (japanese) "Japanese" else null, setName, name, "#$num").joinToString(" ")
        val words = name.lowercase().split(' ', '-').filter { it.length > 1 }
        val hit = search(query).filter { h ->
            val n = h.number?.trimStart('#')?.trimStart('0')?.lowercase()
            n == num.lowercase() && words.count { h.description.lowercase().contains(it) } >= (words.size + 1) / 2 &&
                (japanese == h.description.contains("japanese", ignoreCase = true))
        }.maxByOrNull { it.total } ?: return null
        return population(hit.id)
    }

    companion object {
        const val BASE = "https://api.gemrate.com/v1"

        /** "psa_9_5" -> "9.5", "cgc_10_pristine" -> "10 Pristine", "psa_q8" -> "8 Q". */
        fun label(grader: String, key: String): String {
            val k = key.removePrefix("${grader}_")
            if (k.startsWith("q")) return k.drop(1).replace('_', '.') + " Q"
            val parts = k.split('_')
            val num = parts.takeWhile { p -> p.all { it.isDigit() } }.joinToString(".")
            val rest = parts.drop(parts.takeWhile { p -> p.all { it.isDigit() } }.size).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            return listOf(num, rest).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}
