package org.tiqian.shaping.nativefont

import org.tiqian.font.FontRole
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LegacyFontConfigParserTest {
    @Test
    fun retainsNamedAndOrderedLanguageFallbackFamilies() {
        val file = Files.createTempFile("tiqian-fonts", ".xml")
        file.writeText(
            """
            <familyset version="23">
              <family name="sans-serif">
                <font weight="400">Primary.ttf</font>
                <font weight="700" style="italic">Primary-BoldItalic.ttf</font>
              </family>
              <family lang="zh-Hans">
                <font index="2">Cjk.ttc</font>
              </family>
              <family lang="zh">
                <font>MiSansL3.otf</font>
              </family>
              <family>
                <font>RareHan.ttf</font>
              </family>
            </familyset>
            """.trimIndent(),
        )

        val parsed = LegacyFontConfigParser.parse(file.toFile())

        assertEquals(listOf("sans-serif"), parsed.families[0].names)
        assertEquals(listOf("zh-Hans"), parsed.families[1].languages)
        assertEquals(2, parsed.families[1].fonts.single().collectionIndex)
        assertEquals(listOf("zh"), parsed.families[2].languages)
        assertEquals("MiSansL3.otf", parsed.families[2].fonts.single().fileName)
        assertEquals("RareHan.ttf", parsed.families[3].fonts.single().fileName)
        assertEquals(700, parsed.families[0].fonts[1].weight)
        assertEquals(true, parsed.families[0].fonts[1].italic)
    }

    @Test
    fun plainZhRareCharacterFamilyRemainsInSimplifiedHanFallbackOrder() {
        val directory = Files.createTempDirectory("tiqian-font-config")
        val config = directory.resolve("fonts.xml")
        config.writeText(
            """
            <familyset version="23">
              <family name="sans-serif"><font>Primary.ttf</font></family>
              <family lang="zh-Hans"><font>MiSansVF.ttf</font></family>
              <family lang="zh"><font>MiSansL3.otf</font></family>
              <family lang="zh-Hant"><font>Traditional.ttf</font></family>
            </familyset>
            """.trimIndent(),
        )
        listOf("Primary.ttf", "MiSansVF.ttf", "MiSansL3.otf", "Traditional.ttf")
            .forEach { Files.createFile(directory.resolve(it)) }

        val catalog = assertNotNull(
            DeclaredSystemFontConfigCatalog.create(
                configFiles = listOf(config.toFile()),
                fontDirectory = directory.toFile(),
            ),
        )

        assertEquals(
            listOf("declared-cjk-1", "declared-cjk-2"),
            catalog.fallbackChains.getValue(FontRole.CjkText),
        )
        assertEquals(
            listOf("MiSansVF.ttf", "MiSansL3.otf"),
            catalog.faceSpecs
                .filter { it.familyKey in catalog.fallbackChains.getValue(FontRole.CjkText) }
                .map { it.source.label.substringAfterLast('/') },
        )
    }
}
