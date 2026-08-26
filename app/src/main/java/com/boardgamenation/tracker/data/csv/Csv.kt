package com.boardgamenation.tracker.data.csv

import java.io.BufferedWriter
import java.util.Locale

/**
 * RFC 4180 CSV, written for spreadsheet interop.
 *
 * Three details matter and are easy to get wrong:
 *
 *  - **UTF-8 BOM.** Excel assumes the system codepage without it, and a collection full
 *    of accented designer names comes back mangled.
 *  - **CRLF line endings.** Required by RFC 4180 and expected by Excel.
 *  - **A `.` decimal separator regardless of locale.** Every number here is formatted
 *    with [Locale.ROOT], because a device set to a comma-decimal locale would otherwise
 *    produce files that re-import as garbage.
 */
object Csv {

    const val BOM = "﻿"
    const val CRLF = "\r\n"

    /** Quotes only when required, and doubles any embedded quote as the RFC specifies. */
    fun escape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value.first().isWhitespace() || value.last().isWhitespace()
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun formatDouble(value: Double?): String =
        value?.let { stripTrailingZeros(String.format(Locale.ROOT, "%.6f", it)) } ?: ""

    private fun stripTrailingZeros(text: String): String =
        if (!text.contains('.')) text else text.trimEnd('0').trimEnd('.')

    fun formatInt(value: Int?): String = value?.toString() ?: ""

    fun formatLong(value: Long?): String = value?.toString() ?: ""

    /** Booleans round-trip as 0/1, matching how SQLite stores them. */
    fun formatBool(value: Boolean): String = if (value) "1" else "0"

    fun row(values: List<String?>): String = values.joinToString(",") { escape(it) }
}

/** Streams rows out without ever holding the whole file in memory. */
class CsvWriter(private val out: BufferedWriter) {

    private var wroteHeader = false

    fun writeHeader(columns: List<String>) {
        out.write(Csv.BOM)
        out.write(Csv.row(columns))
        out.write(Csv.CRLF)
        wroteHeader = true
    }

    fun writeRow(values: List<String?>) {
        check(wroteHeader) { "Header must be written before any row" }
        out.write(Csv.row(values))
        out.write(Csv.CRLF)
    }

    fun flush() = out.flush()
}

/** A parse failure tied to the line it came from, so the report can point at it. */
data class CsvError(val line: Int, val message: String)

/** One parsed row, addressable by column name. */
class CsvRow(
    private val headers: Map<String, Int>,
    private val values: List<String>,
    val lineNumber: Int,
) {
    fun string(column: String): String? =
        headers[column]?.let { values.getOrNull(it) }?.takeIf { it.isNotEmpty() }

    fun requireString(column: String): String =
        string(column) ?: throw CsvFieldException(column, "is required")

    fun int(column: String): Int? = string(column)?.let {
        it.toIntOrNull() ?: throw CsvFieldException(column, "is not a whole number: $it")
    }

    fun long(column: String): Long? = string(column)?.let {
        it.toLongOrNull() ?: throw CsvFieldException(column, "is not a whole number: $it")
    }

    fun double(column: String): Double? = string(column)?.let {
        // Parsed with the invariant locale, matching how it was written.
        it.toDoubleOrNull() ?: throw CsvFieldException(column, "is not a number: $it")
    }

    /** Accepts 1/0 and true/false, because hand-edited files use both. */
    fun boolean(column: String, default: Boolean = false): Boolean {
        val raw = string(column) ?: return default
        return when (raw.lowercase(Locale.ROOT)) {
            "1", "true", "yes", "y" -> true
            "0", "false", "no", "n" -> false
            else -> throw CsvFieldException(column, "is not a true/false value: $raw")
        }
    }
}

class CsvFieldException(val column: String, val problem: String) :
    Exception("Column '$column' $problem")

/**
 * A streaming RFC 4180 parser.
 *
 * Written by hand rather than pulled in as a dependency because the format is small and
 * the awkward parts (quoted fields containing commas, embedded newlines, doubled quotes,
 * a stray BOM) are exactly the parts a general-purpose library would still need
 * configuring for.
 */
object CsvParser {

    /**
     * Splits one logical record. Returns null when the record is incomplete because a
     * quoted field is still open, which is how embedded newlines are handled: the caller
     * appends the next physical line and tries again.
     */
    fun parseLine(line: String): List<String>? {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val c = line[index]
            when {
                inQuotes && c == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    values += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            index++
        }

        if (inQuotes) return null
        values += current.toString()
        return values
    }

    /**
     * Reads a whole file into rows, joining physical lines that belong to one record.
     * Header names are trimmed and lower-cased so a file touched by a spreadsheet with
     * different capitalisation still imports.
     */
    fun parse(text: String): CsvTable {
        val cleaned = text.removePrefix(Csv.BOM)
        val physicalLines = cleaned.split(Csv.CRLF, "\n")
        val records = mutableListOf<Pair<Int, List<String>>>()
        val pending = StringBuilder()
        var pendingStartLine = 0

        physicalLines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            if (pending.isEmpty()) pendingStartLine = lineNumber else pending.append('\n')
            pending.append(line)
            val parsed = parseLine(pending.toString())
            if (parsed != null) {
                if (pending.isNotEmpty() || parsed.size > 1) {
                    records += pendingStartLine to parsed
                }
                pending.setLength(0)
            }
        }

        if (records.isEmpty()) return CsvTable(emptyList(), emptyList())

        val headerRow = records.first().second.map { it.trim().lowercase(Locale.ROOT) }
        val headerIndex = headerRow.withIndex().associate { (i, name) -> name to i }
        val dataRows = records.drop(1)
            // A trailing newline yields one empty field; that is not a row.
            .filterNot { (_, values) -> values.size == 1 && values.first().isBlank() }
            .map { (lineNumber, values) -> CsvRow(headerIndex, values, lineNumber) }

        return CsvTable(headerRow, dataRows)
    }
}

data class CsvTable(val headers: List<String>, val rows: List<CsvRow>) {

    /** Validates the shape before a single row is touched, as the spec requires. */
    fun missingColumns(required: List<String>): List<String> =
        required.filterNot { it.lowercase(Locale.ROOT) in headers }
}
