package com.troy.orcaplayer.sources.network

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Small, namespace-aware DOM helpers for WebDAV and UPnP replies. Doctypes are refused. */
internal object Xml {
    fun parse(text: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(InputSource(StringReader(text.trimStart { it.code == 0xFEFF })))
    }

    fun Element.children(local: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nameOf() == local) out += n as Element
        }
        return out
    }

    fun Element.child(local: String): Element? = children(local).firstOrNull()

    fun Element.text(local: String): String? = child(local)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** Every element with this local name, at any depth. */
    fun Element.all(local: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    fun Document.all(local: String): List<Element> = documentElement?.let { root ->
        (if (root.nameOf() == local) listOf(root) else emptyList()) + root.all(local)
    } ?: emptyList()

    private fun Node.nameOf(): String = localName ?: nodeName.substringAfter(':')
}
