package dev.langchain4j.store.embedding.pgvector;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import java.util.*;
import dev.langchain4j.data.embedding.Embedding;


import static dev.langchain4j.utils.HelperUtils.*;

public class PostgresEmbeddingStore implements EmbeddingStore<TextSegment> {

    /**
     * Constructor for PostgresEmbeddingStore Class
     * @param host                  the database host
     * @param port                  the database port
     * @param user                  the database user
     * @param password              the database password
     * @param database              the database name
     */
    protected PostgresEmbeddingStore(
        String host,
        Integer port,
        String user,
        String password,
        String database
    ) {
    createDataSource(host, port, user, password, database);
    }

    private static DataSource createDataSource(String host, Integer port, String user, String password, String database) {
    host = isNotBlank(host, "host");
    port = isGreaterThanZero(port, "port");
    user = isNotBlank(user, "user");
    password = isNotBlank(password, "password");
    database = isNotBlank(database, "database");

    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setServerNames(new String[]{host});
    source.setPortNumbers(new int[]{port});
    source.setDatabaseName(database);
    source.setUser(user);
    source.setPassword(password);

    return source;
    }

    /**
    * Adds multiple embeddings to the store.
    * @param embeddings  a list of embeddings to be added to the store.
    * @param embedded   a list of original contents that were embedded.
    */
    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        List<String> ids= new ArrayList<>();
        //   to be implemented
        return ids;
    }

    /**
    * Adds multiple embeddings to the store.
    * @param embeddings  a list of embeddings to be added to the store.
    */
    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        // to be implemented
        List<String> ids = new ArrayList<String>();
        return ids;
    }

    /**
    * Adds a given embedding to the store.
    * @param embedding   the embedding to be added to the store.
    */
    @Override
    public String add(Embedding embedding) {
        // to be implemented
        return "";
    }

    /**
    * Adds a given embedding to the store.
    * @param embedding   the embedding to be added to the store.
    * @param textSegment original content that was embedded.
    */
    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        // to be implemented
        return "";
    }

    /**
    * Adds a given embedding to the store.
    * @param embedding   The embedding to be added to the store.
    */
    @Override
    public void add(String id, Embedding embedding) {
           // to be implemented
    }
}