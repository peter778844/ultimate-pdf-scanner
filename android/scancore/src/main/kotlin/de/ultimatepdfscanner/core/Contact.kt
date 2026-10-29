package de.ultimatepdfscanner.core

/** Contact data read from a business card's OCR text (rule-based, offline). */
data class Contact(
    val name: String? = null,
    val company: String? = null,
    val title: String? = null,
    val phones: List<Pair<String, String>> = emptyList(), // type (WORK, CELL, FAX) to number
    val emails: List<String> = emptyList(),
    val web: String? = null,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
) {
    val isEmpty get() = name == null && company == null && phones.isEmpty() && emails.isEmpty()

    /** vCard 3.0, understood by Android contacts, Outlook and iOS. */
    fun toVCard(): String = buildString {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        val n = name ?: company ?: "Kontakt"
        val parts = n.split(' ').filter { it.isNotBlank() }
        val last = if (name != null && parts.size > 1) parts.last() else n
        val first = if (name != null && parts.size > 1) parts.dropLast(1).joinToString(" ") else ""
        append("N:${esc(last)};${esc(first)};;;\r\n")
        append("FN:${esc(n)}\r\n")
        company?.let { append("ORG:${esc(it)}\r\n") }
        title?.let { append("TITLE:${esc(it)}\r\n") }
        phones.forEach { (type, num) -> append("TEL;TYPE=$type:${esc(num)}\r\n") }
        emails.forEach { append("EMAIL;TYPE=INTERNET:${esc(it)}\r\n") }
        web?.let { append("URL:${esc(it)}\r\n") }
        if (street != null || city != null) append("ADR;TYPE=WORK:;;${esc(street ?: "")};${esc(city ?: "")};;${esc(postalCode ?: "")};\r\n")
        append("END:VCARD\r\n")
    }

    companion object {
        private val EMAIL = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
        private val WEB = Regex("""\b((https?://)?www\.[\w-]+(\.[\w-]+)+(/\S*)?|https?://\S+)""", RegexOption.IGNORE_CASE)
        private val PHONE = Regex("""(\+\d{1,3}[\s./-]?)?(\(0\)\s?)?\(?\d{2,5}\)?[\s./-]?\d[\d\s./-]{4,}\d""")
        private val PLZ_CITY = Regex("""\b(D-)?(\d{5})\s+([A-ZÄÖÜ][\p{L}.\- ]+)""")
        private val STREET = Regex("""(?i)(str\.|straße|strasse|weg|platz|allee|gasse|ring|damm|ufer|chaussee)\s*\d+\s*\w?\b|^\p{L}[\p{L}.\- ]+\s\d{1,4}\s?[a-z]?$""")
        private val COMPANY = Regex("""(?i)\b(gmbh|ag|kg|ohg|ug|e\.\s?k\.|e\.\s?v\.|ltd|inc|llc|co\.|se|mbh|gbr|partner|consulting|solutions|group|holding)\b""")
        private val TITLE = Regex("""(?i)\b(geschäftsführer\w*|inhaber\w*|manager\w*|leiter\w*|berater\w*|vertrieb\w*|ingenieur\w*|director|ceo|cto|cfo|head of|sales|consultant|engineer|developer|entwickler\w*|assistenz|assistant|referent\w*|prokurist\w*|partner\w*|projektleiter\w*|architekt\w*|rechtsanwalt|rechtsanwältin|steuerberater\w*|arzt|ärztin)\b""")
        private val ACADEMIC = Regex("""^(Dr\.|Prof\.|Dipl\.-?\w*\.?|M\.\s?Sc\.|B\.\s?Sc\.|Mag\.)\s*""")
        private val LABEL_CELL = Regex("""(?i)\b(mobil|mobile|handy|cell|m)\b\s*[:.]?""")
        private val LABEL_FAX = Regex("""(?i)\b(fax|f)\b\s*[:.]?""")

        fun parse(text: String): Contact {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val emails = EMAIL.findAll(text).map { it.value.trimEnd('.') }.distinct().toList()
            val web = lines.firstNotNullOfOrNull { l -> WEB.find(l)?.value?.takeIf { w -> emails.none { e -> w in e } } }
            val phones = ArrayList<Pair<String, String>>()
            val used = HashSet<String>()
            for (l in lines) {
                if (EMAIL.containsMatchIn(l) || PLZ_CITY.containsMatchIn(l)) continue
                for (m in PHONE.findAll(l)) {
                    val num = m.value.trim()
                    if (num.count { it.isDigit() } < 6) continue
                    val before = l.substring(0, m.range.first)
                    val type = when { LABEL_FAX.containsMatchIn(before) -> "FAX"; LABEL_CELL.containsMatchIn(before) || num.replace(" ", "").let { it.startsWith("+491") || it.startsWith("01") } -> "CELL"; else -> "WORK" }
                    phones += type to num
                    used += l
                }
            }
            var postal: String? = null
            var city: String? = null
            var street: String? = null
            lines.forEachIndexed { i, l ->
                PLZ_CITY.find(l)?.let { m ->
                    postal = m.groupValues[2]; city = m.groupValues[3].trim()
                    val sameLine = l.substring(0, m.range.first).trim().trimEnd(',', '·', '|').trim()
                    street = sameLine.ifEmpty { lines.getOrNull(i - 1)?.takeIf { STREET.containsMatchIn(it) } }
                    used += l; street?.let { used += it }
                }
            }
            if (street == null) street = lines.firstOrNull { STREET.containsMatchIn(it) && it.any(Char::isDigit) && it !in used }?.also { used += it }

            val rest = lines.filter { l -> l !in used && !EMAIL.containsMatchIn(l) && !WEB.containsMatchIn(l) && l.count { it.isDigit() } < 3 }
            val company = rest.firstOrNull { COMPANY.containsMatchIn(it) }
            val title = rest.firstOrNull { it != company && TITLE.containsMatchIn(it) }
            val name = rest.firstOrNull { l ->
                val bare = ACADEMIC.replace(l, "")
                val words = bare.split(' ').filter { it.isNotBlank() }
                l != company && l != title && words.size in 2..4 && words.all { w -> w.first().isUpperCase() && w.all { it.isLetter() || it in "-.'" } }
            }
            return Contact(name, company, title, phones, emails, web, street, postal, city)
        }
    }
}
