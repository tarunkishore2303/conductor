package com.tarunkishore.loom_api.ai.optimization;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationOutcome;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationFacts;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationCandidate;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationType;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationReport;
import com.tarunkishore.loom_api.ai.optimization.application.OptimizationContextService;
import com.tarunkishore.loom_api.ai.optimization.client.WorkflowOptimizationClient;

import static org.assertj.core.api.Assertions.*;
import com.loom.common.dto.TaskDefinition;
import com.loom.capability.*;
import com.tarunkishore.loom_api.exception.AiUnavailableException;
import com.tarunkishore.loom_api.repository.AiWorkflowOptimizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@Import(WorkflowOptimizationIT.Fakes.class)
class WorkflowOptimizationIT {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @TestConfiguration static class Fakes {
        @Bean @Primary FakeOptimizationClient client() { return new FakeOptimizationClient(); }
    }
    static class FakeOptimizationClient implements WorkflowOptimizationClient {
        final AtomicInteger calls = new AtomicInteger();
        boolean unavailable;
        public Result interpret(OptimizationFacts facts) {
            calls.incrementAndGet();
            if (unavailable) throw new AiUnavailableException();
            return new Result(new Interpretation("Advisory interpretation of recorded evidence", facts.candidates().stream()
                    .map(c -> new Recommendation(List.of(c.candidateId()), "Investigate recorded evidence", c.title())).toList()), "fake-local");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired FakeOptimizationClient client;
    @Autowired ObjectMapper mapper;
    @Autowired OptimizationContextService contexts;
    @Autowired AiWorkflowOptimizationRepository reports;
    @BeforeEach void reset() { client.calls.set(0); client.unavailable = false; }

    UUID seed(int runs) {
        UUID workflow = UUID.randomUUID();
        var definitions = List.of(new TaskDefinition("root", "root", List.of(), 0),
                new TaskDefinition("wait", "wait", List.of("root"), 0, TaskType.DELAY, new DelayTaskConfig(1000)),
                new TaskDefinition("notify", "notify", List.of("root"), 2, TaskType.HTTP_REQUEST,
                        new HttpRequestTaskConfig("GET", "https://example.test/notify", 1000, null, null)));
        jdbc.update("insert into workflow_templates(id,name,dag_definition_json,default_failure_policy,created_at,updated_at) values(?,?,?,'FAIL_FAST',now(),now())",
                workflow,"optimization-it",mapper.writeValueAsString(definitions));
        for (int i = 0; i < runs; i++) addRun(workflow, i);
        return workflow;
    }
    void addRun(UUID workflow, int index) {
        UUID job = UUID.randomUUID(), root = UUID.randomUUID(), wait = UUID.randomUUID(), notify = UUID.randomUUID();
        boolean failed = index % 2 == 0;
        jdbc.update("insert into jobs(id,name,status,failure_policy,created_at,updated_at,workflow_template_id) values(?,?,?,'CONTINUE_ON_FAILURE',now(),now(),?)",
                job,"run",failed ? "FAILED" : "COMPLETE", workflow);
        for (var row : List.of(new Object[]{root,"root","NOOP","{}",0,"COMPLETE"},
                new Object[]{wait,"wait","DELAY","{\"durationMillis\":1000}",0,"COMPLETE"},
                new Object[]{notify,"notify","HTTP_REQUEST","{\"method\":\"GET\",\"url\":\"https://example.test/notify\",\"timeoutMillis\":1000}",2,failed ? "DEAD_LETTERED" : "COMPLETE"})) {
            jdbc.update("insert into tasks(id,job_id,name,logical_id,task_type,task_config_json,max_retries,status,retry_count,created_at,updated_at,dead_lettered_at) values(?,?,?,?,?,?,?,?,?,now(),now(),CASE WHEN ? THEN now() ELSE NULL END)",
                    row[0],job,row[1],row[1],row[2],row[3],row[4],row[5],failed && row[0].equals(notify) ? 2 : 0, failed && row[0].equals(notify));
        }
        jdbc.update("insert into task_dependencies values(?,?),(?,?)",wait,root,notify,root);
        attempt(root,0,"COMPLETE",10,null); attempt(wait,0,"COMPLETE",1000,null);
        if (failed) for (int n=0;n<3;n++) attempt(notify,n,"FAILED",40,"HTTP_STATUS_500");
        else attempt(notify,0,"COMPLETE",40,null);
    }
    void attempt(UUID task, int number, String status, long millis, String error) {
        jdbc.update("insert into task_executions(id,task_id,execution_id,status,attempt_number,started_at,completed_at,error_type) values(?,?,?,?,?,now()- (? * interval '1 millisecond'),now(),?)",
                UUID.randomUUID(),task,UUID.randomUUID(),status,number,millis,error);
    }
    String path(UUID id) { return "/api/v1/ai/workflows/"+id+"/optimization"; }
    @Test void realQueriesAggregateRetryDlqDurationAndCacheImmutableSnapshot() {
        UUID workflow=seed(6);
        String originalDag = jdbc.queryForObject("select dag_definition_json from workflow_templates where id=?", String.class, workflow);
        long jobsBefore = jdbc.queryForObject("select count(*) from jobs where workflow_template_id=?", Long.class, workflow);
        var response=http.postForEntity(path(workflow),null,OptimizationReport.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var first=response.getBody();
        assertThat(jdbc.queryForObject("select dag_definition_json from workflow_templates where id=?", String.class, workflow)).isEqualTo(originalDag);
        assertThat(jdbc.queryForObject("select count(*) from jobs where workflow_template_id=?", Long.class, workflow)).isEqualTo(jobsBefore);
        assertThat(first.facts().runsConsidered()).isEqualTo(6);
        assertThat(first.facts().candidates()).extracting(OptimizationCandidate::type).contains(
                OptimizationType.SLOW_TASK,OptimizationType.HIGH_RETRY_RATE,OptimizationType.FAILURE_HOTSPOT,
                OptimizationType.DLQ_HOTSPOT,OptimizationType.DURATION_IMBALANCE,OptimizationType.PARALLEL_EXECUTION_OBSERVATION);
        var retry=first.facts().candidates().stream().filter(c->c.type()==OptimizationType.HIGH_RETRY_RATE).findFirst().orElseThrow().evidence();
        assertThat(retry.recordedRetries()).isEqualTo(6); assertThat(retry.ratePercent()).isEqualTo(50.0);
        assertThat(client.calls.get()).isEqualTo(1);
        client.unavailable=true;
        assertThat(http.postForObject(path(workflow),null,OptimizationReport.class)).isEqualTo(first);
        assertThat(http.getForObject(path(workflow),OptimizationReport.class)).isEqualTo(first);
        assertThat(reports.findAll().stream().filter(r->r.getWorkflowId().equals(workflow))).hasSize(1);
        addRun(workflow,6);
        assertThat(http.postForEntity(path(workflow),null,String.class).getStatusCode().value()).isEqualTo(503);
        client.unavailable=false;
        var next=http.postForObject(path(workflow),null,OptimizationReport.class);
        assertThat(next.contextHash()).isNotEqualTo(first.contextHash());
        assertThat(next.optimizationId()).isNotEqualTo(first.optimizationId());
    }
    @Test void insufficientLinkedHistoryBypassesProviderAndUnknownWorkflowIs404() {
        UUID workflow=seed(2); client.unavailable=true;
        var report=http.postForObject(path(workflow),null,OptimizationReport.class);
        assertThat(report.facts().outcome()).isEqualTo(OptimizationOutcome.INSUFFICIENT_HISTORY);
        assertThat(report.model()).isEqualTo("deterministic"); assertThat(client.calls.get()).isZero();
        assertThat(http.postForEntity(path(UUID.randomUUID()),null,String.class).getStatusCode().value()).isEqualTo(404);
    }
    @Test void updatedAtNoiseDoesNotChangeEvidenceAndUnlinkedHistoryIsExcluded() {
        UUID workflow=seed(6);
        var first=http.postForObject(path(workflow),null,OptimizationReport.class);
        jdbc.update("update jobs set updated_at=now()+interval '1 hour' where workflow_template_id=?",workflow);
        jdbc.update("update tasks set updated_at=now()+interval '1 hour' where job_id in(select id from jobs where workflow_template_id=?)",workflow);
        assertThat(http.postForObject(path(workflow),null,OptimizationReport.class).optimizationId()).isEqualTo(first.optimizationId());
        jdbc.update("update jobs set workflow_template_id=null where workflow_template_id=?",workflow);
        assertThat(contexts.collect(workflow).runs()).isEmpty();
    }
}
