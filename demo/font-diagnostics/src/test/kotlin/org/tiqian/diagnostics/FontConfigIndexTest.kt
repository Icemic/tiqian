package org.tiqian.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FontConfigIndexTest {
    @Test
    fun parsesNamedAliasesUnnamedFallbacksAndAxes() {
        val xml = """
            <familyset version='23'>
              <family name='sans-serif' lang='en,zh-Hans' variant='compact'>
                <font weight='400' style='normal' index='2'>Noto.ttc</font>
                <font weight='700' fallbackFor='serif'>
                  Variable.ttf
                  <axis tag='wght' stylevalue='700'/>
                  <axis tag='wdth' stylevalue='100'/>
                </font>
              </family>
              <family lang='zh-Hans'>
                <font postScriptName='OEMSansSC'>OEMSans.ttf</font>
              </family>
              <alias name='sans-serif-medium' to='sans-serif' weight='500'/>
            </familyset>
        """.trimIndent()

        val index = FontConfigIndexParser.parse(xml.toByteArray())

        assertEquals("familyset", index.rootElement)
        assertEquals("23", index.version)
        assertEquals(listOf("sans-serif", "sans-serif-medium"), index.declaredNames)
        assertEquals(2, index.families.size)
        assertEquals(listOf("en", "zh-Hans"), index.families[0].languages)
        assertEquals(2, index.families[0].fonts[0].ttcIndex)
        assertEquals(mapOf("wdth" to 100f, "wght" to 700f), index.families[0].fonts[1].axes)
        assertNull(index.families[1].names.singleOrNull())
        assertEquals("OEMSansSC", index.families[1].fonts.single().postScriptName)
        assertEquals("sans-serif", index.aliases.single().to)
    }

    @Test
    fun parsesLegacyNameAndFileSets() {
        val xml = """
            <familyset>
              <family>
                <nameset><name>sans-serif</name><name>arial</name></nameset>
                <fileset><file>Roboto-Regular.ttf</file></fileset>
              </family>
            </familyset>
        """.trimIndent()

        val index = FontConfigIndexParser.parse(xml.toByteArray())

        assertEquals(listOf("sans-serif", "arial"), index.families.single().names)
        assertEquals("Roboto-Regular.ttf", index.families.single().fonts.single().fileName)
    }
}
