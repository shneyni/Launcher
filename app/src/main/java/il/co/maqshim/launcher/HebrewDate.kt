package il.co.maqshim.launcher

import android.icu.util.HebrewCalendar
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Formats a civil date as a complete Hebrew date, e.g. ט״ו באלול תשפ״ו. */
fun LocalDate.toHebrewDateString(): String {
    val calendar = HebrewCalendar(android.icu.util.TimeZone.getTimeZone("UTC"), Locale("he", "IL"))
    calendar.timeInMillis = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val year = calendar.get(HebrewCalendar.YEAR)
    val month = calendar.get(HebrewCalendar.MONTH)
    val day = calendar.get(HebrewCalendar.DAY_OF_MONTH)
    // The Hebrew calendar has a fixed 19-year cycle; Android ICU keeps the
    // missing common-year Adar-I slot in its month numbering.
    val leap = ((7 * year + 1) % 19) < 7
    val months = if (leap)
        listOf("תשרי", "חשוון", "כסלו", "טבת", "שבט", "אדר א׳", "אדר ב׳", "ניסן", "אייר", "סיוון", "תמוז", "אב", "אלול")
    else
        listOf("תשרי", "חשוון", "כסלו", "טבת", "שבט", "", "אדר", "ניסן", "אייר", "סיוון", "תמוז", "אב", "אלול")
    val monthName = months.getOrElse(month) { "" }
    val yearPart = (year % 1000).takeIf { it > 0 } ?: year
    return "${hebrewNumber(day)} ב$monthName ${hebrewNumber(yearPart)}"
}

private fun hebrewNumber(number: Int): String {
    require(number in 1..9999)
    var n = number % 1000
    val out = StringBuilder()
    while (n >= 400) { out.append('ת'); n -= 400 }
    if (n == 15) { out.append("טו"); n = 0 }
    else if (n == 16) { out.append("טז"); n = 0 }
    else {
        if (n >= 300) { out.append('ש'); n -= 300 }
        else if (n >= 200) { out.append("ר"); n -= 200 }
        else if (n >= 100) { out.append("ק"); n -= 100 }
        when (n / 10) {
            9 -> out.append('צ'); 8 -> out.append('פ'); 7 -> out.append('ע'); 6 -> out.append('ס')
            5 -> out.append('נ'); 4 -> out.append('מ'); 3 -> out.append('ל'); 2 -> out.append('כ'); 1 -> out.append('י')
        }
        when (n % 10) {
            9 -> out.append('ט'); 8 -> out.append('ח'); 7 -> out.append('ז'); 6 -> out.append('ו'); 5 -> out.append('ה')
            4 -> out.append('ד'); 3 -> out.append('ג'); 2 -> out.append('ב'); 1 -> out.append('א')
        }
    }
    if (out.length == 1) out.append('׳') else if (out.length > 1) out.insert(out.length - 1, '״')
    return out.toString()
}
