package edu.mcw.scge.platform.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import edu.mcw.scge.services.ESClient;
import edu.mcw.scge.services.SCGEContext;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;

public class IndexAdmin {

    private Index index;

    public void createIndex(String mappings, String type) throws Exception {
        ElasticsearchClient client = ESClient.getClient();
        String aliasName = index.getIndex();
        boolean existsAlias = client.indices().existsAlias(b -> b.name(aliasName)).value();
        if (existsAlias) {
            for (String idx : Index.getIndices()) {
                boolean aliasOnIdx = client.indices()
                        .existsAlias(b -> b.name(aliasName).index(idx))
                        .value();
                if (!aliasOnIdx) {
                    Index.setNewAlias(idx);
                    boolean indexExists = client.indices().exists(b -> b.index(idx)).value();
                    if (indexExists) {   /**** delete index if exists ****/
                        client.indices().delete(b -> b.index(idx));
                        System.out.println(idx + " deleted");
                    }
                    createNewIndex(idx, mappings, type);
                } else {
                    Index.setOldAlias(idx);
                }
            }
        } else {
            String firstIdx = Index.getIndex() + "1";
            boolean indexExists = client.indices().exists(b -> b.index(firstIdx)).value();
            if (indexExists) {   /**** delete index if exists ****/
                client.indices().delete(b -> b.index(firstIdx));
                System.out.println(firstIdx + " deleted");
            }
            createNewIndex(firstIdx, mappings, type);
        }
    }

    public void createNewIndex(String index, String _mappings, String type) throws Exception {

        String path = "data/" + _mappings + ".json";
        System.out.println("CREATING NEW INDEX..." + index);

        String mappingsJson = new String(Files.readAllBytes(Paths.get(path)));
        int replicates = 0;
        if (SCGEContext.isProduction() || SCGEContext.isTest()) {
            replicates = 1;
        }
        final int finalReplicates = replicates;

        /********* create index, put mappings and analyzers ****/
        CreateIndexRequest request = CreateIndexRequest.of(b -> b
                .index(index)
                .settings(IndexSettings.of(s -> s
                        .numberOfShards("5")
                        .numberOfReplicas(String.valueOf(finalReplicates))))
                .mappings(TypeMapping.of(tm -> tm.withJson(new StringReader(mappingsJson))))
        );
        CreateIndexResponse createIndexResponse = ESClient.getClient().indices().create(request);
        System.out.println(index + " created on  " + new Date());

        Index.setNewAlias(index);
    }

    public void updateIndex(String index) throws Exception {
        Index.setIndex(index);
        if (Index.getIndex() != null) {
            boolean indicesExists = ESClient.getClient().indices()
                    .exists(b -> b.index(Index.getIndex()))
                    .value();
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
