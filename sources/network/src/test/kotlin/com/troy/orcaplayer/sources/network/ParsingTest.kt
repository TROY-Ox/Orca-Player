package com.troy.orcaplayer.sources.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {
    private val conn = ShareConnection(id = "abc123", type = ShareType.SMB, name = "NAS", host = "nas.local", path = "Media")

    @Test
    fun shareUrisRoundTrip() {
        val uri = ShareUris.build(conn, "/Films/My Movie (2020)/movie #1.mkv")
        assertEquals("smb://abc123/Films/My%20Movie%20%282020%29/movie%20%231.mkv", uri)
        assertEquals("abc123" to "/Films/My Movie (2020)/movie #1.mkv", ShareUris.parse(uri))
        assertNull(ShareUris.parse("https://example.com/a.mkv"))
        assertEquals("/Films", ShareUris.parentOf("/Films/Old", "/"))
        assertNull(ShareUris.parentOf("/Films", "/Films"))
    }

    @Test
    fun parsesWebDavMultistatus() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/Films/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/Films/Season%201/</d:href><d:propstat><d:prop><d:displayname>Season 1</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>http://host/dav/Films/a%20b.mp4</d:href>
                <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
                <d:propstat><d:prop><d:getcontentlength>1234</d:getcontentlength><d:getcontenttype>video/mp4</d:getcontenttype><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>"""
        val list = WebDavClient.parse(xml, "/dav/Films/")
        assertEquals(2, list.size)
        assertTrue(list[0].isFolder)
        assertEquals("Season 1", list[0].name)
        assertEquals("/dav/Films/Season 1", list[0].path)
        assertFalse(list[1].isFolder)
        assertEquals("a b.mp4", list[1].name)
        assertEquals(1234L, list[1].size)
        assertEquals("video/mp4", list[1].mimeType)
    }

    @Test
    fun parsesDidlBrowseReply() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<container id="64$1" parentID="64" childCount="3"><dc:title>Movies</dc:title><upnp:class>object.container</upnp:class></container>""" +
            """<item id="64$2" parentID="64"><dc:title>Clip</dc:title><res size="999" protocolInfo="http-get:*:video/x-matroska:*">http://192.168.1.2:8200/MediaItems/2.mkv</res></item>""" +
            """<item id="64$3" parentID="64"><dc:title>Cover</dc:title><res protocolInfo="http-get:*:image/jpeg:*">http://192.168.1.2:8200/3.jpg</res></item>""" +
            "</DIDL-Lite>"
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>$escaped</Result>""" +
            "<NumberReturned>3</NumberReturned><TotalMatches>3</TotalMatches><UpdateID>1</UpdateID></u:BrowseResponse></s:Body></s:Envelope>"
        val (entries, total) = DlnaClient.parseBrowse(soap)
        assertEquals(3, total)
        assertEquals(2, entries.size)
        assertTrue(entries[0].isFolder)
        assertEquals("64$1", entries[0].path)
        assertEquals("http://192.168.1.2:8200/MediaItems/2.mkv", entries[1].url)
        assertEquals(999L, entries[1].size)
    }

    @Test
    fun parsesDeviceDescription() {
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>Living room NAS</friendlyName>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/ctl/ContentDir</controlURL></service></serviceList>
            </device></root>"""
        val d = Ssdp.parseDescription(xml, "http://192.168.1.2:8200/rootDesc.xml")
        assertEquals("Living room NAS", d.name)
        assertEquals("http://192.168.1.2:8200/ctl/ContentDir", d.controlUrl)
        assertEquals("http://10.0.0.5:9000/desc.xml", Ssdp.locationOf("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://10.0.0.5:9000/desc.xml\r\n\r\n"))
    }
}
