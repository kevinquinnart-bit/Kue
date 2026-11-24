package art.kquinn.kuevault

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads the words on a receipt, invoice, order email, warranty card or product label, and picks out what it
 * can: make, model, serial number, shop, the day it was bought, the price and the warranty. Everything it
 * finds is only a first guess shown on the form; the person checks it and changes what's wrong.
 */
object Reader {
    data class Guess(
        val name: String = "",
        val make: String = "",
        val model: String = "",
        val serial: String = "",
        val shop: String = "",
        val bought: LocalDate? = null,
        val price: String = "",
        val warrantyMonths: Int? = null,
    )

    /** Makers, as they are usually written. Matched as whole words, capitals not mattering. */
    val MAKES = listOf(
        "Samsung", "LG", "Sony", "Panasonic", "Philips", "Bosch", "Siemens", "Neff", "Miele", "AEG", "Beko", "Hotpoint",
        "Indesit", "Whirlpool", "Zanussi", "Electrolux", "Hisense", "TCL", "Toshiba", "Sharp", "Hitachi", "Dyson", "Shark",
        "Vax", "Hoover", "Henry", "Numatic", "Russell Hobbs", "Breville", "Morphy Richards", "Kenwood", "De'Longhi", "DeLonghi",
        "Nespresso", "Ninja", "Tefal", "Swan", "Sage", "KitchenAid", "Smeg", "Apple", "Google", "Huawei", "Xiaomi",
        "OnePlus", "Motorola", "Nokia", "Lenovo", "HP", "Dell", "Acer", "Asus", "MSI", "Microsoft", "Logitech", "Canon",
        "Nikon", "Fujifilm", "GoPro", "DJI", "Epson", "Brother", "Bose", "Sonos", "JBL", "Marshall", "Sennheiser",
        "Garmin", "Fitbit", "Amazon", "Roku", "Nintendo", "Black+Decker", "Black & Decker", "DeWalt", "Makita", "Ryobi",
        "Karcher", "Kärcher", "Flymo", "Worx", "Stihl", "Husqvarna", "Weber", "Vileda", "iRobot", "Roborock", "Eufy",
        "Ring", "Nest", "Hive", "Tado", "Worcester", "Baxi", "Vaillant", "Glow-worm", "Triton", "Mira", "Grohe",
        "Belling", "Stoves", "Rangemaster", "Candy", "Haier", "Fisher & Paykel", "Gorenje", "Montpellier", "Logik", "Bush",
        "Igenix", "Salter", "Tower", "Instant Pot", "Cosori", "Lakeland", "Oral-B", "Braun", "Remington", "BaByliss",
        "ghd", "Gtech", "Bissell", "Ecovacs", "Arlo", "Netgear", "TP-Link", "Synology", "Western Digital", "Seagate",
        "SanDisk", "Kindle", "Fire TV", "Xbox", "PlayStation", "Meta", "Oculus", "Polaroid", "Yamaha", "Denon", "Onkyo",
        "Pioneer", "Technics", "Bang & Olufsen", "Bowers & Wilkins", "Cambridge Audio", "Roberts", "Pure", "Ruark",
    )

    /** Shops, as they are usually written. */
    val SHOPS = listOf(
        "Currys", "Argos", "Amazon", "John Lewis", "AO.com", "AO", "Harvey Norman", "DID Electrical", "Power City", "Powercity",
        "Tesco", "Sainsbury's", "Asda", "Morrisons", "Lidl", "Aldi", "Costco", "Screwfix", "Toolstation", "B&Q", "Wickes",
        "Homebase", "Halfords", "Apple", "Samsung", "Dyson", "eBay", "Very", "Littlewoods", "Richer Sounds", "Peter Tyson",
        "Boots", "Superdrug", "Dunnes", "Woodies", "Harvey Norman", "Expert", "Euronics", "Smyths", "Game", "CEX", "Box.co.uk",
        "Scan", "Overclockers", "Ebuyer", "Laptops Direct", "Appliances Direct", "Marks & Spencer", "Next", "IKEA", "Dunelm",
        "The Range", "Wilko", "Lakeland", "Robert Dyas", "Selfridges", "Harrods", "Brown Thomas", "Arnotts", "Tesco Direct",
    )

    private val stopLines = Regex("(?i)\\b(vat|tax|tel|phone|fax|order|invoice|receipt|transaction|trans|card|auth|ref|account|acct|till|cashier|store\\s*no|postcode|barcode|ean|sku|item\\s*no)\\b")
    private val monthNames = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9,
        "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val numberWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "ten" to 10, "twelve" to 12)

    /** Everything [text] gives, read as of [today] (a day after it can't be when something was bought). */
    fun read(text: String, today: LocalDate = LocalDate.now()): Guess {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val make = makeIn(lines)
        val model = modelIn(lines, make)
        val name = nameIn(lines, make, model)
        return Guess(
            name = name,
            make = make,
            model = model,
            serial = serialIn(text),
            shop = shopIn(lines),
            bought = dateIn(lines, today),
            price = priceIn(lines),
            warrantyMonths = warrantyIn(text),
        )
    }

    private fun wordRegex(word: String) = Regex("(?i)(?<![A-Za-z0-9])" + Regex.escape(word) + "(?![A-Za-z0-9])")

    /** The maker named most often, the first named winning a tie. */
    fun makeIn(lines: List<String>): String {
        val all = lines.joinToString("\n")
        var best = ""
        var bestCount = 0
        var bestAt = Int.MAX_VALUE
        for (m in MAKES) {
            val found = wordRegex(m).findAll(all).toList()
            if (found.isEmpty()) continue
            // "Amazon" on an Amazon order names the shop, not the maker, unless it's an Amazon device.
            if (m == "Amazon" && !Regex("(?i)amazon\\s+(echo|kindle|fire|basics)").containsMatchIn(all)) continue
            if (m == "Apple" && found.size == 1 && Regex("(?i)apple\\s+(pay|store)").containsMatchIn(all)) continue
            val at = found.first().range.first
            if (found.size > bestCount || (found.size == bestCount && at < bestAt)) { best = m; bestCount = found.size; bestAt = at }
        }
        return when (best) { "DeLonghi" -> "De'Longhi"; "Kärcher" -> "Karcher"; "Black & Decker" -> "Black+Decker"; else -> best }
    }

    /** A model code: letters and numbers together, at least one of each, 4 to 20 long, and not a date or price. */
    private val code = Regex("(?<![A-Za-z0-9])([A-Z0-9][A-Z0-9\\-/.]{2,18}[A-Z0-9])(?![A-Za-z0-9])")

    internal fun looksLikeModel(t: String): Boolean {
        val clean = t.trim('.', '/', '-')
        if (clean.length < 4 || clean.length > 20) return false
        if (!clean.any { it.isDigit() } || !clean.any { it.isLetter() }) return false
        if (clean.count { it.isLetter() } < 1) return false
        // Postcodes (BT1 1AA, SW1A), sizes and units (500W, 32GB, 65INCH), years-and-letters like 2024A are not models.
        if (Regex("^[A-Z]{1,2}[0-9][A-Z0-9]?$").matches(clean)) return false
        if (Regex("(?i)^[0-9.]+(w|kw|v|gb|tb|mb|mah|kg|g|l|ml|mm|cm|m|in|inch|inches|hz|rpm|pk|pcs|x)$").matches(clean)) return false
        if (Regex("^[0-9]{1,2}[A-Z]{2}$").matches(clean)) return false
        return true
    }

    fun modelIn(lines: List<String>, make: String): String {
        val label = Regex("(?i)\\bmodel(?:\\s*(?:no|number|#|code))?\\.?\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/.]{2,24})")
        for (l in lines) label.find(l)?.groupValues?.get(1)?.trimEnd('.', ',')?.let { if (it.any(Char::isDigit)) return it.uppercase() }
        if (make.isNotEmpty()) {
            val m = wordRegex(make)
            for (l in lines) {
                val at = m.find(l) ?: continue
                val after = l.substring(at.range.last + 1).uppercase()
                code.findAll(after).map { it.groupValues[1] }.firstOrNull { looksLikeModel(it) }?.let { return it }
            }
        }
        for (l in lines) {
            if (stopLines.containsMatchIn(l)) continue
            code.findAll(l.uppercase()).map { it.groupValues[1] }.firstOrNull { looksLikeModel(it) && !it.contains('/') && it.any { c -> c.isLetter() } && it.count(Char::isDigit) >= 2 }?.let { return it }
        }
        return ""
    }

    private fun nameIn(lines: List<String>, make: String, model: String): String {
        if (make.isNotEmpty()) {
            val m = wordRegex(make)
            lines.firstOrNull { m.containsMatchIn(it) && !stopLines.containsMatchIn(it) }?.let { line ->
                val cut = line.replace(Regex("[£€$]\\s*[0-9][0-9,]*\\.[0-9]{2}"), "").replace(Regex("\\s+[0-9]+\\s*x\\s*$"), "").trim(' ', '-', ',', ':', '*')
                if (cut.length in 3..80) return cut
            }
            return listOf(make, model).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return model
    }

    fun serialIn(text: String): String =
        Regex("(?i)\\b(?:serial(?:\\s*(?:no|number|#))?|s/n|sn)\\.?\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\-]{4,30})").find(text)?.groupValues?.get(1)?.uppercase().orEmpty()

    fun shopIn(lines: List<String>): String {
        val top = lines.take(8).joinToString("\n")
        val all = lines.joinToString("\n")
        for (where in listOf(top, all)) {
            val hit = SHOPS.mapNotNull { s -> wordRegex(s).find(where)?.let { s to it.range.first } }.minByOrNull { it.second }
            if (hit != null) return when (hit.first) { "AO" -> "AO.com"; "Powercity" -> "Power City"; "Tesco Direct" -> "Tesco"; else -> hit.first }
        }
        return ""
    }

    /** Every day written in [line], day before month as in the UK and Ireland. */
    fun datesIn(line: String): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        fun year(y: String) = y.toInt().let { if (y.length == 2) 2000 + it else it }
        fun add(y: Int, m: Int, d: Int) { runCatching { LocalDate.of(y, m, d) }.getOrNull()?.let { out += it } }
        Regex("(?<![0-9])([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?![0-9])").findAll(line).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("(?<![0-9])([0-9]{1,2})[/.\\-]([0-9]{1,2})[/.\\-]([0-9]{4}|[0-9]{2})(?![0-9])").findAll(line).forEach {
            add(year(it.groupValues[3]), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        Regex("(?i)(?<![0-9])([0-9]{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec)[a-z]*\\.?,?\\s+([0-9]{4})").findAll(line).forEach {
            add(it.groupValues[3].toInt(), monthNames.getValue(it.groupValues[2].lowercase()), it.groupValues[1].toInt())
        }
        Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec)[a-z]*\\.?\\s+([0-9]{1,2})(?:st|nd|rd|th)?,?\\s+([0-9]{4})").findAll(line).forEach {
            add(it.groupValues[3].toInt(), monthNames.getValue(it.groupValues[1].lowercase()), it.groupValues[2].toInt())
        }
        return out
    }

    /** The day it was bought: a day on a line that says date or ordered first, else the first day written. */
    fun dateIn(lines: List<String>, today: LocalDate): LocalDate? {
        val ok = { d: LocalDate -> !d.isAfter(today) && d.year >= 1990 }
        val labelled = Regex("(?i)\\b(date|dated|ordered|order placed|purchased|bought|sold)\\b")
        lines.filter { labelled.containsMatchIn(it) && !Regex("(?i)\\b(delivery|deliver|dispatch|expires?|due)\\b").containsMatchIn(it) }
            .firstNotNullOfOrNull { l -> datesIn(l).firstOrNull(ok) }?.let { return it }
        return lines.firstNotNullOfOrNull { l -> datesIn(l).firstOrNull(ok) }
    }

    private val money = Regex("([£€$])?\\s*(?<![0-9.])([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\.([0-9]{2})(?![0-9])")

    /** What it cost: the amount on the total line (the paid or grand total first), else the biggest amount. */
    fun priceIn(lines: List<String>): String {
        val sign = Regex("[£€$]").find(lines.joinToString(" "))?.value ?: ""
        fun amounts(l: String) = money.findAll(l).map { (it.groupValues[1].ifEmpty { sign }) to (it.groupValues[2].replace(",", "") + "." + it.groupValues[3]) }.toList()
        val ranks = listOf(
            Regex("(?i)\\b(grand total|total paid|amount paid|total to pay|order total|total due|balance paid|you paid|paid)\\b"),
            Regex("(?i)\\btotal\\b(?!\\s*(vat|tax|saving|discount))"),
        )
        for (r in ranks) {
            for ((i, l) in lines.withIndex()) {
                if (!r.containsMatchIn(l) || Regex("(?i)sub\\s*-?total|vat total").containsMatchIn(l)) continue
                val here = amounts(l).ifEmpty { lines.getOrNull(i + 1)?.let { amounts(it) } ?: emptyList() }
                here.lastOrNull()?.let { return it.first + it.second }
            }
        }
        return lines.flatMap { amounts(it) }.maxByOrNull { it.second.toDouble() }?.let { it.first + it.second } ?: ""
    }

    /** "2 year guarantee", "5-year warranty", "24 months manufacturer's warranty", "three years' cover": in months. */
    fun warrantyIn(text: String): Int? {
        val r = Regex("(?i)\\b([0-9]{1,2}|one|two|three|four|five|six|seven|ten|twelve)\\s*[- ]?\\s*(years?|yrs?|months?)'?\\s*(?:'s\\s*)?(?:free\\s+|parts\\s*(?:and|&)\\s*labou?r\\s+|manufacturer'?s?\\s+|extended\\s+)?(?:warranty|guarantee|cover)")
        val m = r.find(text) ?: return null
        val n = m.groupValues[1].lowercase().let { numberWords[it] ?: it.toIntOrNull() } ?: return null
        val months = if (m.groupValues[2].lowercase().startsWith("m")) n else n * 12
        return months.takeIf { it in 1..240 }
    }

    // ------------------------------------------------------------------ Amazon's order history

    /** One thing bought on Amazon, from the order history Amazon gives through "Request your data". */
    data class Order(val name: String, val bought: LocalDate?, val price: String, val asin: String, val orderId: String, val site: String)

    /** Reads a CSV file: commas between, quotes around anything with a comma, quote or line break in it. */
    fun csv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.clear() }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.clear(); if (row.any { it.isNotEmpty() }) rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }

    /**
     * The things in Amazon's Retail.OrderHistory file, newest first. Cancelled orders and returns are left out,
     * and an order of several of the same thing shows once.
     */
    fun amazon(text: String): List<Order> {
        val rows = csv(text)
        val head = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
        fun at(name: String) = head.indexOf(name)
        val nameAt = at("product name").takeIf { it >= 0 } ?: at("title").takeIf { it >= 0 } ?: return emptyList()
        val dateAt = at("order date")
        val priceAt = listOf("unit price", "item total", "total owed").map { at(it) }.firstOrNull { it >= 0 } ?: -1
        val currencyAt = at("currency")
        val asinAt = listOf("asin", "asin/isbn").map { at(it) }.firstOrNull { it >= 0 } ?: -1
        val idAt = at("order id")
        val siteAt = at("website")
        val statusAt = at("order status")
        val seen = HashSet<String>()
        val out = ArrayList<Order>()
        for (r in rows.drop(1)) {
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val name = cell(nameAt)
            if (name.isEmpty() || name.equals("not available", true)) continue
            if (Regex("(?i)cancel").containsMatchIn(cell(statusAt))) continue
            val key = cell(idAt) + "|" + cell(asinAt) + "|" + name
            if (!seen.add(key)) continue
            val day = cell(dateAt).let { d ->
                runCatching { LocalDate.parse(d.take(10)) }.getOrNull()
                    ?: runCatching { LocalDate.parse(d, DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)) }.getOrNull()
                    ?: datesIn(d).firstOrNull()
            }
            val amount = cell(priceAt).replace(Regex("[^0-9.]"), "").toDoubleOrNull()
            val symbol = when (cell(currencyAt).uppercase()) { "GBP" -> "£"; "EUR" -> "€"; "USD" -> "$"; else -> "" }
            out += Order(name, day, amount?.let { symbol + String.format(Locale.UK, "%.2f", it) } ?: "", cell(asinAt), cell(idAt), cell(siteAt))
        }
        return out.sortedByDescending { it.bought ?: LocalDate.MIN }
    }

    /** A thing from an Amazon order: its maker and model read from the product name. */
    fun fromOrder(o: Order): Thing {
        val make = makeIn(listOf(o.name)).ifEmpty { o.name.split(' ').firstOrNull()?.takeIf { w -> w.length > 1 && w.first().isUpperCase() }.orEmpty() }
        val model = modelIn(listOf(o.name), make)
        val shop = if (o.site.contains(".ie", true)) "Amazon.ie" else if (o.site.contains(".co.uk", true)) "Amazon.co.uk" else "Amazon"
        return Thing(
            name = shortName(o.name), make = make, model = model, shop = shop, boughtDay = o.bought?.toEpochDay(), price = o.price,
            notes = listOfNotNull(o.orderId.takeIf { it.isNotEmpty() }?.let { "Amazon order $it" }, o.asin.takeIf { it.isNotEmpty() }?.let { "ASIN $it" }).joinToString(" · "),
        )
    }

    /** Amazon product names run long: the part before the first comma, bar or bracket, at most 70 letters. */
    fun shortName(name: String): String {
        val cut = name.split(Regex("\\s[|–-]\\s|,|\\(|\\[")).first().trim()
        return (if (cut.length >= 6) cut else name).take(70).trim()
    }
}
