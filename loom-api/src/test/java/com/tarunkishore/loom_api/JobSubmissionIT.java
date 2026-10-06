package com.tarunkishore.loom_api;

import com.loom.common.dto.JobSubmitRequest;
import com.loom.common.dto.TaskDefinition;
import com.loom.common.model.FailurePolicy;
import com.tarunkishore.loom_api.dto.JobResponse;
import com.tarunkishore.loom_api.dto.WorkflowTemplateRequest;
import com.tarunkishore.loom_api.dto.WorkflowTemplateResponse;
import com.tarunkishore.loom_api.repository.JobRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class JobSubmissionIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("loom")
        .withUsername("loom")
        .withPassword("loom");

    @Container
    static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.3"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired JobRepository jobRepository;

    private KafkaConsumer<String, String> kafkaConsumer;

    @AfterEach
    void cleanup() {
        if (kafkaConsumer != null) kafkaConsumer.close();
    }

    private JobSubmitRequest diamondDag(String jobName) {
        return new JobSubmitRequest(jobName, List.of(
            new TaskDefinition("A", "fetch-data",    List.of(),       2),
            new TaskDefinition("B", "transform-left", List.of("A"),  2),
            new TaskDefinition("C", "transform-right", List.of("A"), 2),
            new TaskDefinition("D", "merge",          List.of("B", "C"), 2)
        ), FailurePolicy.FAIL_FAST);
    }

    @Test
    void submitJob_returns201_withCorrectStructure() {
        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            "/api/v1/jobs", diamondDag("diamond-job"), JobResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("diamond-job");
        assertThat(response.getBody().tasks()).hasSize(4);
        assertThat(response.getBody().status().name()).isEqualTo("CREATED");
    }

    @Test
    void submitJob_persistsToDatabase() {
        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            "/api/v1/jobs", diamondDag("persist-test"), JobResponse.class);

        UUID jobId = response.getBody().id();
        assertThat(jobRepository.findByIdWithTasks(jobId)).isPresent();
        assertThat(jobRepository.findByIdWithTasks(jobId).get().getTasks()).hasSize(4);
    }

    @Test
    void submitJob_publishesJobCreatedEventToKafka() {
        kafkaConsumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "it-consumer-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
        ));
        kafkaConsumer.subscribe(List.of("job-created"));

        ResponseEntity<JobResponse> response = restTemplate.postForEntity(
            "/api/v1/jobs", diamondDag("kafka-test"), JobResponse.class);
        String expectedJobId = response.getBody().id().toString();

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(500));
            boolean found = false;
            for (var record : records) {
                if (expectedJobId.equals(record.key())) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("job-created event not found for jobId " + expectedJobId).isTrue();
        });
    }

    @Test
    void submitJob_withCyclicDag_returns400() {
        var cycleRequest = new JobSubmitRequest("cycle-job", List.of(
            new TaskDefinition("A", "step-a", List.of("B"), 2),
            new TaskDefinition("B", "step-b", List.of("A"), 2)
        ), FailurePolicy.FAIL_FAST);

        ResponseEntity<Map> response = restTemplate.postForEntity(
            "/api/v1/jobs", cycleRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getJob_returnsPersistedJob() {
        ResponseEntity<JobResponse> created = restTemplate.postForEntity(
            "/api/v1/jobs", diamondDag("get-test"), JobResponse.class);
        UUID jobId = created.getBody().id();

        ResponseEntity<JobResponse> fetched = restTemplate.getForEntity(
            "/api/v1/jobs/" + jobId, JobResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().id()).isEqualTo(jobId);
        assertThat(fetched.getBody().tasks()).hasSize(4);
    }

    @Test
    void getJob_unknownId_returns404() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
            "/api/v1/jobs/" + UUID.randomUUID(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void workflowTemplate_createAndSubmitJob() {
        // Create template
        var templateRequest = new WorkflowTemplateRequest(
            "etl-pipeline",
            "Standard ETL workflow",
            List.of(
                new TaskDefinition("extract", "Extract", List.of(), 3),
                new TaskDefinition("transform", "Transform", List.of("extract"), 3),
                new TaskDefinition("load", "Load", List.of("transform"), 3)
            ),
            FailurePolicy.FAIL_FAST
        );

        ResponseEntity<WorkflowTemplateResponse> templateResp = restTemplate.postForEntity(
            "/api/v1/workflows", templateRequest, WorkflowTemplateResponse.class);
        assertThat(templateResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID templateId = templateResp.getBody().id();

        // Submit job from template
        ResponseEntity<JobResponse> jobResp = restTemplate.postForEntity(
            "/api/v1/workflows/" + templateId + "/jobs",
            Map.of("name", "etl-run-1"),
            JobResponse.class
        );
        assertThat(jobResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jobResp.getBody().tasks()).hasSize(3);
    }

    @Test
    void cancelJob_setsStatusToCancelling() {
        ResponseEntity<JobResponse> created = restTemplate.postForEntity(
            "/api/v1/jobs", diamondDag("cancel-test"), JobResponse.class);
        UUID jobId = created.getBody().id();

        restTemplate.delete("/api/v1/jobs/" + jobId);

        ResponseEntity<JobResponse> fetched = restTemplate.getForEntity(
            "/api/v1/jobs/" + jobId, JobResponse.class);
        assertThat(fetched.getBody().status().name()).isEqualTo("CANCELLING");
    }
}
