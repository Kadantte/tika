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
package org.apache.tika.parser.microsoft;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.poi.common.usermodel.Hyperlink;
import org.apache.poi.hslf.exceptions.EncryptedPowerPointFileException;
import org.apache.poi.hslf.model.HeadersFooters;
import org.apache.poi.hslf.record.DocInfoListContainer;
import org.apache.poi.hslf.record.RecordContainer;
import org.apache.poi.hslf.record.RecordTypes;
import org.apache.poi.hslf.record.VBAInfoAtom;
import org.apache.poi.hslf.record.VBAInfoContainer;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFMasterSheet;
import org.apache.poi.hslf.usermodel.HSLFNotes;
import org.apache.poi.hslf.usermodel.HSLFObjectData;
import org.apache.poi.hslf.usermodel.HSLFObjectShape;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTableCell;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.Comment;
import org.apache.poi.sl.usermodel.ShapeContainer;
import org.apache.poi.sl.usermodel.SimpleShape;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.StringUtils;

public class HSLFExtractor extends AbstractPOIFSExtractor {

    //This is from Andreas: https://stackoverflow.com/a/45664920
    private static final int[] TIMING_RECORD_PATH = {
            RecordTypes.ProgTags.typeID,
            RecordTypes.ProgBinaryTag.typeID,
            RecordTypes.BinaryTagData.typeID
    };

    private static final int EXT_TIME_NODE_CONTAINER = 0xf144;

    public HSLFExtractor(ParseContext context, Metadata metadata) {
        super(context, metadata);
    }
    private final Set<Integer> processedEmbeddedObjects = new HashSet<>();

    // remove trailing paragraph break
    private static String removePBreak(String fragment) {
        // the last text run of a text paragraph contains the paragraph break (\r)
        // line breaks (\\u000b) can happen more often
        return fragment.replaceFirst("\\r$", "");
    }

    protected void parse(POIFSFileSystem filesystem, XHTMLContentHandler xhtml)
            throws IOException, SAXException, TikaException {
        parse(filesystem.getRoot(), xhtml);
    }

    protected void parse(DirectoryNode root, XHTMLContentHandler xhtml)
            throws IOException, SAXException, TikaException {
        List<HSLFSlide> _slides;

        try (HSLFSlideShow ss = new HSLFSlideShow(root)) {
            _slides = ss.getSlides();

            xhtml.startElement("div", "class", "slideShow");

            /* Iterate over slides and extract text */
            int hiddenSlides = 0;
            Set<String> commentAuthors = new HashSet<>();
            for (HSLFSlide slide : _slides) {
                xhtml.startElement("div", "class", "slide");
                HeadersFooters slideHeaderFooters =
                        (officeParserConfig.isIncludeHeadersAndFooters()) ? slide.getHeadersFooters() :
                                null;

                HeadersFooters notesHeadersFooters = (officeParserConfig.isIncludeHeadersAndFooters()) ?
                        ss.getNotesHeadersFooters() : null;

                if (officeParserConfig.isIncludeHeadersAndFooters()) {
                    // Slide header, if present
                    if (slideHeaderFooters != null && slideHeaderFooters.isHeaderVisible() &&
                            slideHeaderFooters.getHeaderText() != null) {
                        xhtml.startElement("p", "class", "slide-header");

                        xhtml.characters(slideHeaderFooters.getHeaderText());

                        xhtml.endElement("p");
                    }
                }

                // Slide master, if present
                if (officeParserConfig.isIncludeSlideMasterContent()) {
                    extractMaster(xhtml, slide.getMasterSheet());
                }
                // Slide text
                xhtml.startElement("div", "class", "slide-content");
                textRunsToText(xhtml, slide.getTextParagraphs());

                // Table text
                List<HSLFShape> shapes = getShapes(slide);
                if (shapes != null) {
                    for (HSLFShape shape : shapes) {
                        if (shape instanceof HSLFTable) {
                            extractTableText(xhtml, (HSLFTable) shape);
                        }
                    }
                }
                extractGroupText(xhtml, slide, 0);
                //end slide content
                xhtml.endElement("div");

                if (officeParserConfig.isIncludeHeadersAndFooters()) {
                    // Slide footer, if present
                    if (slideHeaderFooters != null && slideHeaderFooters.isFooterVisible() &&
                            slideHeaderFooters.getFooterText() != null) {
                        xhtml.startElement("p", "class", "slide-footer");
                        xhtml.characters(slideHeaderFooters.getFooterText());
                        xhtml.endElement("p");
                    }
                }
                handleComments(slide, xhtml, commentAuthors);
                handleNotes(slide, notesHeadersFooters, xhtml);

                // Now any embedded resources
                handleSlideEmbeddedResources(slide, xhtml);

                // Slide complete
                xhtml.endElement("div");
                if (slide.isHidden()) {
                    hiddenSlides++;
                }
                findAnimations(slide);
            }
            if (hiddenSlides > 0) {
                parentMetadata.set(Office.NUM_HIDDEN_SLIDES, hiddenSlides);
                parentMetadata.set(Office.HAS_HIDDEN_SLIDES, true);
            }
            handleSlideEmbeddedPictures(ss, xhtml);
            handleShowEmbeddedResources(ss, xhtml, true);

            if (officeParserConfig.isExtractMacros()) {
                extractMacros(ss, xhtml);
            }
        } catch (EncryptedPowerPointFileException e) {
            throw new EncryptedDocumentException(e);
        }
        // All slides done
        xhtml.endElement("div");
    }

    private void findAnimations(HSLFSlide slide) {
        if (parentMetadata.get(Office.HAS_ANIMATIONS) != null) {
            return;
        }
        RecordContainer lastRecord = slide.getSheetContainer();
        for (int ri : TIMING_RECORD_PATH) {
            if (lastRecord == null) {
                return;
            }
            lastRecord = (RecordContainer) lastRecord.findFirstOfType(ri);

        }

        if (lastRecord != null && lastRecord.findFirstOfType(EXT_TIME_NODE_CONTAINER) != null) {
            parentMetadata.set(Office.HAS_ANIMATIONS, true);
        }
    }

    /**
     * This is the catch-all for embedded objects.  If we didn't come across
     * them in the shapes in the slides, headers/footers, etc, try to
     * extract them here.
     **/
    private void handleShowEmbeddedResources(HSLFSlideShow ss, XHTMLContentHandler xhtml,
                                             boolean outputHtml)
            throws SAXException {
        
        HSLFObjectData[] objectData = ss.getEmbeddedObjects();
        int i = 0;
        for (HSLFObjectData d : objectData) {
            if (processedEmbeddedObjects.contains(d.getExOleObjStg().getPersistId())) {
                continue;
            }
            String filename = d.getFileName();
            if (StringUtils.isBlank(filename)) {
                filename = "UNKNOWN-" + i;
            }
            try (TikaInputStream tis = TikaInputStream.get(d.getInputStream())) {
                if (FileMagic.valueOf(tis) == FileMagic.OLE2) {
                    try (POIFSFileSystem pfs = new POIFSFileSystem(tis)) {
                        //coz ppts can have empty pfs...shrug...
                        //we may need to add more stringent requirements
                        if (pfs.getRoot().getEntryNames().size() < 1) {
                            return;
                        }
                        handleEmbeddedOfficeDoc(pfs.getRoot(), filename, xhtml, outputHtml);
                    }
                } else {
                    boolean shouldProcess = false;
                    try {
                        tis.mark(1);
                        int b = tis.read();
                        shouldProcess = b > -1;
                    } finally {
                        tis.reset();
                    }
                    if (shouldProcess) {
                        handleEmbeddedResource(tis, filename, null, null, xhtml, true);
                    }
                }
            } catch (IOException | TikaException e) {
                EmbeddedDocumentUtil.recordException(e, parentMetadata);
            }
            i++;
        }
    }

    private void handleComments(HSLFSlide slide, XHTMLContentHandler xhtml, Set<String> commentAuthors) throws SAXException {
        if (slide.getComments() == null || slide.getComments().isEmpty()) {
            return;
        }

        xhtml.startElement("div", "class", "slide-comments");

        // Comments, if present
        StringBuilder authorStringBuilder = new StringBuilder();
        for (Comment comment : slide.getComments()) {
            authorStringBuilder.setLength(0);
            xhtml.startElement("p", "class", "slide-comment");

            if (! StringUtils.isBlank(comment.getAuthor())) {
                String author = comment.getAuthor();
                authorStringBuilder.append(author);
                if (! commentAuthors.contains(author)) {
                    parentMetadata.add(Office.COMMENT_PERSONS, comment.getAuthor());
                    commentAuthors.add(author);
                }
            }
            if (comment.getAuthorInitials() != null) {
                if (! authorStringBuilder.isEmpty()) {
                    authorStringBuilder.append(" ");
                }
                authorStringBuilder.append("(").append(comment.getAuthorInitials()).append(")");
            }
            if (! authorStringBuilder.isEmpty()) {
                if (comment.getText() != null) {
                    authorStringBuilder.append(" - ");
                }
                xhtml.startElement("b");
                xhtml.characters(authorStringBuilder.toString());
                xhtml.endElement("b");
            }
            if (comment.getText() != null) {
                xhtml.characters(comment.getText());
            }
            xhtml.endElement("p");
        }
        //end comments
        xhtml.endElement("div");
    }

    private void handleNotes(HSLFSlide slide, HeadersFooters notesHeaderFooters,
                             XHTMLContentHandler xhtml)
            throws SAXException, TikaException, IOException {

        if (!officeParserConfig.isIncludeSlideNotes()) {
            return;
        }
        // Find the Notes for this slide and extract inline
        HSLFNotes notes = slide.getNotes();
        if (notes == null) {
            return;
        }

        xhtml.startElement("div", "class", "notes");

        // Repeat the Notes header, if set
        if (officeParserConfig.isIncludeHeadersAndFooters() && notesHeaderFooters != null &&
                notesHeaderFooters.isHeaderVisible() &&
                notesHeaderFooters.getHeaderText() != null) {
            xhtml.startElement("p", "class", "slide-note-header");
            xhtml.characters(notesHeaderFooters.getHeaderText());
            xhtml.endElement("p");
        }
        xhtml.startElement("div", "class", "notes-content");
        // Notes text
        textRunsToText(xhtml, notes.getTextParagraphs());

        List<HSLFShape> shapes = getShapes(notes);
        if (shapes != null && shapes.size() > 0) {
            // Table text
            for (HSLFShape shape : shapes) {
                if (shape instanceof HSLFTable) {
                    extractTableText(xhtml, (HSLFTable) shape);
                }
            }
        }
        extractGroupText(xhtml, notes, 0);
        //notes content
        xhtml.endElement("div");

        // Repeat the Notes footer, if set
        if (officeParserConfig.isIncludeHeadersAndFooters() && notesHeaderFooters != null &&
                notesHeaderFooters.isFooterVisible() &&
                notesHeaderFooters.getFooterText() != null) {
            xhtml.startElement("p", "class", "slide-note-footer");
            xhtml.characters(notesHeaderFooters.getFooterText());
            xhtml.endElement("p");
        }
        // Now any embedded resources
        handleSlideEmbeddedResources(notes, xhtml);
        //end notes
        xhtml.endElement("div");
    }

    //Extract any text that's within an HSLFTextShape that's a descendant of
    //an HSLFGroupShape.
    private void extractGroupText(XHTMLContentHandler xhtml, ShapeContainer shapeContainer,
                                  int depth) throws SAXException {
        List<HSLFShape> shapes = getShapes(shapeContainer);
        if (shapes == null) {
            return;
        }

        //Only process items with depth > 0 because they should have been included
        //already in slide.getTextParagraphs above.

        //However, cells are considered grouped within the table, so ignore them.
        //I don't believe that cells can be inside a text box or other
        //grouped text containing object, so always ignore them.
        //I also don't believe that a table can be grouped with a table.
        //If these beliefs are wrong...must fix!
        List<List<HSLFTextParagraph>> paragraphList = new ArrayList<>();
        for (HSLFShape shape : shapes) {
            if (shape instanceof HSLFGroupShape) {
                //work recursively, HSLFGroupShape can contain HSLFGroupShape
                extractGroupText(xhtml, ((HSLFGroupShape) shape), depth + 1);
            } else if (shape instanceof HSLFTextShape && !(shape instanceof HSLFTableCell) &&
                    depth > 0) {
                paragraphList.add(((HSLFTextShape) shape).getTextParagraphs());
            }
        }
        textRunsToText(xhtml, paragraphList);
    }

    private void extractMacros(HSLFSlideShow ppt, XHTMLContentHandler xhtml) {

        //get macro persist id
        DocInfoListContainer list = (DocInfoListContainer) ppt.getDocumentRecord()
                .findFirstOfType(RecordTypes.List.typeID);
        if (list == null) {
            return;
        }
        VBAInfoContainer vbaInfo =
                (VBAInfoContainer) list.findFirstOfType(RecordTypes.VBAInfo.typeID);
        if (vbaInfo == null) {
            return;
        }
        VBAInfoAtom vbaAtom = (VBAInfoAtom) vbaInfo.findFirstOfType(RecordTypes.VBAInfoAtom.typeID);
        if (vbaAtom == null) {
            return;
        }
        long persistId = vbaAtom.getPersistIdRef();
        for (HSLFObjectData objData : ppt.getEmbeddedObjects()) {
            if (objData.getExOleObjStg().getPersistId() == persistId) {
                try (POIFSFileSystem poifsFileSystem = new POIFSFileSystem(
                        objData.getInputStream())) {
                    try {
                        OfficeParser.extractMacros(poifsFileSystem, xhtml,
                                EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context));
                    } catch (IOException | SAXException inner) {
                        EmbeddedDocumentUtil.recordException(inner, parentMetadata);
                    }
                } catch (IOException e) {
                    EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);//swallow
                }
            }
        }

    }

    private void extractMaster(XHTMLContentHandler xhtml, HSLFMasterSheet master)
            throws SAXException {
        if (master == null) {
            return;
        }
        List<HSLFShape> shapes = getShapes(master);
        if (shapes == null || shapes.isEmpty()) {
            return;
        }

        xhtml.startElement("div", "class", "slide-master-content");
        for (HSLFShape shape : shapes) {
            if (shape != null && !isPlaceholder(shape)) {
                if (shape instanceof HSLFTextShape) {
                    HSLFTextShape tsh = (HSLFTextShape) shape;
                    String text = tsh.getText();
                    if (text != null) {
                        xhtml.element("p", text);
                    }
                }
            }
        }
        xhtml.endElement("div");
    }

    private boolean isPlaceholder(HSLFShape shape) {
        return shape instanceof SimpleShape && ((SimpleShape) shape).isPlaceholder();
    }

    private void extractTableText(XHTMLContentHandler xhtml, HSLFTable shape) throws SAXException {
        xhtml.startElement("table");
        for (int row = 0; row < shape.getNumberOfRows(); row++) {
            xhtml.startElement("tr");
            for (int col = 0; col < shape.getNumberOfColumns(); col++) {
                HSLFTableCell cell = shape.getCell(row, col);
                //insert empty string for empty cell if cell is null
                String txt = "";
                if (cell != null) {
                    txt = cell.getText();
                }
                xhtml.element("td", txt);
            }
            xhtml.endElement("tr");
        }
        xhtml.endElement("table");
    }

    private void textRunsToText(XHTMLContentHandler xhtml,
                                List<List<HSLFTextParagraph>> paragraphsList) throws SAXException {
        if (paragraphsList == null) {
            return;
        }

        for (List<HSLFTextParagraph> run : paragraphsList) {
            // Leaving in wisdom from TIKA-712 for easy revert.
            // Avoid boiler-plate text on the master slide (0
            // = TextHeaderAtom.TITLE_TYPE, 1 = TextHeaderAtom.BODY_TYPE):
            //if (!isMaster || (run.getRunType() != 0 && run.getRunType() != 1)) {

            boolean isBullet = false;
            for (HSLFTextParagraph htp : run) {
                boolean nextBullet = htp.isBullet();
                // TODO: identify bullet/list type
                if (isBullet != nextBullet) {
                    isBullet = nextBullet;
                    if (isBullet) {
                        xhtml.startElement("ul");
                    } else {
                        xhtml.endElement("ul");
                    }
                }

                List<HSLFTextRun> textRuns = htp.getTextRuns();
                String firstLine = removePBreak(textRuns.get(0).getRawText());
                boolean showBullet = (isBullet && (textRuns.size() > 1 || !"".equals(firstLine)));
                String paraTag = showBullet ? "li" : "p";

                xhtml.startElement(paraTag);
                boolean runIsHyperLink = false;
                for (HSLFTextRun htr : textRuns) {
                    Hyperlink link = htr.getHyperlink();
                    if (link != null) {
                        String address = link.getAddress();
                        if (address != null && !address.startsWith("_ftn")) {
                            xhtml.startElement("a", "href", link.getAddress());
                            runIsHyperLink = true;
                        }
                    }
                    String line = htr.getRawText();
                    if (line != null) {
                        boolean isfirst = true;
                        for (String fragment : line.split("\\u000b")) {
                            if (!isfirst) {
                                xhtml.startElement("br");
                                xhtml.endElement("br");
                            }
                            isfirst = false;
                            xhtml.characters(removePBreak(fragment));
                        }
                        if (line.endsWith("\u000b")) {
                            xhtml.startElement("br");
                            xhtml.endElement("br");
                        }
                    }
                    if (runIsHyperLink) {
                        xhtml.endElement("a");
                    }
                    runIsHyperLink = false;
                }
                xhtml.endElement(paraTag);
            }
            if (isBullet) {
                xhtml.endElement("ul");
            }
        }
    }

    private void handleSlideEmbeddedPictures(HSLFSlideShow slideshow, XHTMLContentHandler xhtml)
            throws TikaException, SAXException, IOException {
        for (HSLFPictureData pic : slideshow.getPictureData()) {
            String mediaType;

            switch (pic.getType()) {
                case EMF:
                    mediaType = "image/emf";
                    break;
                case WMF:
                    mediaType = "image/wmf";
                    break;
                case DIB:
                    mediaType = "image/bmp";
                    break;
                default:
                    mediaType = pic.getContentType();
                    break;
            }
            byte[] data = null;
            try {
                data = pic.getData();
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
                continue;
            }
            try (TikaInputStream picIs = TikaInputStream.get(data)) {
                handleEmbeddedResource(picIs, null, null, mediaType, xhtml, false);
            }
        }
    }

    private void handleSlideEmbeddedResources(ShapeContainer shapeContainer,
                                              XHTMLContentHandler xhtml)
            throws TikaException, SAXException {
        List<HSLFShape> shapes = getShapes(shapeContainer);
        if (shapes == null) {
            return;
        }
        for (HSLFShape shape : shapes) {
            //handle ActiveXShape, movie shape?
            if (shape instanceof HSLFObjectShape) {
                HSLFObjectShape oleShape = (HSLFObjectShape) shape;

                HSLFObjectData data = null;
                try {
                    data = oleShape.getObjectData();
                } catch (NullPointerException e) {
                    /* getObjectData throws NPE some times. */
                    EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
                    continue;
                }

                if (data != null) {
                    String objID = Integer.toString(oleShape.getObjectID());
                    processedEmbeddedObjects.add(data.getExOleObjStg().getPersistId());
                    // Embedded Object: add a <div
                    // class="embedded" id="X"/> so consumer can see where
                    // in the main text each embedded document
                    // occurred:
                    AttributesImpl attributes = new AttributesImpl();
                    attributes.addAttribute("", "class", "class", "CDATA", "embedded");
                    attributes.addAttribute("", "id", "id", "CDATA", objID);
                    xhtml.startElement("div", attributes);
                    xhtml.endElement("div");
                    InputStream dataStream = null;
                    try {
                        dataStream = data.getInputStream();
                    } catch (Exception e) {
                        EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
                        continue;
                    }
                    handleDataStream(dataStream, objID, oleShape.getProgId(), xhtml);
                }
            }
        }
    }

    private void handleDataStream(InputStream dataStream, String objID, String progId,
                                  XHTMLContentHandler xhtml) {
        //TODO -- inject progId into the metadata of the embedded file
        try (TikaInputStream stream = TikaInputStream.get(dataStream)) {
            String mediaType = null;
            if ("Excel.Chart.8".equals(progId)) {
                mediaType = "application/vnd.ms-excel";
            } else {
                MediaType mt =
                        getTikaConfig().getDetector().detect(stream, new Metadata());
                mediaType = mt.toString();
            }
            if (mediaType
                    .equals("application/x-tika-msoffice-embedded; format=comp_obj") ||
                    mediaType.equals("application/x-tika-msoffice")) {
                POIFSFileSystem poifs = new POIFSFileSystem(CloseShieldInputStream.wrap(stream));

                try {
                    handleEmbeddedOfficeDoc(poifs.getRoot(), objID, xhtml, false);
                } finally {
                    if (poifs != null) {
                        poifs.close();
                    }
                }
            } else {
                handleEmbeddedResource(stream, objID, objID, mediaType, xhtml, false);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            EmbeddedDocumentUtil.recordException(e, parentMetadata);
        }
    }

    //Can return null!
    private List<HSLFShape> getShapes(ShapeContainer shapeContainer) {
        try {
            return shapeContainer.getShapes();
        } catch (NullPointerException e) {
            // Sometimes HSLF hits problems
            // Please open POI bugs for any you come across!
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
            return null;
        }
    }

}
