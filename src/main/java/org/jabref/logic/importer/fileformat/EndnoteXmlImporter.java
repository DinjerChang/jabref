package org.jabref.logic.importer.fileformat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.XMLEvent;

import org.jabref.logic.importer.ImportFormatPreferences;
import org.jabref.logic.importer.Importer;
import org.jabref.logic.importer.ParseException;
import org.jabref.logic.importer.Parser;
import org.jabref.logic.importer.ParserResult;
import org.jabref.logic.util.StandardFileType;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.field.UnknownField;
import org.jabref.model.entry.types.EntryType;
import org.jabref.model.entry.types.IEEETranEntryType;
import org.jabref.model.entry.types.StandardEntryType;
import org.jabref.model.strings.StringUtil;

import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Importer for the Endnote XML format.
 * <p>
 * Based on dtd scheme downloaded from Article #122577 in http://kbportal.thomson.com.
 */
public class EndnoteXmlImporter extends Importer implements Parser {

    private static final Logger LOGGER = LoggerFactory.getLogger(EndnoteXmlImporter.class);
    private final ImportFormatPreferences preferences;

    public EndnoteXmlImporter(ImportFormatPreferences preferences) {
        this.preferences = preferences;
    }

    private static String join(List<String> list, String string) {
        return Joiner.on(string).join(list);
    }

    @Override
    public String getName() {
        return "EndNote XML";
    }

    @Override
    public StandardFileType getFileType() {
        return StandardFileType.XML;
    }

    @Override
    public String getId() {
        return "endnote";
    }

    @Override
    public String getDescription() {
        return "Importer for the EndNote XML format.";
    }

    @Override
    public boolean isRecognizedFormat(BufferedReader reader) throws IOException {
        String str;
        int i = 0;
        while (((str = reader.readLine()) != null) && (i < 50)) {
            if (str.toLowerCase(Locale.ENGLISH).contains("<records>")) {
                return true;
            }

            i++;
        }
        return false;
    }

    @Override
    public ParserResult importDatabase(BufferedReader input) throws IOException {
        Objects.requireNonNull(input);

        List<BibEntry> bibItems = new ArrayList<>();

        try {
            XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();

            // prevent xxe (https://rules.sonarsource.com/java/RSPEC-2755)
            xmlInputFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // required for reading Unicode characters such as &#xf6;
            xmlInputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);

            XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(input);



            while (reader.hasNext()){
                reader.next();
                if (isStartXMLEvent(reader)){
                    String elementName = reader.getName().getLocalPart();
                    if (elementName.equals("record")){
                         parseRecord(reader,bibItems,elementName);
                    }
                }
            }

        } catch (XMLStreamException e) {
            LOGGER.debug("could not parse document", e);
            return ParserResult.fromError(e);
        }
        return new ParserResult(bibItems);
    }
    private void parseRecord(XMLStreamReader reader, List<BibEntry> bibItems, String startElement)
        throws XMLStreamException{

        Map<Field, String> fields = new HashMap<>();
        EntryType entryType = StandardEntryType.Article; //default value

        List<String> keywords = new ArrayList<>();

        while (reader.hasNext()){
            reader.next();
            if (isStartXMLEvent(reader)){
                String elementName = reader.getName().getLocalPart();
                switch(elementName){
                    case "ref-type" -> {
                        String type = reader.getAttributeValue(null,"name");
                        entryType = convertRefNameToType(type);
                    }
                    case "contributors" -> {
                        handleAuthorList(reader,fields,elementName);
                    }
                    case "titles" -> {
                        handleTitles(reader, fields,elementName);
                    }
                    case "pages" -> {
                        parseStyleContent(reader, fields,StandardField.PAGES, elementName);
                    }
                    case "volume" -> {
                        parseStyleContent(reader, fields,StandardField.VOLUME, elementName);
                    }
                    case "number" -> {
                        parseStyleContent(reader, fields,StandardField.NUMBER, elementName);
                    }
                    case "dates" -> {
                        parseYear(reader, fields);
                    }
                    case "notes" -> {
                        parseStyleContent(reader, fields,StandardField.NOTE, elementName);
                    }
                    case "urls" -> {
                       handleUrlList(reader, fields);
                    }
                    case "keywords" -> {
                        handleKeywordsList(reader,keywords,elementName);
                    }
                    case "abstract" -> {
                        parseStyleContent(reader, fields,StandardField.ABSTRACT, elementName);
                    }
                    case "isbn" -> {
                        parseStyleContent(reader, fields,StandardField.ISBN, elementName);
                    }
                    case "electronic-resource-num" -> {
                        parseStyleContent(reader, fields,StandardField.DOI, elementName);
                    }
                    case "publisher" -> {
                        parseStyleContent(reader, fields,StandardField.PUBLISHER, elementName);

                    }
                    case "label" -> {
                        parseStyleContent(reader, fields, new UnknownField("endnote-label"), elementName);
                    }
                }
            }
            if (isEndXMLEvent(reader) && reader.getName().getLocalPart().equals(startElement)) {
                break;
            }
        }

        BibEntry entry = new BibEntry(entryType);
        entry.putKeywords(keywords, preferences.bibEntryPreferences().getKeywordSeparator());

        entry.setField(fields);
        for (Map.Entry<Field,String> f: fields.entrySet()){
            System.out.println(f.getKey().getName() + " : " + f.getValue());
        }
        bibItems.add(entry);

    }

    private static EntryType convertRefNameToType(String refName) {
        return switch (refName.toLowerCase().trim()) {
            case "artwork", "generic" -> StandardEntryType.Misc;
            case "electronic article" -> IEEETranEntryType.Electronic;
            case "book section" -> StandardEntryType.InBook;
            case "book" -> StandardEntryType.Book;
            case "report" -> StandardEntryType.Report;
            // case "journal article" -> StandardEntryType.Article;
            default -> StandardEntryType.Article;
        };
    }

    private void handleAuthorList(XMLStreamReader reader, Map<Field, String> fields, String startElement) throws XMLStreamException {
        List<String> authorNames = new ArrayList<>();

        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                switch (elementName) {
                    case "author" -> {
                        parseAuthor(reader, authorNames);
                    }

                }
            }

            if (isEndXMLEvent(reader) && reader.getName().getLocalPart().equals(startElement)) {
                System.out.println("contributors end");
                break;
            }
        }

        fields.put(StandardField.AUTHOR, join(authorNames, " and "));
    }

    private void parseAuthor(XMLStreamReader reader, List<String> authorNames) throws XMLStreamException {

        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                switch (elementName) {
                    case "style" -> {
                        reader.next();
                        if (isCharacterXMLEvent(reader)) {
                            authorNames.add(reader.getText());
                        }
                    }
                }
            }

            if (isEndXMLEvent(reader) && "author".equals(reader.getName().getLocalPart())) {
                break;
            }
        }
    }
    private void parseStyleContent (XMLStreamReader reader, Map<Field,String> fields, Field field, String elementName) throws XMLStreamException {
        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String tag = reader.getName().getLocalPart();
                if (tag.equals("style")){
                    reader.next();
                    if (isCharacterXMLEvent(reader)) {
                        if (elementName.equals("abstract")||elementName.equals("electronic-resource-num")||elementName.equals("notes")){
                            putIfValueNotNull(fields, field, reader.getText().trim());
                        }else if (elementName.equals("isbn")  || elementName.equals("secondary-title")){
                            putIfValueNotNull(fields, field, clean(reader.getText()));
                        }else{
                            putIfValueNotNull(fields, field, reader.getText());
                        }
                    }
                }
            }
            if (isEndXMLEvent(reader) && reader.getName().getLocalPart().equals(elementName)) {
                break;
            }
        }
    }
    private void parseYear(XMLStreamReader reader, Map<Field, String> fields) throws XMLStreamException {
        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                switch (elementName) {
                    case "style" -> {
                        reader.next();
                        if (isCharacterXMLEvent(reader)) {
                            putIfValueNotNull(fields, StandardField.YEAR, reader.getText());
                        }
                    }
                }
            }

            if (isEndXMLEvent(reader) && "year".equals(reader.getName().getLocalPart())) {
                break;
            }
        }
    }
    private void handleKeywordsList(XMLStreamReader reader, List<String> keywords, String startElement) throws XMLStreamException {

        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                switch (elementName) {
                    case "keyword" -> {
                        parseKeyword(reader, keywords);
                    }
                }
            }

            if (isEndXMLEvent(reader) && reader.getName().getLocalPart().equals(startElement)) {
                System.out.println("keywords end");
                break;
            }
        }
    }
    private void parseKeyword(XMLStreamReader reader, List<String> keywords) throws XMLStreamException {

        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                switch (elementName) {
                    case "style" -> {
                        reader.next();
                        if (isCharacterXMLEvent(reader)) {
                            if (reader.getText() != null){
                                keywords.add(reader.getText());
                            }
                        }
                    }
                }
            }

            if (isEndXMLEvent(reader) && "keyword".equals(reader.getName().getLocalPart())) {
                break;
            }
        }
    }
    private void handleTitles(XMLStreamReader reader, Map<Field, String> fields, String startElement) throws XMLStreamException {

        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                switch (elementName) {
                    case "title" -> {
                        List<String> titleStyleContent = new ArrayList<>();
                        while (reader.hasNext()) {
                            reader.next();
                            if (isStartXMLEvent(reader)) {
                                String tag = reader.getName().getLocalPart();
                                if (tag.equals("style")){
                                    reader.next();
                                    if (isCharacterXMLEvent(reader)) {
                                        if (reader.getText() != null){
                                            titleStyleContent.add((reader.getText()));
                                        }
                                    }
                                }
                            }
                            if (isEndXMLEvent(reader) && reader.getName().getLocalPart().equals(elementName)) {
                                break;
                            }
                        }
                        putIfValueNotNull(fields, StandardField.TITLE, clean(join(titleStyleContent, "")));
                    }
                    case "secondary-title" ->{
                        parseStyleContent(reader, fields, StandardField.JOURNAL,elementName);
                    }
                }
            }

            if (isEndXMLEvent(reader) && reader.getName().getLocalPart().equals(startElement)) {
                System.out.println("titles end");
                break;
            }
        }
    }

    private String clean(String input) {
        return StringUtil.unifyLineBreaks(input, " ")
                         .trim()
                         .replaceAll(" +", " ");
    }

    private void putIfValueNotNull(Map<Field, String> fields, Field field, String value) {
        if (value != null) {
            fields.put(field, value);
        }
    }

    private boolean isCharacterXMLEvent(XMLStreamReader reader) {
        return reader.getEventType() == XMLEvent.CHARACTERS;
    }

    private boolean isStartXMLEvent(XMLStreamReader reader) {
        return reader.getEventType() == XMLEvent.START_ELEMENT;
    }

    private boolean isEndXMLEvent(XMLStreamReader reader) {
        return reader.getEventType() == XMLEvent.END_ELEMENT;
    }
    @Override
    public List<BibEntry> parseEntries(InputStream inputStream) throws ParseException {
        try {
            return importDatabase(
                    new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))).getDatabase().getEntries();
        } catch (IOException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
        return Collections.emptyList();
    }
    private void handleUrlList(XMLStreamReader reader, Map<Field, String> fields) throws XMLStreamException {
        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                switch (elementName) {
                    case "related-urls" -> {
                        parseRelatedUrls(reader, fields);
                    }
                    case "pdf-urls" -> {
                        parsePdfUrls(reader, fields);
                    }
                }
            }

            if (isEndXMLEvent(reader) && reader.getName().getLocalPart().equals("urls")) {
                break;
            }
        }

    }



    private void parseRelatedUrls(XMLStreamReader reader, Map<Field, String> fields) throws XMLStreamException {

        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                if (elementName.equals("style")) {
                    reader.next();
                    if (isCharacterXMLEvent(reader)) {
                        fields.put(StandardField.URL, reader.getText());
                    }
                }
            }

            if (isEndXMLEvent(reader) && "related-urls".equals(reader.getName().getLocalPart())) {
                break;
            }
        }
    }

    private void parsePdfUrls(XMLStreamReader reader, Map<Field, String> fields) throws XMLStreamException {

        while (reader.hasNext()) {
            reader.next();
            if (isStartXMLEvent(reader)) {
                String elementName = reader.getName().getLocalPart();
                if(elementName.equals("url")) {
                    System.out.println(" in url");
                    reader.next();
                    int x = reader.getEventType();
                    if (isStartXMLEvent(reader)){
                        // style
                        System.out.println("test");
                        String tagName = reader.getName().getLocalPart();
                        if (tagName.equals("style")) {
                            reader.next();
                            if (isCharacterXMLEvent(reader)) {
                                putIfValueNotNull(fields, StandardField.FILE, reader.getText());
                            }
                        }

                            }
                        }
                    }
                    if (isCharacterXMLEvent(reader)) {
                        System.out.println(" without style");
                        String y = reader.getText();
                        putIfValueNotNull(fields, StandardField.FILE, reader.getText());
                    }
//                    else if (reader.getName().getLocalPart().equals("style")) {
//
//                        putIfValueNotNull(fields, StandardField.FILE, reader.getText());
//                    }
            if (isEndXMLEvent(reader) && "pdf-urls".equals(reader.getName().getLocalPart())) {
                break;
            }
                }

//                    case "style" -> {
//                        reader.next();
//                        if (isCharacterXMLEvent(reader)) {
//                            fields.put(StandardField.URL, reader.getText());
//                        }
//                    }


            }


        }





