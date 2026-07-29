package com.agi.assistant.infrastructure;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.agi.assistant.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class InfrastructureService {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureService.class);
    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String RAG_INDEX = "rag_chunks";
    private static final String RAG_COLLECTION = "rag_chunks";

    private final AppConfig cfg;
    private Connection pgConn;
    private MilvusClient milvus;
    private ElasticsearchClient es;
    private RestClient esRest;
    private KafkaProducer<String, String> kafka;

    private String milvusStatus = "disconnected";
    private String pgStatus = "disconnected";
    private String esStatus = "disconnected";
    private String kafkaStatus = "disconnected";

    public InfrastructureService(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        connectPostgres();
        connectMilvus();
        connectES();
        connectKafka();
    }

    // ================= Postgres =================

    private void connectPostgres() {
        try {
            String url = cfg.getPgJdbcUrl();
            pgConn = DriverManager.getConnection(url, cfg.getPostgres().getUser(), cfg.getPostgres().getPassword());
            pgStatus = "connected";
            initPGSchema();
            log.info("PostgreSQL 已连接: {}", url);
        } catch (Exception e) {
            log.warn("PostgreSQL 连接失败: {} (将使用内存模式)", e.getMessage());
            pgStatus = "disconnected";
        }
    }

    private void initPGSchema() {
        if (pgConn == null) return;
        String[] ddls = {
                """
                CREATE TABLE IF NOT EXISTS user_preferences (
                    user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
                    updated_at TIMESTAMP DEFAULT NOW(), PRIMARY KEY (user_id, key))""",
                """
                CREATE TABLE IF NOT EXISTS task_snapshots (
                    task_id TEXT PRIMARY KEY, state JSONB NOT NULL, created_at TIMESTAMP DEFAULT NOW())""",
                """
                CREATE TABLE IF NOT EXISTS chat_history (
                    id SERIAL PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP DEFAULT NOW())""",
                """
                CREATE TABLE IF NOT EXISTS long_term_memory (
                    id SERIAL PRIMARY KEY, content TEXT NOT NULL, importance FLOAT NOT NULL DEFAULT 0.5,
                    embedding JSONB, created_at TIMESTAMP DEFAULT NOW(), last_accessed TIMESTAMP DEFAULT NOW())""",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT NOW()",
                """
                CREATE TABLE IF NOT EXISTS rag_chunks (
                    id BIGSERIAL PRIMARY KEY, doc_hash TEXT NOT NULL, chunk_idx INT NOT NULL,
                    content TEXT NOT NULL, embedding JSONB, created_at TIMESTAMP DEFAULT NOW(),
                    UNIQUE(doc_hash, chunk_idx))"""
        };
        try (Statement stmt = pgConn.createStatement()) {
            for (String ddl : ddls) stmt.execute(ddl);
            log.info("PostgreSQL 表结构已初始化");
        } catch (SQLException e) {
            log.warn("PG 建表失败: {}", e.getMessage());
        }
    }

    // ================= Milvus =================

    private void connectMilvus() {
        try {
            ConnectParam param = ConnectParam.newBuilder()
                    .withHost(cfg.getMilvus().getHost())
                    .withPort(cfg.getMilvus().getPort())
                    .withConnectTimeout(5, TimeUnit.SECONDS)
                    .build();
            MilvusServiceClient client = new MilvusServiceClient(param);
            // probe
            R<Boolean> probe = client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName("__probe__").build());
            if (probe.getStatus() != R.Status.Success.getCode()
                    && probe.getStatus() != R.Status.CollectionNotExists.getCode()) {
                // Some versions return different statuses; if rpc unreachable we'll fail here
            }
            milvus = client;
            milvusStatus = "connected";
            log.info("Milvus 已连接: {}", cfg.getMilvusAddr());
        } catch (Exception e) {
            log.warn("Milvus 连接失败: {} (将使用内存向量库)", e.getMessage());
            milvusStatus = "disconnected";
        }
    }

    // ================= Elasticsearch =================

    private void connectES() {
        try {
            List<String> addrs = cfg.getElasticsearch().getAddressList();
            if (addrs.isEmpty()) throw new IllegalStateException("ES addresses 为空");
            HttpHost[] hosts = addrs.stream().map(HttpHost::create).toArray(HttpHost[]::new);
            BasicCredentialsProvider creds = new BasicCredentialsProvider();
            String user = cfg.getElasticsearch().getUsername();
            String pass = cfg.getElasticsearch().getPassword();
            if (user != null && !user.isEmpty()) {
                creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pass));
            }
            esRest = RestClient.builder(hosts)
                    .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(creds))
                    .build();
            // Probe: ping cluster info
            esRest.performRequest(new org.elasticsearch.client.Request("GET", "/"));
            es = new ElasticsearchClient(new RestClientTransport(esRest, new JacksonJsonpMapper()));
            esStatus = "connected";
            log.info("Elasticsearch 已连接: {}", addrs);
        } catch (Exception e) {
            log.warn("Elasticsearch 连接失败: {} (将使用 TF 降级检索)", e.getMessage());
            esStatus = "disconnected";
            if (esRest != null) {
                try { esRest.close(); } catch (Exception ignored) {}
                esRest = null;
            }
        }
    }

    // ================= Kafka =================

    private void connectKafka() {
        try {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getKafka().getBrokers());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringSerializer");
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringSerializer");
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");
            kafka = new KafkaProducer<>(props);
            // Probe: list partitions for the topic
            kafka.partitionsFor(cfg.getKafka().getTopic());
            kafkaStatus = "connected";
            log.info("Kafka 已连接: {}", cfg.getKafka().getBrokers());
        } catch (Exception e) {
            log.warn("Kafka 连接失败: {} (事件将输出到日志)", e.getMessage());
            kafkaStatus = "disconnected";
            if (kafka != null) {
                try { kafka.close(); } catch (Exception ignored) {}
                kafka = null;
            }
        }
    }

    // ================= Status =================

    public Map<String, String> getStatus() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("milvus", milvusStatus);
        s.put("pg", pgStatus);
        s.put("elasticsearch", esStatus);
        s.put("kafka", kafkaStatus);
        return s;
    }

    public String getMilvusStatus() { return milvusStatus; }
    public String getPgStatus() { return pgStatus; }
    public String getEsStatus() { return esStatus; }
    public String getKafkaStatus() { return kafkaStatus; }

    // ================= Preferences =================

    public void savePreference(String userId, String key, String value) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO user_preferences (user_id, key, value) VALUES (?, ?, ?) ON CONFLICT (user_id, key) DO UPDATE SET value = ?, updated_at = NOW()")) {
            ps.setString(1, userId); ps.setString(2, key); ps.setString(3, value); ps.setString(4, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("偏好保存失败: {}", e.getMessage());
        }
    }

    public Map<String, String> loadPreferences(String userId) {
        Map<String, String> result = new LinkedHashMap<>();
        if (pgConn == null) return result;
        try (PreparedStatement ps = pgConn.prepareStatement("SELECT key, value FROM user_preferences WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            log.warn("加载偏好失败: {}", e.getMessage());
        }
        return result;
    }

    // ================= Long-term Memory =================

    public int saveLongTermItem(String content, double importance, String embeddingJson) {
        if (pgConn == null) return -1;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO long_term_memory (content, importance, embedding) VALUES (?, ?, ?::jsonb) RETURNING id")) {
            ps.setString(1, content); ps.setDouble(2, importance); ps.setString(3, embeddingJson);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("长期记忆保存失败: {}", e.getMessage());
        }
        return -1;
    }

    public static class LongTermRow {
        public int id; public String content; public double importance;
        public List<Double> embedding; public Timestamp createdAt; public Timestamp lastAccessed;
    }

    public List<LongTermRow> loadLongTermItems() {
        List<LongTermRow> items = new ArrayList<>();
        if (pgConn == null) return items;
        try (Statement stmt = pgConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, content, importance, embedding, COALESCE(created_at, NOW()), COALESCE(last_accessed, NOW()) FROM long_term_memory ORDER BY id")) {
            while (rs.next()) {
                LongTermRow row = new LongTermRow();
                row.id = rs.getInt(1); row.content = rs.getString(2); row.importance = rs.getDouble(3);
                String embJson = rs.getString(4);
                if (embJson != null && !embJson.isEmpty()) {
                    try { row.embedding = mapper.readValue(embJson,
                            mapper.getTypeFactory().constructCollectionType(List.class, Double.class)); } catch (Exception ignored) {}
                }
                row.createdAt = rs.getTimestamp(5); row.lastAccessed = rs.getTimestamp(6);
                items.add(row);
            }
        } catch (SQLException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage());
        }
        return items;
    }

    public void updateLongTermItem(int id, String content, double importance, String embeddingJson) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "UPDATE long_term_memory SET content = ?, importance = ?, embedding = ?::jsonb, last_accessed = NOW() WHERE id = ?")) {
            ps.setString(1, content); ps.setDouble(2, importance); ps.setString(3, embeddingJson); ps.setInt(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆更新失败 (id={}): {}", id, e.getMessage());
        }
    }

    public void deleteLongTermItems(List<Integer> ids) {
        if (pgConn == null || ids.isEmpty()) return;
        String placeholders = String.join(",", ids.stream().map(i -> "?").toArray(String[]::new));
        try (PreparedStatement ps = pgConn.prepareStatement("DELETE FROM long_term_memory WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) ps.setInt(i + 1, ids.get(i));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆批量删除失败: {}", e.getMessage());
        }
    }

    // ================= RAG Chunks =================

    public long saveRAGChunk(String docHash, int chunkIdx, String content, String embeddingJson) {
        if (pgConn == null) return -1;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO rag_chunks (doc_hash, chunk_idx, content, embedding) VALUES (?, ?, ?, ?::jsonb) " +
                        "ON CONFLICT (doc_hash, chunk_idx) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding RETURNING id")) {
            ps.setString(1, docHash); ps.setInt(2, chunkIdx); ps.setString(3, content); ps.setString(4, embeddingJson);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.warn("RAG chunk 保存失败: {}", e.getMessage());
        }
        return -1;
    }

    public static class ChunkRow {
        public long id; public String content;
    }

    public List<ChunkRow> loadAllRAGChunks() {
        List<ChunkRow> chunks = new ArrayList<>();
        if (pgConn == null) return chunks;
        try (Statement stmt = pgConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, content FROM rag_chunks ORDER BY id")) {
            while (rs.next()) {
                ChunkRow r = new ChunkRow(); r.id = rs.getLong(1); r.content = rs.getString(2);
                chunks.add(r);
            }
        } catch (SQLException e) {
            log.warn("加载 RAG chunks 失败: {}", e.getMessage());
        }
        return chunks;
    }

    public List<ChunkRow> loadRAGChunksByIDs(List<Long> ids) {
        List<ChunkRow> chunks = new ArrayList<>();
        if (pgConn == null || ids.isEmpty()) return chunks;
        String placeholders = String.join(",", ids.stream().map(i -> "?").toArray(String[]::new));
        try (PreparedStatement ps = pgConn.prepareStatement("SELECT id, content FROM rag_chunks WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChunkRow r = new ChunkRow(); r.id = rs.getLong(1); r.content = rs.getString(2);
                    chunks.add(r);
                }
            }
        } catch (SQLException e) {
            log.warn("按 ID 加载 RAG chunks 失败: {}", e.getMessage());
        }
        return chunks;
    }

    public List<Long> deleteRAGChunksByDocHash(String docHash) {
        List<Long> ids = new ArrayList<>();
        if (pgConn == null) return ids;
        try (PreparedStatement ps = pgConn.prepareStatement("SELECT id FROM rag_chunks WHERE doc_hash = ?")) {
            ps.setString(1, docHash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        } catch (SQLException e) { log.warn("查询 RAG chunks 失败: {}", e.getMessage()); }
        if (!ids.isEmpty()) {
            try (PreparedStatement ps = pgConn.prepareStatement("DELETE FROM rag_chunks WHERE doc_hash = ?")) {
                ps.setString(1, docHash); ps.executeUpdate();
            } catch (SQLException e) { log.warn("删除 RAG chunks 失败: {}", e.getMessage()); }
        }
        return ids;
    }

    // ================= Chat History =================

    public void saveChatHistory(String role, String content) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement("INSERT INTO chat_history (role, content) VALUES (?, ?)")) {
            ps.setString(1, role); ps.setString(2, content); ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("聊天记录保存失败: {}", e.getMessage());
        }
    }

    public static class ChatHistoryRow {
        public String role; public String content; public String createdAt;
    }

    public List<ChatHistoryRow> loadChatHistory(int limit) {
        List<ChatHistoryRow> rows = new ArrayList<>();
        if (pgConn == null) return rows;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "SELECT role, content, TO_CHAR(created_at, 'HH24:MI:SS') FROM chat_history ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatHistoryRow r = new ChatHistoryRow();
                    r.role = rs.getString(1); r.content = rs.getString(2); r.createdAt = rs.getString(3);
                    rows.add(r);
                }
            }
        } catch (SQLException e) {
            log.warn("加载聊天记录失败: {}", e.getMessage());
        }
        Collections.reverse(rows);
        return rows;
    }

    // ================= Snapshot =================

    public void saveSnapshot(String taskId, String stateJson) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO task_snapshots (task_id, state) VALUES (?, ?::jsonb) ON CONFLICT (task_id) DO UPDATE SET state = ?::jsonb, created_at = NOW()")) {
            ps.setString(1, taskId); ps.setString(2, stateJson); ps.setString(3, stateJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("快照保存失败: {}", e.getMessage());
        }
    }

    // ================= Kafka =================

    public void publishEvent(String eventType, String payload) {
        if ("connected".equals(kafkaStatus) && kafka != null) {
            try {
                kafka.send(new ProducerRecord<>(cfg.getKafka().getTopic(), eventType, payload));
            } catch (Exception e) {
                log.warn("Kafka 写入失败: {}", e.getMessage());
            }
        } else {
            log.info("[Kafka-fallback] {}: {}", eventType, payload);
        }
    }

    // ================= RAG Infrastructure (Milvus + ES collections) =================

    public void initRAGInfra(int dim) {
        if ("connected".equals(milvusStatus)) {
            try { ensureRAGCollection(dim); }
            catch (Exception e) { log.warn("Milvus rag_chunks 初始化失败: {}", e.getMessage()); }
        }
        if ("connected".equals(esStatus)) {
            try { ensureRAGIndex(); }
            catch (Exception e) { log.warn("ES rag_chunks 初始化失败: {}", e.getMessage()); }
        }
    }

    private void ensureRAGCollection(int dim) {
        if (milvus == null) return;
        R<Boolean> hasResp = milvus.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(RAG_COLLECTION).build());
        boolean has = hasResp.getStatus() == R.Status.Success.getCode() && Boolean.TRUE.equals(hasResp.getData());

        if (has) {
            R<io.milvus.grpc.DescribeCollectionResponse> desc = milvus.describeCollection(
                    DescribeCollectionParam.newBuilder().withCollectionName(RAG_COLLECTION).build());
            boolean needRecreate = false;
            if (desc.getStatus() == R.Status.Success.getCode()) {
                DescCollResponseWrapper w = new DescCollResponseWrapper(desc.getData());
                for (FieldType f : w.getFields()) {
                    if ("embedding".equals(f.getName()) && f.getDataType() == DataType.FloatVector) {
                        Map<String, String> tp = f.getTypeParams();
                        String existing = tp == null ? "" : tp.getOrDefault("dim", "");
                        if (!String.valueOf(dim).equals(existing)) {
                            log.warn("Milvus rag_chunks 维度不匹配 (现有={}, 期望={})，重建", existing, dim);
                            needRecreate = true;
                        }
                    }
                    if ("id".equals(f.getName()) && f.isPrimaryKey()) {
                        log.warn("Milvus rag_chunks 主键为 id (应为 pg_id)，重建");
                        needRecreate = true;
                    }
                }
            }
            if (needRecreate) {
                milvus.dropCollection(DropCollectionParam.newBuilder()
                        .withCollectionName(RAG_COLLECTION).build());
                has = false;
            }
            if (has) return;
        }

        FieldType pgIdField = FieldType.newBuilder().withName("pg_id")
                .withDataType(DataType.Int64).withPrimaryKey(true).withAutoID(false).build();
        FieldType contentField = FieldType.newBuilder().withName("content")
                .withDataType(DataType.VarChar).withMaxLength(4096).build();
        FieldType embField = FieldType.newBuilder().withName("embedding")
                .withDataType(DataType.FloatVector).withDimension(dim).build();
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(RAG_COLLECTION).withShardsNum(1)
                .addFieldType(pgIdField).addFieldType(contentField).addFieldType(embField).build();
        R<RpcStatus> r = milvus.createCollection(createParam);
        if (r.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("create rag_chunks 失败: " + r.getMessage());
        }
        // Index on embedding
        try {
            milvus.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.L2)
                    .withExtraParam("{\"nlist\":128}")
                    .build());
        } catch (Exception e) {
            log.warn("Milvus rag_chunks 索引创建失败: {}", e.getMessage());
        }
        try {
            milvus.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION).build());
        } catch (Exception e) {
            log.warn("Milvus rag_chunks 加载失败: {}", e.getMessage());
        }
        log.info("Milvus rag_chunks collection 已创建");
    }

    private void ensureRAGIndex() throws IOException {
        if (es == null) return;
        boolean exists = es.indices().exists(ExistsRequest.of(b -> b.index(RAG_INDEX))).value();
        if (exists) return;
        String mapping = """
                {
                  "mappings": {
                    "properties": {
                      "pg_id": {"type": "long"},
                      "content": {"type": "text", "analyzer": "standard"},
                      "doc_hash": {"type": "keyword"},
                      "chunk_idx": {"type": "integer"}
                    }
                  }
                }""";
        es.indices().create(CreateIndexRequest.of(c -> c
                .index(RAG_INDEX)
                .withJson(new StringReader(mapping))));
        log.info("ES rag_chunks 索引已创建");
    }

    public void insertRAGChunks(List<Long> pgIds, List<String> contents, List<List<Float>> embeddings) {
        if (milvus == null || pgIds.isEmpty()) return;
        try {
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("pg_id", new ArrayList<>(pgIds)));
            fields.add(new InsertParam.Field("content", new ArrayList<>(contents)));
            fields.add(new InsertParam.Field("embedding", new ArrayList<>(embeddings)));
            milvus.insert(InsertParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION)
                    .withFields(fields).build());
        } catch (Exception e) {
            log.warn("Milvus 插入 RAG chunks 失败: {}", e.getMessage());
        }
    }

    public void indexRAGChunkInES(long pgId, String content, String docHash, int chunkIdx) {
        if (es == null) return;
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("pg_id", pgId);
            doc.put("content", content);
            doc.put("doc_hash", docHash);
            doc.put("chunk_idx", chunkIdx);
            es.index(IndexRequest.of(b -> b
                    .index(RAG_INDEX)
                    .id(String.valueOf(pgId))
                    .document(doc)
                    .refresh(Refresh.False)));
        } catch (Exception e) {
            log.warn("ES 索引 RAG chunk (pg_id={}) 失败: {}", pgId, e.getMessage());
        }
    }

    public void deleteRAGChunksFromES(List<Long> pgIds) {
        if (es == null || pgIds.isEmpty()) return;
        for (long id : pgIds) {
            try {
                es.delete(d -> d.index(RAG_INDEX).id(String.valueOf(id)));
            } catch (Exception e) {
                log.warn("ES 删除文档 (pg_id={}) 失败: {}", id, e.getMessage());
            }
        }
    }

    public void deleteRAGChunksFromMilvus(List<Long> pgIds) {
        if (milvus == null || pgIds.isEmpty()) return;
        StringBuilder sb = new StringBuilder("pg_id in [");
        for (int i = 0; i < pgIds.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(pgIds.get(i));
        }
        sb.append("]");
        try {
            milvus.delete(DeleteParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION)
                    .withExpr(sb.toString()).build());
        } catch (Exception e) {
            log.warn("Milvus 删除 RAG chunks 失败: {}", e.getMessage());
        }
    }

    public static class MilvusHit {
        public long id; public float distance;
        public MilvusHit(long id, float distance) { this.id = id; this.distance = distance; }
    }

    public List<MilvusHit> milvusSearchWithScores(List<Float> vector, int topK) {
        List<MilvusHit> hits = new ArrayList<>();
        if (milvus == null) return hits;
        try {
            List<List<Float>> vectors = new ArrayList<>();
            vectors.add(vector);
            R<SearchResults> resp = milvus.search(SearchParam.newBuilder()
                    .withCollectionName(RAG_COLLECTION)
                    .withMetricType(MetricType.L2)
                    .withTopK(topK)
                    .withVectors(vectors)
                    .withVectorFieldName("embedding")
                    .withParams("{\"nprobe\":10}")
                    .addOutField("pg_id")
                    .build());
            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus 检索失败: {}", resp.getMessage());
                return hits;
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<?> ids = wrapper.getFieldData("pg_id", 0);
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            int n = scores.size();
            for (int i = 0; i < n; i++) {
                long id = ids != null && i < ids.size() && ids.get(i) instanceof Long ? (Long) ids.get(i)
                        : scores.get(i).getLongID();
                hits.add(new MilvusHit(id, scores.get(i).getScore()));
            }
        } catch (Exception e) {
            log.warn("Milvus 检索异常: {}", e.getMessage());
        }
        return hits;
    }

    public static class ESHit {
        public long pgId; public double score;
        public ESHit(long pgId, double score) { this.pgId = pgId; this.score = score; }
    }

    public List<ESHit> searchRAGChunks(String query, int topK) {
        List<ESHit> hits = new ArrayList<>();
        if (es == null) return hits;
        try {
            SearchResponse<Map> resp = es.search(SearchRequest.of(s -> s
                    .index(RAG_INDEX)
                    .size(topK)
                    .query(Query.of(q -> q.match(MatchQuery.of(m -> m
                            .field("content").query(query)))))
                    .source(src -> src.filter(f -> f.includes("pg_id")))), Map.class);
            for (Hit<Map> hit : resp.hits().hits()) {
                Object pid = hit.source() != null ? hit.source().get("pg_id") : null;
                long pgId;
                if (pid instanceof Number n) pgId = n.longValue();
                else if (hit.id() != null) {
                    try { pgId = Long.parseLong(hit.id()); } catch (Exception ex) { continue; }
                } else continue;
                hits.add(new ESHit(pgId, hit.score() == null ? 0 : hit.score()));
            }
        } catch (Exception e) {
            log.warn("ES 检索失败: {}", e.getMessage());
        }
        return hits;
    }

    @PreDestroy
    public void close() {
        if (pgConn != null) {
            try { pgConn.close(); } catch (SQLException ignored) {}
        }
        if (milvus != null) {
            try { milvus.close(3); } catch (Exception ignored) {}
        }
        if (esRest != null) {
            try { esRest.close(); } catch (Exception ignored) {}
        }
        if (kafka != null) {
            try { kafka.close(); } catch (Exception ignored) {}
        }
    }
}
