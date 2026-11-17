package edu.mcw.scge.platform.index;

import java.util.Date;
import java.util.Set;

/**
 * One indexed regulatory document (a single file shared by an SCGE program -
 * briefing book, meeting package, FDA responses, ...).
 *
 * These live in the same index as the clinical trials and are told apart by
 * {@link #getCategory()}, which is always "RegulatoryDocument"; the platform
 * search filters on category.keyword.
 */
public class RegulatoryDocumentIndexObject {

    private String category;
    private String documentName;
    private String title;
    private String program;
    private String documentType;
    private String content;
    private String contentType;
    private String relativePath;
    private String downloadUrl;
    private Long fileSize;
    private Integer pageCount;
    private Date lastModifiedDate;
    private boolean redacted;
    private Set<String> tags;
    private Set<String> suggest;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getProgram() {
        return program;
    }

    public void setProgram(String program) {
        this.program = program;
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Date getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(Date lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public boolean isRedacted() {
        return redacted;
    }

    public void setRedacted(boolean redacted) {
        this.redacted = redacted;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags;
    }

    public Set<String> getSuggest() {
        return suggest;
    }

    public void setSuggest(Set<String> suggest) {
        this.suggest = suggest;
    }
}
