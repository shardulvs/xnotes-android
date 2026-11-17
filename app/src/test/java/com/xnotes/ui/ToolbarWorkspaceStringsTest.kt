package com.xnotes.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarWorkspaceStringsTest {
    private fun strings(directory: String): Map<String, String> {
        val file = File("src/main/res/$directory/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        val entries = (0 until nodes.length).map { nodes.item(it) }.map {
            it.attributes.getNamedItem("name").nodeValue to it.textContent
        }
        assertEquals("Duplicate resource names in $directory", entries.size, entries.map { it.first }.toSet().size)
        return entries.toMap().filterKeys { it.startsWith("toolbar_config_") || it.startsWith("toolbar_template_name_") }
    }

    @Test fun portugueseResourcesMatchEnglishKeysAndTypedPlaceholders() {
        val english = strings("values")
        val portuguese = strings("values-pt-rBR")
        assertTrue(english.isNotEmpty())
        assertEquals(english.keys, portuguese.keys)
        val placeholder = Regex("%[0-9]+\\$[ds]")
        english.forEach { (key, value) ->
            assertEquals(key, placeholder.findAll(value).map { it.value }.toList(),
                placeholder.findAll(portuguese.getValue(key)).map { it.value }.toList())
        }
        assertEquals("Workspace name", english["toolbar_config_workspace_name"])
        assertEquals("Nome do workspace", portuguese["toolbar_config_workspace_name"])
    }
}
