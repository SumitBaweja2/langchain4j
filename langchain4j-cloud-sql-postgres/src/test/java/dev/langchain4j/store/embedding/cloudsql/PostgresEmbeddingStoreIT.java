package dev.langchain4j.store.embedding.cloudsql;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static dev.langchain4j.internal.Utils.getOrDefault;
import static dev.langchain4j.internal.Utils.randomUUID;
import static dev.langchain4j.utils.CloudSQLTestUtils.randomPGvector;
import static dev.langchain4j.utils.CloudSQLTestUtils.verifyIndex;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.engine.EmbeddingStoreConfig;
import dev.langchain4j.engine.MetadataColumn;
import dev.langchain4j.engine.PostgresEngine;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.index.BaseIndex;
import dev.langchain4j.store.embedding.index.DistanceStrategy;
import dev.langchain4j.store.embedding.index.HNSWIndex;
import dev.langchain4j.store.embedding.index.IVFFlatIndex;
import java.sql.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class PostgresEmbeddingStoreIT {
    private static String projectId;
    private static String region;
    private static String instance;
    private static String database;
    private static String user;
    private static String password;
    private static String iamEmail;

    private static PostgresEngine engine;
    private static PostgresEmbeddingStore store;
    private static Connection defaultConnection;
    private static EmbeddingStoreConfig embeddingStoreConfig;
    private static final String TABLE_NAME = "JAVA_EMBEDDING_TEST_TABLE";
    private static final Integer VECTOR_SIZE = 384;
    private static final String ipType = "public";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(INDENT_OUTPUT);
    private static final EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();

    @BeforeAll
    public static void beforeAll() throws SQLException {
        projectId = System.getenv("POSTGRES_PROJECT_ID");
        region = System.getenv("REGION");
        instance = System.getenv("POSTGRES_INSTANCE");
        database = System.getenv("POSTGRES_DB");
        user = System.getenv("POSTGRES_USER");
        password = System.getenv("POSTGRES_PASS");
        iamEmail = System.getenv("POSTGRES_IAM_EMAIL");

        engine = new PostgresEngine.Builder()
                .projectId(projectId)
                .region(region)
                .instance(instance)
                .database(database)
                .user(user)
                .password(password)
                .ipType(ipType)
                .build();

        List<MetadataColumn> metadataColumns = new ArrayList<>();
        metadataColumns.add(new MetadataColumn("string", "text", true));
        metadataColumns.add(new MetadataColumn("uuid", "uuid", true));
        metadataColumns.add(new MetadataColumn("integer", "integer", true));
        metadataColumns.add(new MetadataColumn("long", "bigint", true));
        metadataColumns.add(new MetadataColumn("float", "real", true));
        metadataColumns.add(new MetadataColumn("double", "double precision", true));

        embeddingStoreConfig = new EmbeddingStoreConfig.Builder(TABLE_NAME, VECTOR_SIZE)
                .metadataColumns(metadataColumns)
                .storeMetadata(true)
                .build();
        defaultConnection = engine.getConnection();
        defaultConnection.createStatement().executeUpdate(String.format("DROP TABLE IF EXISTS \"%s\"", TABLE_NAME));
        engine.initVectorStoreTable(embeddingStoreConfig);
        List<String> metaColumnNames =
                metadataColumns.stream().map(c -> c.getName()).collect(Collectors.toList());
        store = new PostgresEmbeddingStore.Builder(engine, TABLE_NAME)
                .distanceStrategy(DistanceStrategy.COSINE_DISTANCE)
                .metadataColumns(metaColumnNames)
                .build();
    }

    @AfterEach
    public void afterEach() throws SQLException {
        defaultConnection.createStatement().executeUpdate(String.format("TRUNCATE TABLE \"%s\"", TABLE_NAME));
    }

    @AfterAll
    public static void afterAll() throws SQLException {
        defaultConnection.createStatement().executeUpdate(String.format("DROP TABLE IF EXISTS \"%s\"", TABLE_NAME));
        defaultConnection.close();
    }

    @Test
    void remove_all_from_store() throws SQLException {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PGvector vector = randomPGvector(VECTOR_SIZE);
            embeddings.add(new Embedding(vector.toArray()));
        }
        List<String> ids = store.addAll(embeddings);
        String stringIds = ids.stream().map(id -> String.format("'%s'", id)).collect(Collectors.joining(","));
        try (Statement statement = defaultConnection.createStatement(); ) {
            // assert IDs exist
            ResultSet rs = statement.executeQuery(String.format(
                    "SELECT \"%s\" FROM \"%s\" WHERE \"%s\" IN (%s)",
                    embeddingStoreConfig.getIdColumn(), TABLE_NAME, embeddingStoreConfig.getIdColumn(), stringIds));
            while (rs.next()) {
                String response = rs.getString(embeddingStoreConfig.getIdColumn());
                assertThat(ids).contains(response);
            }
        }
        store.removeAll(ids);
        try (Statement statement = defaultConnection.createStatement(); ) {
            // assert IDs were removed
            ResultSet rs = statement.executeQuery(String.format(
                    "SELECT \"%s\" FROM \"%s\" WHERE \"%s\" IN (%s)",
                    embeddingStoreConfig.getIdColumn(), TABLE_NAME, embeddingStoreConfig.getIdColumn(), stringIds));
            assertThat(rs.isBeforeFirst()).isFalse();
        }
    }

    @Test
    void search_for_vector_min_score_0() {
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> textSegments = new ArrayList<>();
        Map<Integer, Map<String, Object>> metaMaps = new HashMap<>();

        Stack<String> hayStack = new Stack<>();
        for (int i = 0; i < 10; i++) {
            PGvector vector = randomPGvector(VECTOR_SIZE);
            embeddings.add(new Embedding(vector.toArray()));
            Map<String, Object> metaMap = new HashMap<>();
            metaMap.put("string", "s" + i);
            metaMap.put("uuid", UUID.randomUUID());
            metaMap.put("integer", i);
            metaMap.put("long", 1L);
            metaMap.put("float", 1f);
            metaMap.put("double", 1d);
            metaMap.put("extra", "not in table columns " + i);
            metaMap.put("extra_credits", 100 + i);
            Metadata metadata = new Metadata(metaMap);
            textSegments.add(new TextSegment("this is a test text " + i, metadata));
            metaMaps.put(i, metaMap);

            hayStack.push("s" + i);
        }

        store.addAll(embeddings, textSegments);

        // filter by a column
        IsIn isIn = new IsIn("string", hayStack);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddings.get(1))
                .maxResults(10)
                .minScore(0.0)
                .filter(isIn)
                .build();

        List<EmbeddingMatch<TextSegment>> result = store.search(request).matches();

        // should return all 10
        assertThat(result.size()).isEqualTo(10);

        for (EmbeddingMatch<TextSegment> match : result) {
            Map<String, Object> matchMetadata = match.embedded().metadata().toMap();
            Integer index = (Integer) matchMetadata.get("integer");
            assertThat(match.embedded().text()).contains("this is a test text " + index);
            // metadata json should be unpacked into the original columns
            for (String column : matchMetadata.keySet()) {
                assertThat(matchMetadata.get(column))
                        .isEqualTo(metaMaps.get(index).get(column));
            }
        }
    }

    @Test
    void search_for_vector_specific_min_score_embedding_model() {
        List<String> testTexts = Arrays.asList("cat", "dog", "car", "truck");
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> textSegments = new ArrayList<>();

        for (String text : testTexts) {
            Map<String, Object> metaMap = new HashMap<>();
            metaMap.put("string", "s" + text);
            metaMap.put("uuid", UUID.randomUUID());
            metaMap.put("integer", 1);
            metaMap.put("long", 1L);
            metaMap.put("float", 1f);
            metaMap.put("double", 1d);
            metaMap.put("extra", "not in table columns ");
            metaMap.put("extra_credits", 100);
            Metadata metadata = new Metadata(metaMap);
            textSegments.add(new TextSegment(text, metadata));
            // using AllMiniLmL6V2QuantizedEmbeddingModel for consistency with other implementations
            embeddings.add(embeddingModel.embed(text).content());
        }

        store.addAll(embeddings, textSegments);
        // search for "cat"
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddings.get(0))
                .maxResults(10)
                .minScore(0.5)
                .build();

        List<EmbeddingMatch<TextSegment>> result = store.search(request).matches();
        // should get 2 hits
        assertThat(result.size()).isEqualTo(2);
        List<String> expectedSearchResult = Arrays.asList("cat", "dog");
        List<String> actualSearchResult = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result) {
            actualSearchResult.add(match.embedded().text());
        }
        assertThat(actualSearchResult).isEqualTo(expectedSearchResult);

        // search for "cat" using a higher minScore
        request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddings.get(0))
                .minScore(0.9)
                .build();

        result = store.search(request).matches();

        // should get 1 hit
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).embedded().text()).isEqualTo("cat");
    }

    @Test
    void search_for_vector_with_null_metadata() {
        List<Embedding> embeddings = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            PGvector vector = randomPGvector(VECTOR_SIZE);
            embeddings.add(new Embedding(vector.toArray()));
        }

        store.addAll(embeddings);

        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("metadata", "I'm not null!");
        TextSegment textSegment = new TextSegment("this is a test text", new Metadata(metaMap));
        String idEmbeddingWithMetadata =
                store.add(new Embedding(randomPGvector(VECTOR_SIZE).toArray()), textSegment);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddings.get(1))
                .maxResults(11)
                .minScore(0.0)
                .build();

        List<EmbeddingMatch<TextSegment>> result = store.search(request).matches();

        // should return all 11
        assertThat(result.size()).isEqualTo(11);

        for (EmbeddingMatch<TextSegment> match : result) {
            if (match.embeddingId().equals(idEmbeddingWithMetadata)) {
                assertThat(match.embedded().text()).isEqualTo("this is a test text");
                assertThat(match.embedded().metadata().toMap().size()).isEqualTo(1);
                assertThat(match.embedded().metadata().getString("metadata")).isEqualTo("I'm not null!");
            } else {
                assertThat(match.embedded()).isNull();
            }
        }
    }

    @Test
    void add_single_embedding_to_store() throws SQLException {
        PGvector vector = randomPGvector(VECTOR_SIZE);
        Embedding embedding = new Embedding(vector.toArray());
        String id = store.add(embedding);

        try (Statement statement = defaultConnection.createStatement(); ) {
            ResultSet rs = statement.executeQuery(String.format(
                    "SELECT \"%s\" FROM \"%s\" WHERE \"%s\" = '%s'",
                    embeddingStoreConfig.getEmbeddingColumn(), TABLE_NAME, embeddingStoreConfig.getIdColumn(), id));
            rs.next();
            PGvector response = (PGvector) rs.getObject(embeddingStoreConfig.getEmbeddingColumn());
            assertThat(response).isEqualTo(vector);
        }
    }

    @Test
    void add_embeddings_list_to_store() throws SQLException {
        List<PGvector> expectedVectors = new ArrayList<>();
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PGvector vector = randomPGvector(VECTOR_SIZE);
            expectedVectors.add(vector);
            embeddings.add(new Embedding(vector.toArray()));
        }
        List<String> ids = store.addAll(embeddings);
        String stringIds = ids.stream().map(id -> String.format("'%s'", id)).collect(Collectors.joining(","));

        try (Statement statement = defaultConnection.createStatement(); ) {
            ResultSet rs = statement.executeQuery(String.format(
                    "SELECT \"%s\" FROM \"%s\" WHERE \"%s\" IN (%s)",
                    embeddingStoreConfig.getEmbeddingColumn(),
                    TABLE_NAME,
                    embeddingStoreConfig.getIdColumn(),
                    stringIds));
            while (rs.next()) {
                PGvector response = (PGvector) rs.getObject(embeddingStoreConfig.getEmbeddingColumn());
                assertThat(expectedVectors).contains(response);
            }
        }
    }

    @Test
    void add_single_embedding_with_id_to_store() throws SQLException {
        PGvector vector = randomPGvector(VECTOR_SIZE);
        Embedding embedding = new Embedding(vector.toArray());
        String id = randomUUID();
        store.add(id, embedding);

        try (Statement statement = defaultConnection.createStatement(); ) {
            ResultSet rs = statement.executeQuery(String.format(
                    "SELECT \"%s\" FROM \"%s\" WHERE \"%s\" = '%s'",
                    embeddingStoreConfig.getEmbeddingColumn(), TABLE_NAME, embeddingStoreConfig.getIdColumn(), id));
            rs.next();
            PGvector response = (PGvector) rs.getObject(embeddingStoreConfig.getEmbeddingColumn());
            assertThat(response).isEqualTo(vector);
        }
    }

    @Test
    void add_single_embedding_with_content_to_store() throws SQLException, JsonProcessingException {
        PGvector vector = randomPGvector(VECTOR_SIZE);
        Embedding embedding = new Embedding(vector.toArray());

        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("string", "s");
        metaMap.put("uuid", UUID.randomUUID());
        metaMap.put("integer", 1);
        metaMap.put("long", 1L);
        metaMap.put("float", 1f);
        metaMap.put("double", 1d);
        metaMap.put("extra", "not in table columns");
        metaMap.put("extra_credits", 10);
        Metadata metadata = new Metadata(metaMap);
        TextSegment textSegment = new TextSegment("this is a test text", metadata);
        String id = store.add(embedding, textSegment);

        String metadataColumnNames = metaMap.entrySet().stream()
                .filter(e -> !e.getKey().contains("extra"))
                .map(e -> "\"" + e.getKey() + "\"")
                .collect(Collectors.joining(", "));

        try (Statement statement = defaultConnection.createStatement(); ) {
            ResultSet rs = statement.executeQuery(String.format(
                    "SELECT \"%s\", %s, \"%s\" FROM \"%s\" WHERE \"%s\" = '%s'",
                    embeddingStoreConfig.getEmbeddingColumn(),
                    metadataColumnNames,
                    embeddingStoreConfig.getMetadataJsonColumn(),
                    TABLE_NAME,
                    embeddingStoreConfig.getIdColumn(),
                    id));
            Map<String, Object> extraMetaMap = new HashMap<>();
            Map<String, Object> metadataJsonMap = null;
            while (rs.next()) {
                PGvector response = (PGvector) rs.getObject(embeddingStoreConfig.getEmbeddingColumn());
                assertThat(response).isEqualTo(vector);
                for (String column : metaMap.keySet()) {
                    if (column.contains("extra")) {
                        extraMetaMap.put(column, metaMap.get(column));
                    } else {
                        assertThat(rs.getObject(column)).isEqualTo(metaMap.get(column));
                    }
                }
                String metadataJsonString =
                        getOrDefault(rs.getString(embeddingStoreConfig.getMetadataJsonColumn()), "{}");
                metadataJsonMap = OBJECT_MAPPER.readValue(metadataJsonString, Map.class);
            }
            assertThat(extraMetaMap.size()).isEqualTo(metadataJsonMap.size());
            for (String key : extraMetaMap.keySet()) {
                assertThat(extraMetaMap.get(key).equals((metadataJsonMap.get(key))))
                        .isTrue();
            }
        }
    }

    @Test
    void add_embeddings_list_and_content_list_to_store() throws SQLException, JsonProcessingException {
        Map<PGvector, Integer> expectedVectorsAndIndexes = new HashMap<>();
        Map<Integer, Map<String, Object>> metaMaps = new HashMap<>();
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> textSegments = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            PGvector vector = randomPGvector(VECTOR_SIZE);
            expectedVectorsAndIndexes.put(vector, i);
            embeddings.add(new Embedding(vector.toArray()));
            Map<String, Object> metaMap = new HashMap<>();
            metaMap.put("string", "s" + i);
            metaMap.put("uuid", UUID.randomUUID());
            metaMap.put("integer", i);
            metaMap.put("long", 1L);
            metaMap.put("float", 1f);
            metaMap.put("double", 1d);
            metaMap.put("extra", "not in table columns " + i);
            metaMap.put("extra_credits", 100 + i);
            metaMaps.put(i, metaMap);
            Metadata metadata = new Metadata(metaMap);
            textSegments.add(new TextSegment("this is a test text " + i, metadata));
        }

        List<String> ids = store.addAll(embeddings, textSegments);
        String stringIds = ids.stream().map(id -> String.format("'%s'", id)).collect(Collectors.joining(","));

        String metadataColumnNames = metaMaps.get(0).entrySet().stream()
                .filter(e -> !e.getKey().contains("extra"))
                .map(e -> "\"" + e.getKey() + "\"")
                .collect(Collectors.joining(", "));

        try (Statement statement = defaultConnection.createStatement(); ) {
            ResultSet rs = statement.executeQuery(String.format(
                    "SELECT \"%s\", %s ,\"%s\" FROM \"%s\" WHERE \"%s\" IN (%s)",
                    embeddingStoreConfig.getEmbeddingColumn(),
                    metadataColumnNames,
                    embeddingStoreConfig.getMetadataJsonColumn(),
                    TABLE_NAME,
                    embeddingStoreConfig.getIdColumn(),
                    stringIds));
            Map<String, Object> extraMetaMap = new HashMap<>();
            Map<String, Object> metadataJsonMap = null;
            while (rs.next()) {
                PGvector response = (PGvector) rs.getObject(embeddingStoreConfig.getEmbeddingColumn());
                assertThat(expectedVectorsAndIndexes.keySet()).contains(response);
                int index = expectedVectorsAndIndexes.get(response);
                for (String column : metaMaps.get(index).keySet()) {
                    if (column.contains("extra")) {
                        extraMetaMap.put(column, metaMaps.get(index).get(column));
                    } else {
                        assertThat(rs.getObject(column))
                                .isEqualTo(metaMaps.get(index).get(column));
                    }
                }
                String metadataJsonString =
                        getOrDefault(rs.getString(embeddingStoreConfig.getMetadataJsonColumn()), "{}");
                metadataJsonMap = OBJECT_MAPPER.readValue(metadataJsonString, Map.class);
            }
            assertThat(metadataJsonMap).isNotNull();
            assertThat(extraMetaMap.size()).isEqualTo(metadataJsonMap.size());
            for (String key : extraMetaMap.keySet()) {
                assertThat(extraMetaMap.get(key).equals((metadataJsonMap.get(key))))
                        .isTrue();
            }
        }
    }

    @Test
    void apply_vector_index() throws SQLException {
        BaseIndex index = new HNSWIndex.Builder().name("test_hnsw").build();
        store.applyVectorIndex(index, null, false);
        verifyIndex(defaultConnection, TABLE_NAME, index.getName(), index.getIndexType());
        store.dropVectorIndex(index.getName());
    }

    @Test
    void drop_vector_index() throws SQLException {
        BaseIndex index = new HNSWIndex.Builder().name("test_hnsw").build();
        store.applyVectorIndex(index, null, false);
        verifyIndex(defaultConnection, TABLE_NAME, index.getName(), index.getIndexType());
        store.dropVectorIndex(index.getName());

        ResultSet indexes = defaultConnection
                .createStatement()
                .executeQuery(String.format(
                        "SELECT indexdef FROM pg_indexes WHERE tablename = '%s' AND indexname = '%s'",
                        TABLE_NAME, index.getName()));
        assertThat(indexes.isBeforeFirst())
                .withFailMessage("there should be no indexes")
                .isFalse();
    }

    @Test
    void vector_store_reindex() throws SQLException {
        BaseIndex index = new HNSWIndex.Builder().build();
        String defaultIndexName = (TABLE_NAME + BaseIndex.DEFAULT_INDEX_NAME_SUFFIX).toLowerCase();
        store.applyVectorIndex(index, null, false);
        verifyIndex(defaultConnection, TABLE_NAME, defaultIndexName, index.getIndexType());
        store.reindex(null);
        store.reindex(defaultIndexName);
        store.dropVectorIndex(index.getName());
    }

    @Test
    void apply_vector_index_ivfflat() throws SQLException {
        BaseIndex index = new IVFFlatIndex.Builder()
                .distanceStrategy(DistanceStrategy.EUCLIDEAN)
                .name("test_ivfflat")
                .build();
        store.applyVectorIndex(index, index.getName(), true);
        verifyIndex(defaultConnection, TABLE_NAME, index.getName(), index.getIndexType());

        BaseIndex secondIndex;
        secondIndex = new IVFFlatIndex.Builder()
                .distanceStrategy(DistanceStrategy.INNER_PRODUCT)
                .name("second_ivfflat")
                .build();
        store.applyVectorIndex(secondIndex, secondIndex.getName(), false);
        verifyIndex(defaultConnection, TABLE_NAME, secondIndex.getName(), secondIndex.getIndexType());

        store.dropVectorIndex(index.getName());
        store.dropVectorIndex(secondIndex.getName());
    }
}
