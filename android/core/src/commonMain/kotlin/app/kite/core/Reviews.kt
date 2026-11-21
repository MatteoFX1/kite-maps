package app.kite.core


/** One Google review of a place, as Google Maps lists them. */
data class GoogleReview(val author: String, val rating: Int?, val whenText: String?, val text: String)

/**
 * A place's Google reviews, the ones Google Maps shows first (GitHub #70: Kite only ever showed one).
 * The page Google Maps itself asks; when it stops answering, Kite opens the place's reviews on Google instead.
 * The same as the iPhone's GoogleReviews.
 */
object GoogleReviews {
    /** Google's own numbers for the place ("0x4874…:0x1a2b…"), as the decimal pair its pages ask with. */
    fun ids(featureId: String): Pair<String, String>? {
        val parts = featureId.split(":").map { it.removePrefix("0x") }
        if (parts.size != 2) return null
        val a = parts[0].toULongOrNull(16) ?: return null
        val b = parts[1].toULongOrNull(16) ?: return null
        return a.toString() to b.toString()
    }

    fun url(featureId: String, count: Int = 20): String? {
        val (a, b) = ids(featureId) ?: return null
        val pb = "!1m2!1y$a!2y$b!2m2!1i0!2i$count!3e1!4m5!3b1!4b1!5b1!6b1!7b1"
        return "https://www.google.com/maps/preview/review/listentitiesreviews?authuser=0&hl=en-GB&gl=gb&pb=$pb"
    }

    /** Reviews from Google's answer; null when it isn't the shape expected. Reviews with no words are left out. */
    fun parse(body: String): List<GoogleReview>? {
        val root = GoogleJSON.parse(body) as? List<*> ?: return null
        if (root.size <= 2) return emptyList()
        val list = root[2] as? List<*> ?: return if (root[2] == null) emptyList() else null
        return list.mapNotNull { item ->
            val r = item as? List<*> ?: return@mapNotNull null
            if (r.size <= 4) return@mapNotNull null
            val author = ((r[0] as? List<*>)?.getOrNull(1) as? String) ?: "Google user"
            val text = (r[3] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val rating = (r[4] as? Number)?.toInt()?.takeIf { it in 1..5 }
            GoogleReview(author, rating, r[1] as? String, text)
        }
    }

    /** Where to read every review on Google, for when Kite can't list them itself. */
    fun webUrl(p: Place): String {
        fun enc(s: String) = formEnc(s)
        val q = listOfNotNull(p.name, p.address).joinToString(", ")
        return "https://www.google.com/maps/search/?api=1&query=${enc(q)}" + (p.placeId?.let { "&query_place_id=${enc(it)}" } ?: "")
    }
}
