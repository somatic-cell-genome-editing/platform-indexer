package edu.mcw.scge.platform.index;

import edu.mcw.scge.platform.utils.BulkIndexProcessor;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Walks the shared regulatory document folder, pulls the text out of every
 * document with Tika and adds one Elasticsearch document per file.
 *
 * Metadata is derived from where a file sits and what it is called: a sub
 * folder of the root is the program the file belongs to, otherwise the leading
 * token of the file name is used (CHARM_Pre-IND_Meeting_Package.pdf -> program
 * CHARM, title "Pre-IND Meeting Package").
 */
public class ProcessRegulatoryDocuments {

    public static final String CATEGORY = "RegulatoryDocument";

    private static final Logger logger = LogManager.getLogger();

    /** Extensions Tika is asked to read; anything else in the folder is skipped. */
    private static final Set<String> SUPPORTED_EXTENSIONS = new LinkedHashSet<>(Arrays.asList(
            "pdf", "doc", "docx", "rtf", "txt", "ppt", "pptx", "xls", "xlsx", "htm", "html"));

    /** A leading file name token is read as a program code when it looks like PKU, CHARM, CPS1... */
    private static final Pattern PROGRAM_CODE = Pattern.compile("^[A-Z][A-Z0-9-]{1,11}$");

    /**
     * Short single word program names are upper cased, so a pku/ folder and a
     * PKU_... file name do not end up as two separate facet values. Names that
     * are already mixed case (hearingLoss, ucdPrimeEditing) are left alone.
     */
    private static final Pattern ACRONYM_PROGRAM = Pattern.compile("^[a-z][a-z0-9]{0,5}$");

    private static final Pattern REDACTED = Pattern.compile("(?i)[\\s_-]*\\bredacted\\b[\\s_-]*");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\n{3,}");

    /**
     * Document type is matched in order so the more specific phrase wins:
     * "Pre-IND Meeting Package" is a Meeting Package, not an IND.
     */
    private static final List<String[]> DOCUMENT_TYPES = Arrays.asList(
            new String[]{"briefing\\s*book", "Briefing Book"},
            new String[]{"meeting\\s*package", "Meeting Package"},
            new String[]{"meeting\\s*(summary|minutes)|\\bminutes\\b", "Meeting Minutes"},
            new String[]{"meeting\\s*request", "Meeting Request"},
            new String[]{"(preliminary\\s*)?responses?", "FDA Responses"},
            new String[]{"interact", "INTERACT"},
            new String[]{"pre-?\\s*ind", "Pre-IND"},
            new String[]{"\\bind\\b", "IND"});

    /** Regulatory milestone, recorded as a tag so documents can be faceted by stage. */
    private static final List<String[]> STAGES = Arrays.asList(
            new String[]{"interact", "INTERACT"},
            new String[]{"pre-?\\s*ind", "Pre-IND"},
            new String[]{"\\bind\\b", "IND"});

    /** Set from AppConfigure.xml; this is where the documents live on the servers. */
    private String documentDir = "/data/download/regulatoryDocuments";

    /**
     * Text beyond this is dropped rather than pushed into Elasticsearch; kept
     * under the 1,000,000 index.highlight.max_analyzed_offset default so
     * highlighting a hit in a long document does not fail.
     */
    private int maxContentChars = 900_000;

    /** %s is replaced with the url encoded file name. */
    private String downloadUrlPattern = "/platform/public/download/regulatory?documentName=%s";

    private final Tika tika = new Tika();

    public void indexRegulatoryDocuments() throws Exception {
        Path root = Paths.get(documentDir);
        if (!Files.isDirectory(root)) {
            logger.warn("REGULATORY DOCUMENT DIRECTORY NOT FOUND: " + root.toAbsolutePath()
                    + ", nothing to index");
            return;
        }
        logger.info("INDEXING REGULATORY DOCUMENTS FROM " + root.toAbsolutePath());

        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> SUPPORTED_EXTENSIONS.contains(extensionOf(p)))
                    .sorted()
                    .collect(Collectors.toList());
        }

        int indexed = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                RegulatoryDocumentIndexObject document = parse(root, file);
                if (document == null) {
                    continue;
                }
                indexRegulatoryDocument(document.getRelativePath(), document);
                indexed++;
            } catch (Exception e) {
                failed++;
                logger.error("FAILED TO PARSE REGULATORY DOCUMENT: " + file, e);
            }
        }
        logger.info("REGULATORY DOCUMENTS INDEXED: " + indexed + ", FAILED: " + failed
                + ", SKIPPED: " + (files.size() - indexed - failed));
    }

    /** Reads one file and turns it into an index object, or null when it holds no text. */
    public RegulatoryDocumentIndexObject parse(Path root, Path file) throws Exception {
        String fileName = file.getFileName().toString();
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

        String content;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            content = tika.parseToString(in, metadata, maxContentChars);
        }
        content = cleanContent(content);
        if (content.isEmpty()) {
            // Scanned documents with no text layer would otherwise land as empty hits.
            logger.warn("NO EXTRACTABLE TEXT, SKIPPING: " + file);
            return null;
        }

        String relativePath = root.relativize(file).toString().replace('\\', '/');
        String program = programOf(root, file, fileName);
        String baseName = stripExtension(fileName);
        String remainder = program != null ? stripLeadingToken(baseName, program) : baseName;

        boolean redacted = REDACTED.matcher(remainder).find();
        String title = toTitle(redacted ? REDACTED.matcher(remainder).replaceAll(" ") : remainder);
        if (title.isEmpty()) {
            title = toTitle(baseName);
        }

        RegulatoryDocumentIndexObject document = new RegulatoryDocumentIndexObject();
        document.setCategory(CATEGORY);
        document.setDocumentName(fileName);
        document.setRelativePath(relativePath);
        document.setTitle(title);
        document.setProgram(program);
        document.setDocumentType(matchFirst(DOCUMENT_TYPES, title));
        document.setContent(content);
        document.setContentType(metadata.get(HttpHeaders.CONTENT_TYPE));
        document.setPageCount(parsePageCount(metadata));
        document.setFileSize(Files.size(file));
        document.setLastModifiedDate(new Date(Files.getLastModifiedTime(file).toMillis()));
        document.setRedacted(redacted);
        document.setTags(buildTags(document));
        document.setSuggest(buildSuggestTerms(document));
        document.setDownloadUrl(buildDownloadUrl(fileName));
        return document;
    }

    /**
     * A sub folder of the root names the program it holds; for files sitting
     * directly in the root the leading file name token is used instead.
     */
    public String programOf(Path root, Path file, String fileName) {
        Path parent = root.relativize(file).getParent();
        if (parent != null) {
            return normalizeProgram(parent.getName(0).toString());
        }
        String[] tokens = stripExtension(fileName).split("[_\\s]+");
        if (tokens.length > 1 && PROGRAM_CODE.matcher(tokens[0]).matches()) {
            return normalizeProgram(tokens[0]);
        }
        logger.warn("NO PROGRAM FOUND FOR " + fileName
                + "; put the file in a program sub folder or prefix it with the program code");
        return null;
    }

    private String normalizeProgram(String program) {
        String trimmed = program.trim();
        return ACRONYM_PROGRAM.matcher(trimmed).matches()
                ? trimmed.toUpperCase(Locale.ENGLISH)
                : trimmed;
    }

    private Set<String> buildTags(RegulatoryDocumentIndexObject document) {
        Set<String> tags = new LinkedHashSet<>();
        if (document.getProgram() != null) {
            tags.add(document.getProgram());
        }
        if (document.getDocumentType() != null) {
            tags.add(document.getDocumentType());
        }
        String stage = matchFirst(STAGES, document.getTitle());
        if (stage != null) {
            tags.add(stage);
        }
        if (document.isRedacted()) {
            tags.add("Redacted");
        }
        return tags;
    }

    private Set<String> buildSuggestTerms(RegulatoryDocumentIndexObject document) {
        Set<String> suggestTerms = new LinkedHashSet<>(document.getTags());
        if (document.getTitle() != null) {
            suggestTerms.add(document.getTitle());
        }
        if (document.getProgram() != null && document.getDocumentType() != null) {
            suggestTerms.add(document.getProgram() + " " + document.getDocumentType());
        }
        return suggestTerms.stream().map(String::trim).filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String buildDownloadUrl(String fileName) {
        try {
            return String.format(downloadUrlPattern,
                    URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException e) {
            return String.format(downloadUrlPattern, fileName);
        }
    }

    /** Returns the label of the first pattern that hits, or null when none does. */
    private String matchFirst(List<String[]> patterns, String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ENGLISH);
        for (String[] pattern : patterns) {
            if (Pattern.compile(pattern[0]).matcher(lower).find()) {
                return pattern[1];
            }
        }
        return null;
    }

    private Integer parsePageCount(Metadata metadata) {
        String pages = metadata.get("xmpTPg:NPages");
        if (pages == null) {
            pages = metadata.get("Page-Count");
        }
        try {
            return pages != null ? Integer.valueOf(pages.trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Collapses the ragged whitespace Tika leaves behind so highlights stay readable. */
    private String cleanContent(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.replace(" ", " ").replace("\r\n", "\n").replace('\r', '\n');
        cleaned = WHITESPACE_RUN.matcher(cleaned).replaceAll(" ");
        cleaned = BLANK_LINE_RUN.matcher(cleaned).replaceAll("\n\n");
        return cleaned.trim();
    }

    private String toTitle(String text) {
        String title = text.replace('_', ' ');
        title = WHITESPACE_RUN.matcher(title).replaceAll(" ").trim();
        return StringUtils.capitalize(title);
    }

    private String stripLeadingToken(String baseName, String token) {
        return baseName.regionMatches(true, 0, token, 0, token.length())
                ? baseName.substring(token.length()).replaceFirst("^[_\\s-]+", "")
                : baseName;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String extensionOf(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ENGLISH) : "";
    }

    /**
     * The relative path is used as the document id so re-running the pipeline
     * refreshes a document in place instead of adding a second copy.
     */
    public void indexRegulatoryDocument(String id, RegulatoryDocumentIndexObject record) {
        String alias = Index.getNewAlias();
        BulkIndexProcessor.ingester.add(op -> op.index(idx -> idx.index(alias).id(id).document(record)));
    }

    public String getDocumentDir() {
        return documentDir;
    }

    public void setDocumentDir(String documentDir) {
        this.documentDir = documentDir;
    }

    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

    public String getDownloadUrlPattern() {
        return downloadUrlPattern;
    }

    public void setDownloadUrlPattern(String downloadUrlPattern) {
        this.downloadUrlPattern = downloadUrlPattern;
    }
}
