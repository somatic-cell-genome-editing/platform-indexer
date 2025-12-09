package edu.mcw.scge.platform;


import edu.mcw.scge.platform.index.Index;
import edu.mcw.scge.platform.index.IndexAdmin;
import edu.mcw.scge.platform.index.ProcessFile;



import edu.mcw.scge.process.Utils;
import edu.mcw.scge.services.ESClient;

import org.apache.logging.log4j.LogManager;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;


import org.elasticsearch.client.RequestOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;


import java.io.IOException;

import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private String version;
    private IndexAdmin admin;
    private Index index;
    String command;
    String env;
//    String source;
//    IndexAdmin indexer=new IndexAdmin();
    private static List environments;
    ProcessFile fileProcess=new ProcessFile();
    protected static Logger logger= LogManager.getLogger();
    public static void main(String[] args) throws IOException {

        DefaultListableBeanFactory bf= new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf) .loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        Main manager= (Main) bf.getBean("manager");
        manager.command=args[0];
        manager.env=args[1];
//        manager.source=args[2];
        logger.info(manager.version);
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

        if (command.equalsIgnoreCase("reindex"))
           admin.createIndex("clinicalTrialMappings", "");
        fileProcess.indexClinicalTrials();

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
    

    public String getClusterHealth(String index) throws Exception {

        ClusterHealthRequest request = new ClusterHealthRequest(index);
        ClusterHealthResponse response = ESClient.getClient().cluster().health(request, RequestOptions.DEFAULT);
        logger.info("CLUSTER STATE: " +response.getStatus().name());
        //     log.info("CLUSTER STATE: " + response.getStatus().name());
        if (response.isTimedOut()) {
            return   "cluster state is " + response.getStatus().name();
        }

        return "OK";
    }
    public boolean switchAlias() throws Exception {
        logger.info("NEW ALIAS: " + Index.getNewAlias() + " || OLD ALIAS:" + Index.getOldAlias());
        IndicesAliasesRequest request = new IndicesAliasesRequest();


        if (Index.getOldAlias() != null) {

            IndicesAliasesRequest.AliasActions removeAliasAction =
                    new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.REMOVE)
                            .index(Index.getOldAlias())
                            .alias(Index.getIndex());
            IndicesAliasesRequest.AliasActions addAliasAction =
                    new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(Index.getNewAlias())
                            .alias(Index.getIndex());
            request.addAliasAction(removeAliasAction);
            request.addAliasAction(addAliasAction);
            //    log.info("Switched from " + RgdIndex.getOldAlias() + " to  " + RgdIndex.getNewAlias());

        }else{
            IndicesAliasesRequest.AliasActions addAliasAction =
                    new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(Index.getNewAlias())
                            .alias(Index.getIndex());
            request.addAliasAction(addAliasAction);
            //    log.info(rgdIndex.getIndex() + " pointed to " + RgdIndex.getNewAlias());
        }
        AcknowledgedResponse indicesAliasesResponse =
                ESClient.getClient().indices().updateAliases(request, RequestOptions.DEFAULT);

        return  true;

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