package com.xnotes.sync.nextcloud

import org.junit.Assert.assertEquals
import org.junit.Test
import org.kxml2.io.KXmlParser

class NextcloudDavTest {
    private val multistatus = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
 <d:response><d:href>/remote.php/dav/files/erik/Notes/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Tue, 03 Nov 2026 10:00:00 GMT</d:getlastmodified><d:getetag>"f1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/erik/Notes/My%20note%2B1.xnote</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength><d:getlastmodified>Tue, 03 Nov 2026 10:00:05 GMT</d:getlastmodified><d:getetag>"abc"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    @Test
    fun parsesFoldersFilesAndIgnoresEmptyMissingProps() {
        val e = NextcloudDav.parse(KXmlParser(), multistatus.byteInputStream())
        assertEquals(2, e.size)
        assertEquals(NextcloudDav.Entry("/remote.php/dav/files/erik/Notes/", true, 0, 1793700000000, "\"f1\""), e[0])
        assertEquals(NextcloudDav.Entry("/remote.php/dav/files/erik/Notes/My%20note%2B1.xnote", false, 1234, 1793700005000, "\"abc\""), e[1])
        assertEquals("My note+1.xnote", NextcloudDav.decode(e[1].href.substringAfterLast('/')))
        assertEquals("My%20note%2B1.xnote", NextcloudDav.encode("My note+1.xnote"))
    }
}
