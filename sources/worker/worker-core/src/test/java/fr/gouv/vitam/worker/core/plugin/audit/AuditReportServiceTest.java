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
package fr.gouv.vitam.worker.core.plugin.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.fasterxml.jackson.databind.JsonNode;

import fr.gouv.vitam.batch.report.client.BatchReportClient;
import fr.gouv.vitam.batch.report.client.BatchReportClientFactory;
import fr.gouv.vitam.batch.report.model.OperationSummary;
import fr.gouv.vitam.batch.report.model.Report;
import fr.gouv.vitam.batch.report.model.ReportItemStatus;
import fr.gouv.vitam.batch.report.model.ReportResults;
import fr.gouv.vitam.batch.report.model.ReportStatus;
import fr.gouv.vitam.batch.report.model.ReportSummary;
import fr.gouv.vitam.batch.report.model.ReportType;
import fr.gouv.vitam.batch.report.model.entry.AuditObjectGroupReportEntry;
import fr.gouv.vitam.batch.report.model.entry.AuditObjectVersion;
import fr.gouv.vitam.common.json.JsonHandler;

public class AuditReportServiceTest {

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private BatchReportClientFactory batchReportFactory;

    @Mock
    private BatchReportClient batchReportClient;
    private AuditReportService auditReportService;
    private String processId;
    private int tenantId;

    @Before
    public void setUp() throws Exception {
        given(batchReportFactory.getClient()).willReturn(batchReportClient);
        processId = "123456789";
        tenantId = 0;

        auditReportService = new AuditReportService(batchReportFactory);
    }

    @Test
    public void should_append_audit_report_entries() {
        // Given
        AuditObjectVersion version = new AuditObjectVersion("objectId1", "objectOpi1", "objectQualifier1",
                "objectVersion1", new ArrayList<ReportItemStatus>() {
                    {
                        add(new ReportItemStatus("offerId1", ReportStatus.OK));
                        add(new ReportItemStatus("offerId2", ReportStatus.OK));
                    }
                }, ReportStatus.OK);

        List<AuditObjectVersion> objectVersions = new ArrayList<AuditObjectVersion>();
        objectVersions.add(version);

        AuditObjectGroupReportEntry auditReportEntry = new AuditObjectGroupReportEntry("objectGroupId1",
                Collections.singletonList("unitId"), "originatingAgency1", "opi", objectVersions, ReportStatus.KO,
                "outcome");

        List<AuditObjectGroupReportEntry> reports = new ArrayList<>();
        reports.add(auditReportEntry);

        // When
        ThrowingCallable appendPreservation = () -> auditReportService.appendAuditEntries(processId, reports);

        // Then
        assertThatCode(appendPreservation).doesNotThrowAnyException();
    }

    @Test
    public void should_store_does_not_throw_any_exception() {

        OperationSummary operationSummary = new OperationSummary(tenantId, processId, "", "", "", "",
                JsonHandler.createObjectNode(), JsonHandler.createObjectNode());
        ReportSummary reportSummary = new ReportSummary(null, null, ReportType.AUDIT, new ReportResults(),
                JsonHandler.createObjectNode());
        JsonNode context = JsonHandler.createObjectNode();

        Report reportInfo = new Report(operationSummary, reportSummary, context);

        // Given / When
        ThrowingCallable exportReport = () -> auditReportService.storeReport(reportInfo);

        // Then
        assertThatCode(exportReport).doesNotThrowAnyException();
    }

    @Test
    public void should_delete_does_not_throw_any_exception() {

        // Given / When
        ThrowingCallable exportReport = () -> auditReportService.cleanupReport(processId);

        // Then
        assertThatCode(exportReport).doesNotThrowAnyException();
    }
}
