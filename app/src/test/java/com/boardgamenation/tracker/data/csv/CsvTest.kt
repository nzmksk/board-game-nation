package com.boardgamenation.tracker.data.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSV layer, held to RFC 4180 and to opening cleanly in a spreadsheet.
 */
class CsvTest {

    @Test
    fun `plain values are not quoted`() {
        assertEquals("Catan", Csv.escape("Catan"))
    }

    @Test
    fun `a value containing a comma is quoted`() {
        assertEquals("\"Wingspan, Oceania\"", Csv.escape("Wingspan, Oceania"))
    }

    @Test
    fun `a colon is not a reason to quote`() {
        assertEquals("Root: The Underworld", Csv.escape("Root: The Underworld"))
    }

    @Test
    fun `an embedded quote is doubled, as the RFC specifies`() {
        assertEquals("\"He said \"\"hello\"\"\"", Csv.escape("He said \"hello\""))
    }

    @Test
    fun `a value containing a newline is quoted`() {
        assertTrue(Csv.escape("line one\nline two").startsWith("\""))
    }

    /** Leading and trailing spaces are meaningful and would otherwise be trimmed. */
    @Test
    fun `surrounding whitespace is preserved by quoting`() {
        assertEquals("\"  padded  \"", Csv.escape("  padded  "))
    }

    @Test
    fun `null becomes an empty field`() {
        assertEquals("", Csv.escape(null))
    }

    /**
     * A device set to a comma-decimal locale would otherwise write "3,5" and produce a
     * file that re-imports as two columns.
     */
    @Test
    fun `numbers always use a dot decimal separator`() {
        assertEquals("3.5", Csv.formatDouble(3.5))
        assertEquals("42", Csv.formatDouble(42.0))
        assertEquals("", Csv.formatDouble(null))
    }

    @Test
    fun `booleans round trip as zero and one`() {
        assertEquals("1", Csv.formatBool(true))
        assertEquals("0", Csv.formatBool(false))
    }

    @Test
    fun `a simple line splits into fields`() {
        assertEquals(listOf("a", "b", "c"), CsvParser.parseLine("a,b,c"))
    }

    @Test
    fun `quoted fields keep their commas`() {
        assertEquals(
            listOf("Wingspan, Oceania", "2019"),
            CsvParser.parseLine("\"Wingspan, Oceania\",2019")
        )
    }

    @Test
    fun `doubled quotes decode back to one quote`() {
        assertEquals(listOf("He said \"hi\""), CsvParser.parseLine("\"He said \"\"hi\"\"\""))
    }

    @Test
    fun `an unterminated quote reports an incomplete record`() {
        // The caller appends the next physical line and retries; this is how a field
        // containing a newline is reassembled.
        assertNull(CsvParser.parseLine("\"unfinished"))
    }

    @Test
    fun `empty fields survive`() {
        assertEquals(listOf("a", "", "c"), CsvParser.parseLine("a,,c"))
    }

    @Test
    fun `a table parses with headers and typed accessors`() {
        val text = Csv.BOM + "id,title,price,owned" + Csv.CRLF +
            "1,Catan,120.5,1" + Csv.CRLF +
            "2,\"Wingspan, Oceania\",,0" + Csv.CRLF

        val table = CsvParser.parse(text)
        assertEquals(listOf("id", "title", "price", "owned"), table.headers)
        assertEquals(2, table.rows.size)

        val first = table.rows[0]
        assertEquals(1L, first.long("id"))
        assertEquals("Catan", first.string("title"))
        assertEquals(120.5, first.double("price")!!, 0.0001)
        assertTrue(first.boolean("owned"))

        val second = table.rows[1]
        assertEquals("Wingspan, Oceania", second.string("title"))
        assertNull(second.double("price"))
    }

    @Test
    fun `a byte order mark is stripped rather than absorbed into the first header`() {
        val table = CsvParser.parse(Csv.BOM + "id,title" + Csv.CRLF + "1,Catan" + Csv.CRLF)
        assertEquals("id", table.headers.first())
        assertEquals(1L, table.rows.first().long("id"))
    }

    @Test
    fun `headers are matched case insensitively`() {
        val table = CsvParser.parse("ID,Title" + Csv.CRLF + "1,Catan" + Csv.CRLF)
        assertEquals("Catan", table.rows.first().string("title"))
    }

    @Test
    fun `a trailing newline does not produce a phantom row`() {
        val table = CsvParser.parse("id,title" + Csv.CRLF + "1,Catan" + Csv.CRLF)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `a field containing a newline is reassembled across physical lines`() {
        val text = "id,notes" + Csv.CRLF + "1,\"first line" + Csv.CRLF + "second line\"" + Csv.CRLF
        val table = CsvParser.parse(text)
        assertEquals(1, table.rows.size)
        assertEquals("first line\nsecond line", table.rows.first().string("notes"))
    }

    @Test
    fun `line numbers are reported so errors can point at a row`() {
        val table = CsvParser.parse("id" + Csv.CRLF + "1" + Csv.CRLF + "2" + Csv.CRLF)
        assertEquals(2, table.rows[0].lineNumber)
        assertEquals(3, table.rows[1].lineNumber)
    }

    @Test
    fun `a bad number names the column it came from`() {
        val table = CsvParser.parse("id,price" + Csv.CRLF + "1,not-a-number" + Csv.CRLF)
        val error = runCatching { table.rows.first().double("price") }.exceptionOrNull()
        assertTrue(error is CsvFieldException)
        assertEquals("price", (error as CsvFieldException).column)
    }

    @Test
    fun `a missing required column is reported before anything is imported`() {
        val table = CsvParser.parse("id,title" + Csv.CRLF)
        assertEquals(listOf("date_added"), table.missingColumns(listOf("title", "date_added")))
    }

    @Test
    fun `a full row survives a write and read round trip`() {
        val values = listOf("Wingspan, Oceania", "He said \"hi\"", "  spaced  ", null, "plain")
        val line = Csv.row(values)
        val parsed = CsvParser.parseLine(line)
        assertEquals(listOf("Wingspan, Oceania", "He said \"hi\"", "  spaced  ", "", "plain"), parsed)
    }
}
