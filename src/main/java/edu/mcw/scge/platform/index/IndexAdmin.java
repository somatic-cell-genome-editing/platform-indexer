package edu.mcw.scge.platform.index;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.mcw.scge.services.ESClient;
import edu.mcw.scge.services.SCGEContext;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentType;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;

public class IndexAdmin {

    private Index index;
    public void createIndex(String mappings, String type) throws Exception {
        GetAliasesRequest aliasesRequest = new GetAliasesRequest(index.getIndex());
        boolean existsAlias = ESClient.getClient().indices().existsAlias(aliasesRequest, RequestOptions.DEFAULT);
        if (existsAlias) {
            for (String index : Index.getIndices()) {
                aliasesRequest.indices(index);
                existsAlias = ESClient.getClient().indices().existsAlias(aliasesRequest, RequestOptions.DEFAULT);
                if (!existsAlias) {
                    Index.setNewAlias(index);
                    GetIndexRequest request1 = new GetIndexRequest(index);
                    boolean indexExists = ESClient.getClient().indices().exists(request1, RequestOptions.DEFAULT);

                    if (indexExists) {   /**** delete index if exists ****/

                        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(index);
                        ESClient.getClient().indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
                        System.out.println(index + " deleted");
                    }
                    createNewIndex(index, mappings, type);
                } else {
                    Index.setOldAlias(index);
                }

            }
        } else {
            GetIndexRequest request1 = new GetIndexRequest(Index.getIndex() + "1");
            boolean indexExists = ESClient.getClient().indices().exists(request1, RequestOptions.DEFAULT);
            if (indexExists) {   /**** delete index if exists ****/

                DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(Index.getIndex() + "1");
                ESClient.getClient().indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
                System.out.println(Index.getIndex() + "1" + " deleted");
            }
            createNewIndex(Index.getIndex() + "1", mappings, type);

        }
    }
        public void createNewIndex(String index, String _mappings, String type) throws Exception {

            String path= "data/"+_mappings+".json";
            System.out.println("CREATING NEW INDEX..." + index);

            String mappings=new String(Files.readAllBytes(Paths.get(path)));
         //   String analyzers=new String(Files.readAllBytes(Paths.get("data/analyzers.json")));
            int replicates=0;

                if(SCGEContext.isProduction() || SCGEContext.isTest()){
                replicates=1;
            }
            /********* create index, put mappings and analyzers ****/
            CreateIndexRequest request=new CreateIndexRequest(index);
            request.settings(Settings.builder()
                    .put("index.number_of_shards",5)
                    .put("index.number_of_replicas", replicates))
               //     .loadFromSource(analyzers, XContentType.JSON))
            ;
            request.mapping(mappings, XContentType.JSON);
            org.elasticsearch.client.indices.CreateIndexResponse createIndexResponse = ESClient.getClient().indices().create(request, RequestOptions.DEFAULT);
            System.out.println(index + " created on  " + new Date());

            Index.setNewAlias(index);

    }
    public void updateIndex(String index) throws Exception {
        Index.setIndex(index);
        if(Index.getIndex()!=null) {
            GetIndexRequest request=new GetIndexRequest(Index.getIndex());
            boolean indicesExists=ESClient.getClient().indices().exists(request, RequestOptions.DEFAULT);
            if (indicesExists) {  /* CHECK IF INDEX NAME PROVIDED EXISTS*/
                Index.setNewAlias(Index.getIndex());
            }
        }
    }

    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index) {
        this.index = index;
    }
}
