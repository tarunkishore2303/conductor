package com.tarunkishore.loom_api.repository;

import com.tarunkishore.loom_api.ai.AiOutputException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small pgvector adapter. Every query is application-owned and parameterized;
 * the model never supplies SQL or table names.
 */
@Repository
public class IncidentVectorRepository {
    public static final int DIMENSIONS = 768;
    private final JdbcTemplate jdbc;

    public IncidentVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean store(UUID analysisId, UUID jobId, UUID taskId, String model,
                         String document, float[] vector) {
        identifiers(analysisId, jobId, taskId);
        text(model, 128, "Embedding model");
        text(document, 4000, "Incident document");
        String literal = vectorLiteral(vector);
        Integer linked = jdbc.queryForObject("""
                SELECT count(*) FROM ai_failure_analyses a JOIN tasks t ON t.job_id = a.job_id
                WHERE a.id = ? AND a.job_id = ? AND t.id = ?
                """, Integer.class, analysisId, jobId, taskId);
        if (linked == null || linked != 1) {
            throw new IllegalArgumentException("Incident task and analysis must belong to the supplied run");
        }
        return jdbc.update("""
                INSERT INTO ai_incident_embeddings
                    (id, analysis_id, job_id, task_id, embedding_model, embedding, document_text)
                VALUES (?, ?, ?, ?, ?, CAST(? AS vector), ?)
                ON CONFLICT (analysis_id, task_id, embedding_model) DO NOTHING
                """, UUID.randomUUID(), analysisId, jobId, taskId, model, literal, document) == 1;
    }

    public List<Match> search(UUID excludedJobId, String model, float[] query, int topK,
                              double minSimilarity) {
        if (excludedJobId == null) throw new IllegalArgumentException("Current run must be supplied");
        text(model, 128, "Embedding model");
        if (topK < 1 || topK > 5 || !Double.isFinite(minSimilarity)
                || minSimilarity < 0 || minSimilarity > 1) {
            throw new IllegalArgumentException("Search requires topK 1 to 5 and similarity 0 to 1");
        }
        String literal = vectorLiteral(query);
        return jdbc.query("""
                SELECT id, analysis_id, job_id, task_id, document_text,
                    1 - (embedding <=> CAST(? AS vector)) AS similarity
                FROM ai_incident_embeddings
                WHERE embedding_model = ? AND job_id <> ?
                    AND (embedding <=> CAST(? AS vector)) <= ?
                ORDER BY embedding <=> CAST(? AS vector), id
                LIMIT ?
                """, (rs, row) -> new Match(
                        rs.getObject("id", UUID.class), rs.getObject("analysis_id", UUID.class),
                        rs.getObject("job_id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getString("document_text"), rs.getDouble("similarity")),
                literal, model, excludedJobId, literal, 1 - minSimilarity, literal, topK);
    }

    public List<VectorRecord> findTaskEmbeddings(UUID analysisId, String model) {
        if (analysisId == null) throw new IllegalArgumentException("Analysis must be supplied");
        text(model, 128, "Embedding model");
        return jdbc.query("""
                SELECT id, analysis_id, job_id, task_id, document_text, embedding::text AS vector_text
                FROM ai_incident_embeddings WHERE analysis_id = ? AND embedding_model = ?
                ORDER BY task_id
                """, (rs, row) -> new VectorRecord(
                        rs.getObject("id", UUID.class), rs.getObject("analysis_id", UUID.class),
                        rs.getObject("job_id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getString("document_text"), parseVector(rs.getString("vector_text"))),
                analysisId, model);
    }

    static String vectorLiteral(float[] vector) {
        validateVector(vector);
        var parts = new ArrayList<String>(DIMENSIONS);
        for (float coordinate : vector) parts.add(Float.toString(coordinate));
        return "[" + String.join(",", parts) + "]";
    }

    static float[] parseVector(String literal) {
        if (literal == null || !literal.startsWith("[") || !literal.endsWith("]")) {
            throw new AiOutputException("Invalid stored embedding");
        }
        String[] coordinates = literal.substring(1, literal.length() - 1).split(",", -1);
        if (coordinates.length != DIMENSIONS) throw new AiOutputException("Invalid embedding dimensions");
        float[] vector = new float[DIMENSIONS];
        try {
            for (int i = 0; i < DIMENSIONS; i++) vector[i] = Float.parseFloat(coordinates[i]);
        } catch (NumberFormatException exception) {
            throw new AiOutputException("Invalid stored embedding");
        }
        validateVector(vector);
        return vector;
    }

    public static void validateVector(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) {
            throw new AiOutputException("Embedding must have exactly " + DIMENSIONS + " coordinates");
        }
        double norm = 0;
        for (float coordinate : vector) {
            if (!Float.isFinite(coordinate) || Math.abs(coordinate) > 1_000_000) {
                throw new AiOutputException("Embedding coordinates must be finite and bounded");
            }
            norm += (double) coordinate * coordinate;
        }
        if (norm == 0) throw new AiOutputException("Zero embeddings cannot support cosine similarity");
    }

    private static void identifiers(UUID analysis, UUID job, UUID task) {
        if (analysis == null || job == null || task == null) {
            throw new IllegalArgumentException("Incident identifiers must be supplied");
        }
    }

    private static void text(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " must contain 1 to " + limit + " characters");
        }
    }

    public record Match(UUID id, UUID analysisId, UUID jobId, UUID taskId,
                        String document, double similarity) {}

    public record VectorRecord(UUID id, UUID analysisId, UUID jobId, UUID taskId,
                               String document, float[] embedding) {}
}
