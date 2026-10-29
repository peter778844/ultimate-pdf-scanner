package de.ultimatepdfscanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactTest {
    @Test
    fun readsTypicalGermanCard() {
        val c = Contact.parse("""
            Müller & Söhne GmbH
            Dr. Anna-Lena Schmidt
            Geschäftsführerin
            Hauptstraße 12a
            80331 München
            Tel. +49 89 123456-0
            Mobil 0171 2345678
            Fax +49 89 123456-99
            anna.schmidt@mueller-soehne.de
            www.mueller-soehne.de
        """.trimIndent())
        assertEquals("Dr. Anna-Lena Schmidt", c.name)
        assertEquals("Müller & Söhne GmbH", c.company)
        assertEquals("Geschäftsführerin", c.title)
        assertEquals(listOf("WORK", "CELL", "FAX"), c.phones.map { it.first })
        assertEquals("+49 89 123456-0", c.phones[0].second)
        assertEquals(listOf("anna.schmidt@mueller-soehne.de"), c.emails)
        assertEquals("www.mueller-soehne.de", c.web)
        assertEquals("Hauptstraße 12a", c.street)
        assertEquals("80331", c.postalCode)
        assertEquals("München", c.city)
        val v = c.toVCard()
        assertTrue("N:Schmidt;Dr. Anna-Lena;;;" in v, v)
        assertTrue("TEL;TYPE=CELL:0171 2345678" in v)
        assertTrue("ADR;TYPE=WORK:;;Hauptstraße 12a;München;;80331;" in v)
    }

    @Test
    fun addressOnOneLineAndNoTitle() {
        val c = Contact.parse("Jan Becker\nBecker Consulting\nLindenweg 3, 10115 Berlin\nT: 030 9876543\njan@becker.io")
        assertEquals("Jan Becker", c.name)
        assertEquals("Becker Consulting", c.company)
        assertEquals("Lindenweg 3", c.street)
        assertEquals("Berlin", c.city)
        assertEquals(listOf("WORK" to "030 9876543"), c.phones)
    }
}
