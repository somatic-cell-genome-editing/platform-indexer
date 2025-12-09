package edu.mcw.scge.platform.index;


import com.fasterxml.jackson.annotation.JsonInclude;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.gson.Gson;
import edu.mcw.scge.dao.implementation.ClinicalTrailDAO;
import edu.mcw.scge.dao.implementation.DefinitionDAO;
import edu.mcw.scge.datamodel.Alias;
import edu.mcw.scge.datamodel.ClinicalTrialAdditionalInfo;
import edu.mcw.scge.datamodel.ClinicalTrialExternalLink;
import edu.mcw.scge.datamodel.ClinicalTrialRecord;
import edu.mcw.scge.platform.utils.BulkIndexProcessor;
import edu.mcw.scge.services.ESClient;
import edu.mcw.scge.services.SCGEContext;
import org.apache.commons.lang.StringUtils;


import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;

import org.elasticsearch.xcontent.XContentType;
import org.json.JSONObject;



import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ProcessFile {
    ClinicalTrailDAO clinicalTrailDAO=new ClinicalTrailDAO();

    ObjectMapper mapper=new ObjectMapper();
    DefinitionDAO definitionDAO=new DefinitionDAO();


    public void indexClinicalTrials() throws Exception {
        Gson gson=new Gson();
        List<ClinicalTrialRecord> trials= clinicalTrailDAO.getAllClinicalTrailRecords();
        ObjectMapper mapper=JsonMapper.builder().
                enable( JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                //.enable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);


        for(ClinicalTrialRecord trial:trials){
        //   formatRecordValue(trial);
            List<ClinicalTrialExternalLink> externalLinks=  clinicalTrailDAO.getExtLinksByNctId(trial.getNctId());
            if(externalLinks!=null && externalLinks.size()>0)
              trial.setExternalLinks(externalLinks);
            ClinicalTrialIndexObject object=mapper.readValue(gson.toJson(trial), ClinicalTrialIndexObject.class);
            List<Alias> aliases=clinicalTrailDAO.getAliases(trial.getNctId(),"compound");
            if(aliases!=null && aliases.size()>0){
                object.setAliases(aliases.stream().map(Alias::getAlias).collect(Collectors.toSet()));
            }
            Set<String> tags = new HashSet<>(getAbbreviationTags(trial.getNctId(), "fda_designation"));
            if(trial.getIndicationDOID()!=null) {
                tags.addAll(getOntologyTags(trial.getNctId(), trial.getIndicationDOID(), "indication_ont_parent_term"));
            }
            object.setTags(tags);
            List<ClinicalTrialAdditionalInfo> additionalInfo=clinicalTrailDAO.getAdditionalInfo(trial.getNctId(),"fda_designation");
            if(additionalInfo!=null && additionalInfo.size()>0){
                object.setFdaDesignations(additionalInfo.stream().map(ClinicalTrialAdditionalInfo::getPropertyValue).collect(Collectors.toSet()));
            }
          if(trial.getPhase()!=null)
          object.setPhases(Arrays.stream(trial.getPhase().split(",")).map(String::trim).collect(Collectors.toSet()));
            if(trial.getIndication()!=null)
                object.setIndications(Arrays.stream(trial.getIndication().split(",")).map(String::trim).collect(Collectors.toSet()));

            if(trial.getStandardAge()!=null)
          object.setStandardAges(Arrays.stream(trial.getStandardAge().split(",")).map(String::trim).collect(Collectors.toSet()));
            if(trial.getStudyStatus()!=null)
          object.setStatus(Arrays.stream(trial.getStudyStatus().split(",")).map(String::trim).collect(Collectors.toSet()));
            if(trial.getLocation()!=null)
            object.setLocations(Arrays.stream(trial.getLocation().split(",")).map(String::trim).collect(Collectors.toSet()));
            object.setCategory("ClinicalTrial");
            addSuggestTerms(object);
          indexClinicalTrailRecord(object);
        }
    }
    public void addSuggestTerms(ClinicalTrialIndexObject object){
        Set<String> suggestTerms=new HashSet<>();
        try {
            if(object.getPhases()!=null)
            suggestTerms.addAll(object.getPhases().stream().map(t->StringUtils.capitalize(t.toLowerCase().trim())).collect(Collectors.toSet()));
        }catch (Exception ignored){}
        try{
            if(object.getAliases()!=null)
            suggestTerms.addAll(object.getAliases());
        }catch (Exception ignored){}try{
            if(object.getFdaDesignations()!=null)
            suggestTerms.addAll(object.getFdaDesignations().stream().map(t->StringUtils.capitalize(t.toLowerCase().trim())).collect(Collectors.toSet()));
        }catch (Exception ignored){}try{
            if(object.getLocations()!=null)
            suggestTerms.addAll(object.getLocations().stream().map(t->StringUtils.capitalize(t.toLowerCase().trim())).collect(Collectors.toSet()));
        }catch (Exception ignored){}try{
            if(object.getTags()!=null) {
                for(String tag:object.getTags()) {
                    if(!tag.contains("DOID"))
                    suggestTerms.add(StringUtils.capitalize(tag.toLowerCase().trim()));
                    else  suggestTerms.add(tag);
                }
            }
        }catch (Exception ignored){}try{
            if(object.getStandardAges()!=null)
            suggestTerms.addAll(object.getStandardAges().stream().map(t->StringUtils.capitalize(t.toLowerCase().trim())).collect(Collectors.toSet()));
        }catch (Exception ignored){}try{
            if(object.getBrowseConditionTerms()!=null){
                String[] browseConditionTerms=object.getBrowseConditionTerms().split("[,;]");
                suggestTerms.addAll(Arrays.stream(browseConditionTerms).map(t->StringUtils.capitalize(t.toLowerCase().trim())).collect(Collectors.toSet()));
            }
        }catch (Exception ignored){}try{
            if(object.getStatus()!=null)
            suggestTerms.addAll(object.getStatus().stream().map(t->StringUtils.capitalize(t.toLowerCase().trim())).collect(Collectors.toSet()));
        }catch (Exception ignored){}try{
            if(object.getCompoundName()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getCompoundName().trim().toLowerCase()));
        }catch (Exception ignored){}try{
            if(object.getEditorType()!=null)
            suggestTerms.add(object.getEditorType());
        }catch (Exception ignored){}try{
            if(object.getDeliverySystem()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getDeliverySystem().toLowerCase().trim()));
        }catch (Exception ignored){}try{
            if(object.getInterventionName()!=null)
            suggestTerms.add(object.getInterventionName());
        }catch (Exception ignored){}try{
            if(object.getTherapyRoute()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getTherapyRoute().toLowerCase().trim()));
        }catch (Exception ignored){}try{
            if(object.getTherapyType()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getTherapyType().toLowerCase().trim()));
        }catch (Exception ignored){}try{
            if(object.getVectorType()!=null)
            suggestTerms.add(object.getVectorType());
        }catch (Exception ignored){}try{
            if(object.getDrugProductType()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getDrugProductType().toLowerCase().trim()));
        }catch (Exception ignored){}try{
            if(object.getDevelopmentStatus()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getDevelopmentStatus().toLowerCase().trim()));
        }catch (Exception ignored){}try{
            if(object.getEligibilitySex()!=null && !object.getEligibilitySex().equalsIgnoreCase("all"))
            suggestTerms.add(object.getEligibilitySex());
        }catch (Exception ignored){}try{
            if(object.getIndications()!=null)
            suggestTerms.addAll(object.getIndications().stream().map(i->StringUtils.capitalize(i.toLowerCase().trim())).collect(Collectors.toSet()));
        }catch (Exception ignored){}try{
            if(object.getMechanismOfAction()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getMechanismOfAction().toLowerCase().trim()));
        }catch (Exception ignored){}try{
            if(object.getRouteOfAdministration()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getRouteOfAdministration().toLowerCase().trim()));
        }catch (Exception ignored){}try{
            if(object.getSponsor()!=null)
            suggestTerms.add(StringUtils.capitalize(object.getSponsor().trim().toLowerCase()));
        }catch (Exception ignored){}try{
            if(object.getTargetGeneOrVariant()!=null)
            suggestTerms.add(object.getTargetGeneOrVariant());
        }catch (Exception ignored){}try{
            if(object.getTargetTissueOrCell()!=null)
            suggestTerms.add(object.getTargetTissueOrCell());
        }catch (Exception ignored){}
        Map<String, Set<String>> suggestions=new HashMap<>();
        if(suggestTerms.size()>0) {
            suggestions.put("input", suggestTerms.stream().map(String::trim).collect(Collectors.toSet()));
            object.setSuggest(suggestions);
        }
    }

    public Set<String> getAbbreviationTags(String nctId,String tagType) throws Exception {
        List<ClinicalTrialAdditionalInfo> additionalInfo=clinicalTrailDAO.getAdditionalInfo(nctId,tagType);

        Set<String> tags=new HashSet<>();
        for(ClinicalTrialAdditionalInfo info:additionalInfo){
            List<String> defs=definitionDAO.getAbbreviation(info.getPropertyValue());
            if(defs!=null && defs.size()>0){
                tags.addAll(new HashSet<>(defs));

            }
        }
        return tags;
    }
    public Set<String> getOntologyTags(String nctId,String indicationDOID, String tagType) throws Exception {
        List<ClinicalTrialAdditionalInfo> additionalInfo=clinicalTrailDAO.getAdditionalInfo(nctId,tagType);

        Set<String> tags=new HashSet<>();
        tags.add(indicationDOID);
        for(ClinicalTrialAdditionalInfo info:additionalInfo){
            tags.add(info.getPropertyValue());
        }
        return tags;
    }
    public void formatRecordValue(ClinicalTrialRecord record){
        try {
            record.setTargetGeneOrVariant(StringUtils.capitalize(record.getTargetGeneOrVariant()));
        }catch (Exception e){}
        try {
            record.setCompoundName(StringUtils.capitalize(record.getCompoundName()));
        }catch (Exception e){}
        try {
            record.setTherapyType(StringUtils.capitalize(record.getTherapyType()));
        }catch (Exception e){}
        try {
            record.setTherapyRoute(StringUtils.capitalize(record.getTherapyRoute()));
        }catch(Exception e){}
        try {
            record.setMechanismOfAction(StringUtils.capitalize(record.getMechanismOfAction()));
        }catch (Exception e){}
        try {
            record.setRouteOfAdministration(StringUtils.capitalize(record.getRouteOfAdministration()));
        }catch (Exception e){}
        try {
            record.setDrugProductType(StringUtils.capitalize(record.getDrugProductType()));
        }catch (Exception e){}
        try {
            record.setTargetTissueOrCell(StringUtils.capitalize(record.getTargetTissueOrCell()));
        }catch (Exception e){}
        try {
            record.setDeliverySystem(StringUtils.capitalize(record.getDeliverySystem()));
        }catch (Exception e){}
        try {
            if(!record.getDose1().equalsIgnoreCase("none"))
            record.setDose1(StringUtils.capitalize(record.getDose1()));
            else record.setDose1("");
        }catch (Exception e){}
        try {
            if(!record.getDose2().equalsIgnoreCase("none"))
            record.setDose2(StringUtils.capitalize(record.getDose2()));
            else record.setDose2("");
        }catch (Exception e){}
        try {
            if(!record.getDose3().equalsIgnoreCase("none"))
            record.setDose3(StringUtils.capitalize(record.getDose3()));
            else record.setDose3("");
        }catch (Exception e){}
        try {
            if(!record.getDose4().equalsIgnoreCase("none"))
            record.setDose4(StringUtils.capitalize(record.getDose4()));
            else record.setDose4("");
        }catch (Exception e){}
        try {
            if(!record.getDose5().equalsIgnoreCase("none"))
            record.setDose5(StringUtils.capitalize(record.getDose5()));
            else record.setDose5("");
        }catch (Exception e){}

        try {
            if(record.getIsFDARegulated()!=null && !record.getIsFDARegulated().equalsIgnoreCase("null"))
                record.setIsFDARegulated(StringUtils.capitalize(record.getIsFDARegulated()));
            else record.setIsFDARegulated("");
        }catch (Exception e){}
        try {
            if(record.getRecentUpdates()!=null && !record.getRecentUpdates().equalsIgnoreCase("null"))
                record.setRecentUpdates(StringUtils.capitalize(record.getRecentUpdates()));

        }catch (Exception e){}

        if(!record.getStudyStatus().equals("")) record.setStudyStatus(formatFieldVal(record.getStudyStatus()));
        if(!record.getSponsorClass().equals("") && !record.getSponsorClass().equalsIgnoreCase("NIH")) record.setSponsorClass(formatFieldVal(record.getSponsorClass()));
        if(!record.getPhase().equals("")) record.setPhase(formatFieldVal(record.getPhase()));
        if(!record.getStandardAge().equals("")) record.setStandardAge(formatFieldVal(record.getStandardAge()));
    }
    public String formatFieldVal(String fieldVal){
        return  Arrays.stream(fieldVal.split(",")).map(str->StringUtils.capitalize(str.toLowerCase().trim().replaceAll("_", " "))).collect(Collectors.joining(", "));
    }
    public void indexClinicalTrailRecord(ClinicalTrialIndexObject record) throws IOException {
        JSONObject jsonObject = new JSONObject(record);
        IndexRequest request=   new IndexRequest(Index.getNewAlias()).source(jsonObject.toString(), XContentType.JSON);
        ESClient.getClient().index(request, RequestOptions.DEFAULT);
        RefreshRequest refreshRequest = new RefreshRequest();
        ESClient.getClient().indices().refresh(refreshRequest, RequestOptions.DEFAULT);

    }
    public void updateClinicalTrailRecord(ClinicalTrialIndexObject record) throws Exception {

        IndexAdmin indexAdmin=new IndexAdmin();
        indexAdmin.updateIndex(SCGEContext.getESIndexName());
        if(BulkIndexProcessor.bulkProcessor==null){
            BulkIndexProcessor.getInstance();
        }
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        String json = mapper.writeValueAsString(record);
        BulkIndexProcessor.bulkProcessor.add(new IndexRequest(Index.getNewAlias()).source(json, XContentType.JSON));
    }
}
