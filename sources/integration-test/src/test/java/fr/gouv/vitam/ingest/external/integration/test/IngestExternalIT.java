/*
 * Copyright French Prime minister Office/SGMAP/DINSIC/Vitam Program (2015-2019)
 *
 * contact.vitam@culture.gouv.fr
 *
 * This software is a computer program whose purpose is to implement a digital archiving back-office system managing
 * high volumetry securely and efficiently.
 *
 * This software is governed by the CeCILL 2.1 license under French law and abiding by the rules of distribution of free
 * software. You can use, modify and/ or redistribute the software under the terms of the CeCILL 2.1 license as
 * circulated by CEA, CNRS and INRIA at the following URL "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and rights to copy, modify and redistribute granted by the license,
 * users are provided only with a limited warranty and the software's author, the holder of the economic rights, and the
 * successive licensors have only limited liability.
 *
 * In this respect, the user's attention is drawn to the risks associated with loading, using, modifying and/or
 * developing or reproducing the software by the user in light of its specific status of free software, that may mean
 * that it is complicated to manipulate, and that also therefore means that it is reserved for developers and
 * experienced professionals having in-depth computer knowledge. Users are therefore encouraged to load and test the
 * software's suitability as regards their requirements in conditions enabling the security of their systems and/or data
 * to be ensured and, more generally, to use and operate it in the same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had knowledge of the CeCILL 2.1 license and that you
 * accept its terms.
 */
package fr.gouv.vitam.ingest.external.integration.test;

import static fr.gouv.vitam.common.GlobalDataRest.X_REQUEST_ID;
import static fr.gouv.vitam.logbook.common.parameters.Contexts.DEFAULT_WORKFLOW;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import com.mongodb.client.model.Filters;
import fr.gouv.vitam.access.external.client.AdminExternalClient;
import fr.gouv.vitam.access.external.client.AdminExternalClientFactory;
import fr.gouv.vitam.access.external.client.VitamPoolingClient;
import fr.gouv.vitam.access.external.rest.AccessExternalMain;
import fr.gouv.vitam.access.internal.rest.AccessInternalMain;
import fr.gouv.vitam.common.DataLoader;
import fr.gouv.vitam.common.GlobalDataRest;
import fr.gouv.vitam.common.PropertiesUtils;
import fr.gouv.vitam.common.VitamRuleRunner;
import fr.gouv.vitam.common.VitamServerRunner;
import fr.gouv.vitam.common.client.VitamClientFactory;
import fr.gouv.vitam.common.client.VitamClientFactoryInterface;
import fr.gouv.vitam.common.client.VitamContext;
import fr.gouv.vitam.common.elasticsearch.ElasticsearchRule;
import fr.gouv.vitam.common.json.JsonHandler;
import fr.gouv.vitam.common.model.ItemStatus;
import fr.gouv.vitam.common.model.ProcessAction;
import fr.gouv.vitam.common.model.ProcessState;
import fr.gouv.vitam.common.model.RequestResponse;
import fr.gouv.vitam.common.model.RequestResponseOK;
import fr.gouv.vitam.common.model.StatusCode;
import fr.gouv.vitam.common.thread.RunWithCustomExecutor;
import fr.gouv.vitam.functional.administration.rest.AdminManagementMain;
import fr.gouv.vitam.ingest.external.client.IngestExternalClient;
import fr.gouv.vitam.ingest.external.client.IngestExternalClientFactory;
import fr.gouv.vitam.ingest.external.rest.IngestExternalMain;
import fr.gouv.vitam.ingest.internal.upload.rest.IngestInternalMain;
import fr.gouv.vitam.logbook.common.server.database.collections.LogbookCollections;
import fr.gouv.vitam.logbook.rest.LogbookMain;
import fr.gouv.vitam.metadata.rest.MetadataMain;
import fr.gouv.vitam.processing.management.rest.ProcessManagementMain;
import fr.gouv.vitam.storage.engine.client.StorageClientFactory;
import fr.gouv.vitam.storage.engine.server.rest.StorageMain;
import fr.gouv.vitam.storage.offers.rest.DefaultOfferMain;
import fr.gouv.vitam.worker.server.rest.WorkerMain;
import fr.gouv.vitam.workspace.rest.WorkspaceMain;
import net.javacrumbs.jsonunit.JsonAssert;
import org.assertj.core.api.Assertions;
import org.bson.Document;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Ingest External integration test
 */
public class IngestExternalIT extends VitamRuleRunner {
    private static final Integer tenantId = 0;
    public static final String APPLICATION_SESSION_ID = "ApplicationSessionId";
    public static final String INTEGRATION_PROCESSING_4_UNITS_2_GOTS_ZIP = "integration-processing/4_UNITS_2_GOTS.zip";
    public static final String ACCESS_CONTRACT = "aName3";
    public static final String OPERATION_ID_REPLACE = "OPERATION_ID_REPLACE";
    public static final String INTEGRATION_INGEST_EXTERNAL_EXPECTED_LOGBOOK_JSON =
        "integration-ingest-external/expected-logbook.json";


    @ClassRule
    public static VitamServerRunner runner =
        new VitamServerRunner(IngestExternalIT.class, mongoRule.getMongoDatabase().getName(),
            ElasticsearchRule.getClusterName(),
            Sets.newHashSet(
                MetadataMain.class,
                WorkerMain.class,
                StorageMain.class,
                DefaultOfferMain.class,
                AdminManagementMain.class,
                LogbookMain.class,
                WorkspaceMain.class,
                ProcessManagementMain.class,
                AccessInternalMain.class,
                AccessExternalMain.class,
                IngestInternalMain.class,
                IngestExternalMain.class));

    private static IngestExternalClient ingestExternalClient;
    private static AdminExternalClient adminExternalClient;


    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        handleBeforeClass(0, 1);
        ingestExternalClient = IngestExternalClientFactory.getInstance().getClient();
        adminExternalClient = AdminExternalClientFactory.getInstance().getClient();

        // TODO: 18/09/2019 should import referential from externals
        new DataLoader("integration-ingest-internal").prepareData();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        handleAfterClass(0, 1);
        StorageClientFactory storageClientFactory = StorageClientFactory.getInstance();
        storageClientFactory.setVitamClientType(VitamClientFactoryInterface.VitamClientType.PRODUCTION);
        runAfter();
        VitamClientFactory.resetConnections();
    }

    @RunWithCustomExecutor
    @Test
    public void test_ingest_ok() throws Exception {
        try (InputStream inputStream =
            PropertiesUtils.getResourceAsStream(INTEGRATION_PROCESSING_4_UNITS_2_GOTS_ZIP)) {
            RequestResponse response = ingestExternalClient
                .ingest(
                    new VitamContext(tenantId).setApplicationSessionId(APPLICATION_SESSION_ID)
                        .setAccessContract("aName3"),
                    inputStream, DEFAULT_WORKFLOW.name(), ProcessAction.RESUME.name());

            assertThat(response.isOk()).as(JsonHandler.unprettyPrint(response)).isTrue();

            final String operationId = response.getHeaderString(GlobalDataRest.X_REQUEST_ID);

            assertThat(operationId).as(format("%s not found for request", X_REQUEST_ID)).isNotNull();

            final VitamPoolingClient vitamPoolingClient = new VitamPoolingClient(adminExternalClient);
            boolean process_timeout = vitamPoolingClient
                .wait(tenantId, operationId, ProcessState.COMPLETED, 1800, 1_000L, TimeUnit.MILLISECONDS);
            if (!process_timeout) {
                Assertions.fail("Sip processing not finished : operation (" + operationId + "). Timeout exceeded.");
            }

            response = adminExternalClient.getOperationProcessExecutionDetails(new VitamContext(tenantId), operationId);
            assertThat(response.isOk()).isTrue();


            RequestResponseOK<ItemStatus> itemStatusRequestResponse = (RequestResponseOK<ItemStatus>) response;
            assertThat(itemStatusRequestResponse.getResults()).hasSize(1);

            ItemStatus itemStatus = itemStatusRequestResponse.getFirstResult();
            assertThat(itemStatus.getGlobalState()).isEqualTo(ProcessState.COMPLETED);
            assertThat(itemStatus.getGlobalStatus()).as(JsonHandler
                .unprettyPrint(LogbookCollections.OPERATION.getCollection().find(Filters.eq(operationId))))
                .isEqualTo(StatusCode.OK);
        }
    }

    @RunWithCustomExecutor
    @Test
    public void test_ingest_step_by_step_ok() throws Exception {
        try (InputStream inputStream =
            PropertiesUtils.getResourceAsStream("integration-processing/4_UNITS_2_GOTS.zip")) {
            // Start ingest with step by step mode
            RequestResponse response = ingestExternalClient
                .ingest(
                    new VitamContext(tenantId).setApplicationSessionId(APPLICATION_SESSION_ID).setAccessContract(
                        ACCESS_CONTRACT),
                    inputStream, DEFAULT_WORKFLOW.name(), ProcessAction.NEXT.name());

            assertThat(response.isOk()).as(JsonHandler.unprettyPrint(response)).isTrue();

            final String operationId = response.getHeaderString(GlobalDataRest.X_REQUEST_ID);

            assertThat(operationId).as(format("%s not found for request", X_REQUEST_ID)).isNotNull();

            // Number of steps in ingest workflow
            int numberOfIngestSteps = 11;

            final VitamPoolingClient vitamPoolingClient = new VitamPoolingClient(adminExternalClient);
            boolean process_timeout = vitamPoolingClient
                .wait(tenantId, operationId, 1800, 1_000L, TimeUnit.MILLISECONDS);
            if (!process_timeout) {
                Assertions.fail("Sip processing not finished : operation (" + operationId + "). Timeout exceeded.");
            }

            RequestResponse itemStatusResponse = null;
            while (null == itemStatusResponse || itemStatusResponse.isOk()) {
                numberOfIngestSteps--;
                // Check workflow state and status
                itemStatusResponse =
                    adminExternalClient.getOperationProcessExecutionDetails(new VitamContext(tenantId), operationId);
                assertThat(itemStatusResponse.isOk()).isTrue();
                RequestResponseOK<ItemStatus> itemStatusRequestResponse =
                    (RequestResponseOK<ItemStatus>) itemStatusResponse;
                assertThat(itemStatusRequestResponse.getResults()).hasSize(1);

                ItemStatus itemStatus = itemStatusRequestResponse.getFirstResult();
                assertThat(itemStatus.getGlobalStatus()).as(JsonHandler
                    .unprettyPrint(LogbookCollections.OPERATION.getCollection().find(Filters.eq(operationId))))
                    .isEqualTo(StatusCode.OK);


                if (ProcessState.COMPLETED.equals(itemStatus.getGlobalState())) {
                    break;
                }

                // Execute the next step
                RequestResponse<ItemStatus> updateResponse = adminExternalClient.updateOperationActionProcess(
                    new VitamContext(tenantId).setApplicationSessionId(APPLICATION_SESSION_ID)
                        .setAccessContract(ACCESS_CONTRACT),
                    ProcessAction.NEXT.name(),
                    operationId
                );
                assertThat(updateResponse.isOk()).isTrue();

                // Wait the end of execution of the current step
                process_timeout = vitamPoolingClient
                    .wait(tenantId, operationId, 1800, 1_000L, TimeUnit.MILLISECONDS);
                if (!process_timeout) {
                    Assertions.fail("Sip processing not finished : operation (" + operationId + "). Timeout exceeded.");
                }
            }

            // Assert all steps are executed
            assertThat(numberOfIngestSteps).isEqualTo(0);

            // Get logbook and check all events
            Document operation =
                (Document) LogbookCollections.OPERATION.getCollection().find(Filters.eq(operationId), Document.class)
                    .first();
            InputStream expected =
                PropertiesUtils.getResourceAsStream(INTEGRATION_INGEST_EXTERNAL_EXPECTED_LOGBOOK_JSON);
            JsonNode expectedJsonNode = JsonHandler.getFromInputStream(
                expected);
            String found = JsonHandler.prettyPrint(operation).replace(operationId, OPERATION_ID_REPLACE);
            JsonNode foundJsonNode = JsonHandler.getFromString(found);
            JsonAssert.assertJsonEquals(expectedJsonNode, foundJsonNode,
                JsonAssert
                    .whenIgnoringPaths(
                        "evDetData",
                        "evDateTime",
                        "agId",
                        "_lastPersistedDate",
                        "events[*].evDetData",
                        "events[*].evId",
                        "events[*].evDateTime",
                        "events[*].agId",
                        "events[*].evParentId")
            );

        }
    }
}