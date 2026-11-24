package art.kquinn.kuevault

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTest {
    private fun item(name: String, section: Section, place: String, ms: Long, size: Long = 1) =
        Store.Item(name, name, size, ms, "", place, section)

    private val all = listOf(
        item("Tax return 2025.pdf", Section.DOCUMENTS, "g:a", 3, 500),
        item("Café receipt.jpg", Section.DOCUMENTS, "h:pc/vault", 2, 90),
        item("Holiday Kerry.jpg", Section.PHOTOS, "g:a", 5, 4000),
        item("Tax letter.docx", Section.DOCUMENTS, "h:pc/vault", 1, 20),
    )
    private val places = mapOf("g:a" to "a@gmail.com", "h:pc/vault" to "vault on PC")

    @Test
    fun everyWordTypedMustMatchSomewhere() {
        assertEquals(listOf("Tax return 2025.pdf"), Library.search(all, "tax pdf", null, places).map { it.name })
        assertEquals(listOf("Tax return 2025.pdf", "Tax letter.docx"), Library.search(all, "TAX", null, places).map { it.name })
    }

    @Test
    fun accentsAndPlacesAndSectionsCount() {
        assertEquals(listOf("Café receipt.jpg"), Library.search(all, "cafe", null, places).map { it.name })
        assertEquals(listOf("Café receipt.jpg", "Tax letter.docx"), Library.search(all, "pc", null, places).map { it.name })
        assertEquals(listOf("Holiday Kerry.jpg"), Library.search(all, "photos", null, places).map { it.name })
    }

    @Test
    fun oneSectionAndOrder() {
        assertEquals(listOf("Café receipt.jpg", "Tax letter.docx", "Tax return 2025.pdf"),
            Library.search(all, "", Section.DOCUMENTS, places, Library.Order.NAME).map { it.name })
        assertEquals("Holiday Kerry.jpg", Library.search(all, "", null, places, Library.Order.BIGGEST).first().name)
        assertEquals(4, Library.search(all, "  ", null, places).size)
    }
}
