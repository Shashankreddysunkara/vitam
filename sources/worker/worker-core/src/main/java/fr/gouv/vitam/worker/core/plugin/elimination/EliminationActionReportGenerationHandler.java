/*******************************************************************************
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
 *******************************************************************************/
package fr.gouv.vitam.worker.core.plugin.elimination;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import fr.gouv.vitam.batch.report.model.OperationSummary;
import fr.gouv.vitam.batch.report.model.Report;
import fr.gouv.vitam.batch.report.model.ReportResults;
import fr.gouv.vitam.batch.report.model.ReportSummary;
import fr.gouv.vitam.batch.report.model.ReportType;
import fr.gouv.vitam.batch.report.model.entry.EliminationActionUnitReportEntry;
import fr.gouv.vitam.common.LocalDateUtil;
import fr.gouv.vitam.common.collection.CloseableIterator;
import fr.gouv.vitam.common.json.JsonHandler;
import fr.gouv.vitam.common.logging.VitamLogger;
import fr.gouv.vitam.common.logging.VitamLoggerFactory;
import fr.gouv.vitam.common.model.ItemStatus;
import fr.gouv.vitam.common.model.StatusCode;
import fr.gouv.vitam.common.thread.VitamThreadUtils;
import fr.gouv.vitam.functional.administration.common.BackupService;
import fr.gouv.vitam.functional.administration.common.exception.BackupServiceException;
import fr.gouv.vitam.processing.common.exception.ProcessingException;
import fr.gouv.vitam.processing.common.parameter.WorkerParameters;
import fr.gouv.vitam.storage.engine.common.model.DataCategory;
import fr.gouv.vitam.worker.common.HandlerIO;
import fr.gouv.vitam.worker.core.handler.ActionHandler;
import fr.gouv.vitam.worker.core.plugin.elimination.exception.EliminationException;
import fr.gouv.vitam.worker.core.plugin.elimination.report.EliminationActionObjectGroupReportExportEntry;
import fr.gouv.vitam.worker.core.plugin.elimination.report.EliminationActionReportService;
import fr.gouv.vitam.workspace.api.exception.ContentAddressableStorageServerException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;

import static fr.gouv.vitam.worker.core.utils.PluginHelper.buildItemStatus;


/**
 * Elimination action finalization handler.
 */
public class EliminationActionReportGenerationHandler extends ActionHandler {

    private static final VitamLogger LOGGER =
        VitamLoggerFactory.getInstance(EliminationActionReportGenerationHandler.class);

    private static final String ELIMINATION_ACTION_REPORT_GENERATION = "ELIMINATION_ACTION_REPORT_GENERATION";

    private final EliminationActionReportService eliminationActionReportService;
    private final BackupService backupService;

    /**
     * Default constructor
     */
    public EliminationActionReportGenerationHandler() {
        this(
            new EliminationActionReportService(),
            new BackupService());
    }

    /***
     * Test only constructor
     */
    @VisibleForTesting
    EliminationActionReportGenerationHandler(
        EliminationActionReportService eliminationActionReportService,
        BackupService backupService) {
        this.eliminationActionReportService = eliminationActionReportService;
        this.backupService = backupService;
    }

    @Override
    public ItemStatus execute(WorkerParameters param, HandlerIO handler)
        throws ProcessingException, ContentAddressableStorageServerException {

        try {
            generateEliminationReport(param);

            LOGGER.info("Elimination action finalization succeeded");
            return buildItemStatus(ELIMINATION_ACTION_REPORT_GENERATION, StatusCode.OK, null);
        } catch (EliminationException e) {
            LOGGER.error(
                String.format("Elimination action finalization failed with status [%s]", e.getStatusCode()), e);
            return buildItemStatus(ELIMINATION_ACTION_REPORT_GENERATION, e.getStatusCode(), e.getEventDetails());
        }
    }

    private void generateEliminationReport(WorkerParameters param) throws EliminationException {
        Integer tenant = VitamThreadUtils.getVitamSession().getTenantId();
        String evId = param.getContainerName();
        String evType = ""; // FIXME To be Fill in a post commit
        String outcome = ""; // FIXME To be Fill in a post commit
        String outDetail = ""; // FIXME To be Fill in a post commit
        String outMsg = ""; // FIXME To be Fill in a post commit
        // VitamThreadUtils.getVitamSession().getContractId();
        // VitamThreadUtils.getVitamSession().getContextId();
        // rSI = {AccessContract: contractId, Context: contextId }
        // FIXME: What should we put in rightsStatementIdentifier for Elimination ?
        JsonNode rSI = JsonHandler.createObjectNode(); // FIXME To be Fill in a post commit
        JsonNode evDetData = JsonHandler.createObjectNode(); // FIXME To be Fill in a post commit
        OperationSummary operationSummary = new OperationSummary(tenant, evId, evType, outcome, outDetail, outMsg, rSI, evDetData);

        String startDate = null; // FIXME To be Fill in a post commit
        String endDate = LocalDateUtil.getString(LocalDateTime.now());
        ReportType reportType = ReportType.ELIMINATION_ACTION;
        ReportResults vitamResults = new ReportResults(); // FIXME To be Fill in a post commit
        JsonNode extendedInfo = JsonHandler.createObjectNode(); // FIXME To be Fill in a post commit
        ReportSummary reportSummary = new ReportSummary(startDate, endDate, reportType, vitamResults, extendedInfo);

        JsonNode context = JsonHandler.createObjectNode();

        Report reportInfo = new Report(operationSummary, reportSummary, context);
        eliminationActionReportService.storeReport(reportInfo);
    }

    @Override
    public void checkMandatoryIOParameter(HandlerIO handler) throws ProcessingException {
        // NOP.
    }

    public static String getId() {
        return ELIMINATION_ACTION_REPORT_GENERATION;
    }
}
