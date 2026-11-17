package edu.mcw.scge.platform;


import edu.mcw.scge.platform.index.Index;
import edu.mcw.scge.platform.index.IndexAdmin;
import edu.mcw.scge.platform.index.ProcessFile;


import edu.mcw.scge.platform.index.ProcessRegulatoryDocuments;
import edu.mcw.scge.platform.utils.BulkIndexProcessor;
import edu.mcw.scge.process.Utils;
import edu.mcw.scge.services.ESClient;

import org.apache.logging.log4j.LogManager;

import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesResponse;
import org.apache.logging.log4j.Logger;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;



import java.io.IOException;

import java.util.*;


public class Main {
    private String version;
    private IndexAdmin admin;
    private Index index;
    String command;
    String env;
//    String source;
//    IndexAdmin indexer=new IndexAdmin();
    private static List<String> environments;
    ProcessFile fileProcess=new ProcessFile();
    /** Overridden from AppConfigure.xml to point at the shared document folder. */
    private ProcessRegulatoryDocuments regulatoryDocuments=new ProcessRegulatoryDocuments();
    protected static Logger logger= LogManager.getLogger();
    public static void main(String[] args) throws IOException {

        DefaultListableBeanFactory bf= new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf) .loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        Main manager= (Main) bf.getBean("manager");
        manager.command=args[0];
        manager.env=args[1];
//        manager.source=args[2];
        logger.info(manager.version);
       BulkIndexProcessor.getInstance();
      String index="scgeplatform_search_ct_"+manager.env;
      //  String index= SCGEContext.getESIndexName();
        List<String> indices= new ArrayList<>();
        if (environments.contains(manager.env)) {
            manager.index.setIndex( index);
            indices.add(index+ "1");
           indices.add(index  + "2");
           manager.index.setIndices(indices);
        }
        manager.index= (Index) bf.getBean("index");

        try {
            manager.run();
        } catch (Exception e) {
            try {
                    ESClient.destroy();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

            e.printStackTrace();
        }
            ESClient.destroy();

    }

    public void run() throws Exception {
        long start = System.currentTimeMillis();

        switch (command.toLowerCase()) {
            case "reindex":
                /**** rebuild the whole search index from every source ****/
                admin.createIndex("clinicalTrialMappings", "");
                fileProcess.indexClinicalTrials();
                regulatoryDocuments.indexRegulatoryDocuments();
                break;
            case "regulatorydocuments":
                /**** refresh just the regulatory documents in the live index;
                      the clinical trials already in it are left untouched and
                      the alias stays where it is ****/
                admin.updateIndex(Index.getIndex());
                regulatoryDocuments.indexRegulatoryDocuments();
                break;
            default:
                logger.info("UNKNOWN COMMAND '" + command
                        + "', expected 'reindex' or 'regulatoryDocuments'");
                BulkIndexProcessor.getInstance().destroy();
                return;
        }
        BulkIndexProcessor.getInstance().destroy();

        String clusterStatus = this.getClusterHealth(Index.getNewAlias());
        if (!clusterStatus.equalsIgnoreCase("ok")) {
            logger.info(clusterStatus + ", refusing to continue with operations");
        } else {
            if (command.equalsIgnoreCase("reindex")) {
                logger.info("CLUSTER STATUS:" + clusterStatus + ". Switching Alias...");
                switchAlias();
            }
        }
        long end = System.currentTimeMillis();
        logger.info(" - " + Utils.formatElapsedTime(start, end));
        logger.info("CLIENT IS CLOSED");
    }

    public void setVersion(String version) {
    }


    public void setIndexName(Index indexName) {
    }


    public IndexAdmin getAdmin() {
        return admin;
    }

    public void setAdmin(IndexAdmin admin) {
        this.admin = admin;
    }



    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index) {
        this.index = index;
    }

    public ProcessRegulatoryDocuments getRegulatoryDocuments() {
        return regulatoryDocuments;
    }

    public void setRegulatoryDocuments(ProcessRegulatoryDocuments regulatoryDocuments) {
        this.regulatoryDocuments = regulatoryDocuments;
    }


    public String getClusterHealth(String index) throws Exception {

        HealthResponse response = ESClient.getClient().cluster().health(h -> h.index(index));
        logger.info("CLUSTER STATE: " + response.status().name());
        if (Boolean.TRUE.equals(response.timedOut())) {
            return "cluster state is " + response.status().name();
        }

        return "OK";
    }
    public boolean switchAlias() throws Exception {
        logger.info("NEW ALIAS: " + Index.getNewAlias() + " || OLD ALIAS:" + Index.getOldAlias());

        UpdateAliasesRequest request;
        if (Index.getOldAlias() != null) {
            request = UpdateAliasesRequest.of(b -> b
                    .actions(a -> a.remove(r -> r.index(Index.getOldAlias()).alias(Index.getIndex())))
                    .actions(a -> a.add(ad -> ad.index(Index.getNewAlias()).alias(Index.getIndex())))
            );
        } else {
            request = UpdateAliasesRequest.of(b -> b
                    .actions(a -> a.add(ad -> ad.index(Index.getNewAlias()).alias(Index.getIndex())))
            );
        }
        UpdateAliasesResponse response = ESClient.getClient().indices().updateAliases(request);

        return response.acknowledged();
    }


    public void setEnvironments(List environments) {
        this.environments = environments;
    }

    public List getEnvironments() {
        return environments;
    }

    public String getVersion() {
        return version;
    }
}