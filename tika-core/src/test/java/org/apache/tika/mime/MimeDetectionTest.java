/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.mime;

import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.commons.io.ByteOrderMark;
import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

public class MimeDetectionTest {

    private static MimeTypes MIME_TYPES;

    private static MediaTypeRegistry REGISTRY;

    /**
     * @inheritDoc
     */
    @BeforeAll
    public static void setUp() {
        MIME_TYPES = TikaConfig.getDefaultConfig().getMimeRepository();
        REGISTRY = MIME_TYPES.getMediaTypeRegistry();
    }

    @Test
    public void testDetection() throws Exception {
        testFile("image/svg+xml", "circles.svg");
        testFile("image/svg+xml", "circles-with-prefix.svg");
        testFile("image/png", "datamatrix.png");
        testFile("text/html", "test.html");
        testFile("application/xml", "test-iso-8859-1.xml");
        testFile("application/xml", "test-utf8.xml");
        testFile("application/xml", "test-utf8-bom.xml");
        testFile("application/xml", "test-utf16le.xml");
        testFile("application/xml", "test-utf16be.xml");
        testFile("application/xml", "test-long-comment.xml");
        testFile("application/xslt+xml", "stylesheet.xsl");
        testUrl("application/rdf+xml", "http://www.ai.sri.com/daml/services/owl-s/1.2/Process.owl",
                "test-difficult-rdf1.xml");
        testUrl("application/rdf+xml", "http://www.w3.org/2002/07/owl#", "test-difficult-rdf2.xml");
        // add evil test from TIKA-327
        testFile("text/html", "test-tika-327.html");
        // add another evil html test from TIKA-357
        testFile("text/html", "testlargerbuffer.html");
        // test fragment of HTML with <div> (TIKA-1102)
        testFile("text/html", "htmlfragment");
        // test fragment of HTML with <iframe> and potentially misleading file suffix
        testFile("text/html", "test-html-snippet-iframe.jsp");
        // test binary CGM detection (TIKA-1170)
        testFile("image/cgm", "plotutils-bin-cgm-v3.cgm");
        // test HTML detection of malformed file, previously identified as image/cgm (TIKA-1170)
        testFile("text/html", "test-malformed-header.html.bin");

        //test GCMD Directory Interchange Format (.dif) TIKA-1561
        testFile("application/dif+xml", "brwNIMS_2014.dif");

        // truncated xml should still be detected as xml, See TIKA-3596
        testFile("application/xml", "truncated-utf16-xml.xyz");
    }

    @Test
    public void testDetectionWithoutContent() throws IOException {
        testUrlWithoutContent("text/html", "test.html");
        testUrlWithoutContent("text/html", "http://test.com/test.html");
        testUrlWithoutContent("text/plain", "http://test.com/test.txt");

        // In case the url contains a filename referencing a server-side scripting language,
        // it gives us no clue concerning the actual mime type of the response
        testUrlWithoutContent("application/octet-stream", "http://test.com/test.php");
        testUrlWithoutContent("application/octet-stream", "http://test.com/test.cgi");
        testUrlWithoutContent("application/octet-stream", "http://test.com/test.jsp");
        // But in case the protocol is not http or https, the script is probably not interpreted
        testUrlWithoutContent("text/x-php", "ftp://test.com/test.php");
    }

    @Test
    public void testByteOrderMark() throws Exception {
        assertEquals(MediaType.TEXT_PLAIN, MIME_TYPES
                .detect(new ByteArrayInputStream("\ufefftest".getBytes(UTF_16LE)), new Metadata()));
        assertEquals(MediaType.TEXT_PLAIN, MIME_TYPES
                .detect(new ByteArrayInputStream("\ufefftest".getBytes(UTF_16BE)), new Metadata()));
        assertEquals(MediaType.TEXT_PLAIN, MIME_TYPES
                .detect(new ByteArrayInputStream("\ufefftest".getBytes(UTF_8)), new Metadata()));
    }

    @Test
    public void testRFC822WithBOM() throws Exception {
        String header = "From: blah <blah@blah.com>\r\n" + "Received: Friday, January 24, 2020 3:24 PM\r\n" +
                "To: someone@somewhere.com\r\n" + "Cc: someone-else@other.com\r\n" +
                "Subject: Received\r\n";
        MediaType rfc822 = MediaType.parse("message/rfc822");
        assertEquals(rfc822, MIME_TYPES.detect(UnsynchronizedByteArrayInputStream
                .builder()
                .setByteArray(header.getBytes(UTF_8))
                .get(), new Metadata()));

        int utfLength = ByteOrderMark.UTF_8.length();
        byte[] bytes = new byte[header.getBytes(UTF_8).length + utfLength];
        System.arraycopy(ByteOrderMark.UTF_8.getBytes(), 0, bytes, 0, utfLength);
        System.arraycopy(header.getBytes(UTF_8), 0, bytes, 3, header.getBytes(UTF_8).length);
        assertEquals(rfc822, MIME_TYPES.detect(UnsynchronizedByteArrayInputStream
                .builder()
                .setByteArray(bytes)
                .get(), new Metadata()));
    }

    @Test
    public void testSuperTypes() {
        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("text/something; charset=UTF-8"),
                MediaType.parse("text/something")));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("text/something; charset=UTF-8"),
                MediaType.TEXT_PLAIN));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("text/something; charset=UTF-8"),
                MediaType.OCTET_STREAM));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("text/something"),
                MediaType.TEXT_PLAIN));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("application/something+xml"),
                MediaType.APPLICATION_XML));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("application/something+zip"),
                MediaType.APPLICATION_ZIP));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("application/vnd.apple.iwork"),
                MediaType.APPLICATION_ZIP));

        assertTrue(REGISTRY.isSpecializationOf(MediaType.parse("application/json"),
                MediaType.TEXT_PLAIN));
    }

    @SuppressWarnings("unused")
    private void testUrlOnly(String expected, String url) throws IOException {
        InputStream in = new URL(url).openStream();
        testStream(expected, url, in);
    }

    private void testUrlWithoutContent(String expected, String url) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, url);
        String mime = this.MIME_TYPES.detect(null, metadata).toString();
        assertEquals(expected, mime,
                url + " is not properly detected using only resource name");
    }

    private void testUrl(String expected, String url, String file) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(file)) {
            testStream(expected, url, in);
        }
    }

    private void testFile(String expected, String filename) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(filename)) {
            testStream(expected, filename, in);
        }
    }

    private void testStream(String expected, String urlOrFileName, InputStream in)
            throws IOException {
        assertNotNull(in, "Test stream: [" + urlOrFileName + "] is null!");
        if (!in.markSupported()) {
            in = new java.io.BufferedInputStream(in);
        }
        try {
            Metadata metadata = new Metadata();
            String mime = this.MIME_TYPES.detect(in, metadata).toString();
            assertEquals(expected, mime,
                    urlOrFileName + " is not properly detected: detected.");

            //Add resource name and test again
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, urlOrFileName);
            mime = this.MIME_TYPES.detect(in, metadata).toString();
            assertEquals(expected, mime,
                    urlOrFileName + " is not properly detected after adding resource name.");
        } finally {
            in.close();
        }
    }

    /**
     * Test for type detection of empty documents.
     *
     * @see <a href="https://issues.apache.org/jira/browse/TIKA-483">TIKA-483</a>
     */
    @Test
    public void testEmptyDocument() throws IOException {
        assertEquals(MediaType.OCTET_STREAM,
                MIME_TYPES.detect(new ByteArrayInputStream(new byte[0]), new Metadata()));

        Metadata namehint = new Metadata();
        namehint.set(TikaCoreProperties.RESOURCE_NAME_KEY, "test.txt");
        assertEquals(MediaType.TEXT_PLAIN,
                MIME_TYPES.detect(new ByteArrayInputStream(new byte[0]), namehint));

        Metadata typehint = new Metadata();
        typehint.set(Metadata.CONTENT_TYPE, "text/plain");
        assertEquals(MediaType.TEXT_PLAIN,
                MIME_TYPES.detect(new ByteArrayInputStream(new byte[0]), typehint));

    }

    /**
     * Test for things like javascript files whose content is enclosed in XML
     * comment delimiters, but that aren't actually XML.
     *
     * @see <a href="https://issues.apache.org/jira/browse/TIKA-426">TIKA-426</a>
     */
    @Test
    public void testNotXML() throws IOException {
        assertEquals(MediaType.TEXT_PLAIN, MIME_TYPES
                .detect(new ByteArrayInputStream("<!-- test -->".getBytes(UTF_8)), new Metadata()));
    }

    /**
     * Tests that when we repeatedly test the detection of a document
     * that can be detected with Mime Magic, that we consistently
     * detect it correctly. See TIKA-391 for more details.
     */
    @Test
    public void testMimeMagicStability() throws IOException {
        for (int i = 0; i < 100; i++) {
            testFile("application/vnd.ms-excel", "test.xls");
        }
    }

    /**
     * Tests that when two magic matches both apply, and both
     * have the same priority, we use the name to pick the
     * right one based on the glob, or the first one we
     * come across if not. See TIKA-1292 for more details.
     */
    @Test
    public void testMimeMagicClashSamePriority() throws IOException {
        byte[] helloWorld = "Hello, World!".getBytes(UTF_8);
        MediaType helloType = MediaType.parse("hello/world-file");
        MediaType helloXType = MediaType.parse("hello/x-world-hello");
        Metadata metadata;

        // With a filename, picks the right one
        metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "test.hello.world");
        assertEquals(helloType, MIME_TYPES.detect(new ByteArrayInputStream(helloWorld), metadata));

        metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "test.x-hello-world");
        assertEquals(helloXType, MIME_TYPES.detect(new ByteArrayInputStream(helloWorld), metadata));

        // Without, goes for the one that sorts last
        metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "testingTESTINGtesting");
        assertEquals(helloXType, MIME_TYPES.detect(new ByteArrayInputStream(helloWorld), metadata));
    }

    /**
     * Test for TIKA-3771.
     */
    @Test
    public void testPNGWithSomeEmlHeaders() throws IOException {
        testFile("image/png", "test-pngNotEml.bin");
    }
}
