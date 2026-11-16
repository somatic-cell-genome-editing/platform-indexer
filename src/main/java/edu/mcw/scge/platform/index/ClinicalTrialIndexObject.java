package edu.mcw.scge.platform.index;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.mcw.scge.datamodel.ClinicalTrialRecord;

import java.util.List;
import java.util.Set;

public class ClinicalTrialIndexObject extends ClinicalTrialRecord {

    /**
     * The dense vector for this trial, matching the dense_vector field of the same name in
     * clinicalTrialMappings.json. Null when embedding is switched off or the call failed;
     * such a record is still indexed and still found by the keyword query, it is simply not
     * reachable by the kNN clause.
     *
     * NON_NULL matters here. The document is serialized by the Elasticsearch client's
     * Jackson mapper, which writes null fields out as JSON null by default, and a literal
     * "embedding": null is not something a dense_vector field should be asked to accept.
     * Omitting the key instead is unambiguous.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Float> embedding;

    public List<Float> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Float> embedding) {
        this.embedding = embedding;
    }

    private Set<String> phases;
    private Set<String> status;
    private Set<String> standardAges;
    private Set<String> locations;
    private Set<String> aliases;
    private Set<String> fdaDesignations;
    private Set<String> tags;
    private Set<String> indications;
    private Set<String> routesOfAdministration;
    private Set<String> mechanismOfActions;

    public Set<String> getMechanismOfActions() {
        return mechanismOfActions;
    }

    public void setMechanismOfActions(Set<String> mechanismOfActions) {
        this.mechanismOfActions = mechanismOfActions;
    }

    public Set<String> getRoutesOfAdministration() {
        return routesOfAdministration;
    }

    public void setRoutesOfAdministration(Set<String> routesOfAdministration) {
        this.routesOfAdministration = routesOfAdministration;
    }

    public Set<String> getIndications() {
        return indications;
    }

    public void setIndications(Set<String> indications) {
        this.indications = indications;
    }

    private String category;
    private Set<String> suggest;

    public Set<String> getSuggest() {
        return suggest;
    }

    public void setSuggest(Set<String> suggest) {
        this.suggest = suggest;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags;
    }

    public Set<String> getAliases() {
        return aliases;
    }

    public void setAliases(Set<String> aliases) {
        this.aliases = aliases;
    }

    public Set<String> getFdaDesignations() {
        return fdaDesignations;
    }

    public void setFdaDesignations(Set<String> fdaDesignations) {
        this.fdaDesignations = fdaDesignations;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Set<String> getPhases() {
        return phases;
    }

    public void setPhases(Set<String> phases) {
        this.phases = phases;
    }

    public Set<String> getStatus() {
        return status;
    }

    public void setStatus(Set<String> status) {
        this.status = status;
    }

    public Set<String> getStandardAges() {
        return standardAges;
    }

    public void setStandardAges(Set<String> standardAges) {
        this.standardAges = standardAges;
    }

    public Set<String> getLocations() {
        return locations;
    }

    public void setLocations(Set<String> locations) {
        this.locations = locations;
    }
}
