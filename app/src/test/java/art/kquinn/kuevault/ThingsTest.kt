package art.kquinn.kuevault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ThingsTest {
    private val today = LocalDate.of(2026, 11, 24)

    @Test fun currysReceipt() {
        val text = """
            Currys
            PC World
            Belfast Boucher Road
            Tel 0344 561 0000
            VAT No 226 3201 70
            Date: 14/03/2025  Time 14:22
            BOSCH Serie 4 WAN28281GB Washing Machine   £449.00
            Care & Repair not taken
            SUBTOTAL £449.00
            TOTAL £449.00
            Paid by Visa  £449.00
            Your product comes with a 2 year manufacturer's guarantee
        """.trimIndent()
        val g = Reader.read(text, today)
        assertEquals("Bosch", g.make)
        assertEquals("WAN28281GB", g.model)
        assertEquals("Currys", g.shop)
        assertEquals(LocalDate.of(2025, 3, 14), g.bought)
        assertEquals("£449.00", g.price)
        assertEquals(24, g.warrantyMonths)
        assertTrue(g.name, g.name.contains("WAN28281GB"))
    }

    @Test fun argosOrderEmail() {
        val text = """
            Thanks for your order
            Argos
            Order number 1234567890
            Order placed: 2 November 2026
            Ninja Air Fryer AF100UK - 3.8L
            Quantity 1   £79.99
            Delivery £3.95
            Order total £83.94
            Delivery date 4 November 2026
        """.trimIndent()
        val g = Reader.read(text, today)
        assertEquals("Ninja", g.make)
        assertEquals("AF100UK", g.model)
        assertEquals("Argos", g.shop)
        assertEquals(LocalDate.of(2026, 11, 2), g.bought)
        assertEquals("£83.94", g.price)
        assertNull(g.warrantyMonths)
    }

    @Test fun productLabel() {
        val text = """
            DYSON
            Model: V15 Detect
            Type SV22
            Serial No: XA6-UK-MKA1234A
            25.2V 525W
            Made in Malaysia
        """.trimIndent()
        val g = Reader.read(text, today)
        assertEquals("Dyson", g.make)
        assertEquals("XA6-UK-MKA1234A", g.serial)
        // "V15" has letters and numbers; the labelled model wins over anything else on the label.
        assertEquals("V15", g.model)
        assertNull(g.bought)
    }

    @Test fun irishReceiptEuros() {
        val text = """
            Harvey Norman
            Swords
            Invoice 55512 Date 03-06-24
            Samsung QE55Q80C 55" QLED TV €1,099.00
            Grand Total €1,099.00
            Five year warranty included
        """.trimIndent()
        val g = Reader.read(text, today)
        assertEquals("Samsung", g.make)
        assertEquals("QE55Q80C", g.model)
        assertEquals("Harvey Norman", g.shop)
        assertEquals(LocalDate.of(2024, 6, 3), g.bought)
        assertEquals("€1099.00", g.price)
        assertEquals(60, g.warrantyMonths)
    }

    @Test fun datesAreDayFirstAndNeverInTheFuture() {
        assertEquals(listOf(LocalDate.of(2025, 2, 1)), Reader.datesIn("01/02/2025"))
        assertEquals(LocalDate.of(2025, 4, 5), Reader.dateIn(listOf("Delivery 01/01/2027", "5th April 2025"), today))
        assertEquals(LocalDate.of(2024, 12, 31), Reader.datesIn("2024-12-31T10:00:00Z").first())
        assertEquals(LocalDate.of(2025, 3, 12), Reader.datesIn("March 12, 2025").first())
    }

    @Test fun notModels() {
        assertTrue(!Reader.looksLikeModel("BT1"))
        assertTrue(!Reader.looksLikeModel("525W"))
        assertTrue(!Reader.looksLikeModel("32GB"))
        assertTrue(Reader.looksLikeModel("KE2600"))
    }

    @Test fun amazonOrderHistory() {
        val csv = "﻿" + """
            "Website","Order ID","Order Date","Purchase Order Number","Currency","Unit Price","Unit Price Tax","Shipping Charge","Total Discounts","Total Owed","Shipment Item Subtotal","Shipment Item Subtotal Tax","ASIN","Product Condition","Quantity","Payment Instrument Type","Order Status","Shipment Status","Ship Date","Shipping Option","Shipping Address","Billing Address","Carrier Name & Tracking Number","Product Name","Gift Message","Gift Sender Name","Gift Recipient Contact Details","Item Serial Number"
            "Amazon.co.uk","202-1111111-1111111","2025-01-10T09:12:33Z","Not Applicable","GBP","129.99","21.67","0","0","129.99","129.99","21.67","B0ABC12345","New","1","Visa","Closed","Shipped","2025-01-11T08:00:00Z","std-uk","1 Road, Town","1 Road, Town","Royal Mail(AB123)","Philips Series 5000 Electric Shaver S5887/30, Wet & Dry, with Pop-up Trimmer","Not Available","Not Available","Not Available","Not Available"
            "Amazon.co.uk","202-2222222-2222222","2025-06-01T09:12:33Z","Not Applicable","GBP","4.99","0.83","0","0","4.99","4.99","0.83","B0DEF67890","New","2","Visa","Closed","Shipped","2025-06-02T08:00:00Z","std-uk","1 Road, Town","1 Road, Town","Royal Mail(AB124)","AA Batteries, 12 pack","Not Available","Not Available","Not Available","Not Available"
            "Amazon.co.uk","202-3333333-3333333","2025-07-01T09:12:33Z","Not Applicable","GBP","59.00","9.83","0","0","59.00","59.00","9.83","B0XYZ00001","New","1","Visa","Cancelled","Not Available","Not Available","std-uk","1 Road, Town","1 Road, Town","Not Available","Something cancelled","Not Available","Not Available","Not Available","Not Available"
        """.trimIndent()
        val orders = Reader.amazon(csv)
        assertEquals(2, orders.size)
        assertEquals("AA Batteries", Reader.shortName(orders[0].name))
        val shaver = orders[1]
        assertEquals(LocalDate.of(2025, 1, 10), shaver.bought)
        assertEquals("£129.99", shaver.price)
        val t = Reader.fromOrder(shaver)
        assertEquals("Philips", t.make)
        assertEquals("S5887/30", t.model)
        assertEquals("Amazon.co.uk", t.shop)
        assertEquals("Philips Series 5000 Electric Shaver S5887/30", t.name)
        assertTrue(t.notes.contains("202-1111111-1111111"))
    }

    @Test fun csvQuotes() {
        val rows = Reader.csv("a,\"b, c\",\"say \"\"hi\"\"\"\r\n1,2,3\n")
        assertEquals(listOf("a", "b, c", "say \"hi\""), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test fun warrantyEndsAndCover() {
        val t = Thing(name = "Kettle", boughtDay = LocalDate.of(2025, 12, 10).toEpochDay(), warrantyMonths = 12)
        assertEquals(LocalDate.of(2026, 12, 9), t.warrantyEnds)
        assertEquals(Cover.ENDING_SOON, Things.cover(t, today))
        assertEquals(Cover.COVERED, Things.cover(t, LocalDate.of(2026, 10, 1)))
        assertEquals(Cover.ENDED, Things.cover(t, LocalDate.of(2026, 12, 10)))
        assertEquals(Cover.NONE_KNOWN, Things.cover(t.copy(warrantyMonths = null), today))
    }

    @Test fun remindersAt30And7DaysOnce() {
        val t = Thing(id = "k", name = "Kettle", boughtDay = LocalDate.of(2025, 12, 10).toEpochDay(), warrantyMonths = 12)
        val due = Reminders.due(listOf(t), emptySet(), today)
        assertEquals(15, due.single().second)
        val said = setOf(Reminders.mark(t, 15))
        assertTrue(Reminders.due(listOf(t), said, today).isEmpty())
        // At 7 days a second reminder comes.
        assertEquals(1, Reminders.due(listOf(t), said, LocalDate.of(2026, 12, 3)).size)
        assertTrue(Reminders.due(listOf(t), said, LocalDate.of(2026, 9, 1)).isEmpty())
    }

    @Test fun papersFoundAfterMoving() {
        val p = Paperwork(Paper.RECEIPT, "g:a@x.com", "old-id", "Kettle · Receipt.pdf", 1000, "application/pdf")
        val moved = Store.Item("KueVault\\My things\\Kettle · Receipt.pdf", "Kettle · Receipt.pdf", 1000, 1, "application/pdf", "h:pc/vault", Section.THINGS)
        assertEquals(moved, Things.find(p, listOf(moved)))
        assertNull(Things.find(p, listOf(moved.copy(size = 999))))
    }

    @Test fun fileNames() {
        val t = Thing(make = "Bosch", model = "WAN28281GB")
        assertEquals("Bosch WAN28281GB · Receipt.pdf", Things.fileName(t, Paper.RECEIPT, "pdf"))
        assertEquals("A b · Manual.pdf", Things.fileName(Thing(name = "A/b"), Paper.MANUAL, ".pdf"))
    }

    @Test fun searchThings() {
        val a = Thing(name = "Washing machine", make = "Bosch", shop = "Currys")
        val b = Thing(name = "Kettle", make = "Russell Hobbs", shop = "Argos")
        assertEquals(listOf(a), Things.search(listOf(a, b), "bosch currys"))
        assertEquals(listOf(b), Things.search(listOf(a, b), "russell"))
    }

    @Test fun askingTheManual() {
        val pages = listOf(
            "Safety\n\nRead all instructions before use. Keep away from children.",
            "Cleaning the filter\n\nRemove the filter by turning it anticlockwise. Rinse the filter under cold water and leave it to dry for 24 hours before putting it back.",
            "Error codes\n\nE15: water leak detected. Switch off at the socket and call service. E18: the drain pump is blocked; clean the pump filter.",
        )
        val passages = Manuals.split("t", "p", pages)
        val hit = Manuals.ask("how do I clean the filter", passages).first()
        assertEquals(2, hit.passage.page)
        assertEquals(3, Manuals.ask("what does error E15 mean", passages).first().passage.page)
        assertTrue(Manuals.ask("bluetooth pairing", passages).isEmpty())
    }

    @Test fun emailHtml() {
        val words = Scan.html("<html><style>p{}</style><p>Order total&nbsp;<b>&pound;20.00</b></p><br>Thanks</html>")
        assertTrue(words, words.contains("Order total £20.00"))
        assertTrue(!words.contains("p{}"))
    }
}
