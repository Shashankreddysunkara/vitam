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

import static fr.gouv.vitam.worker.core.utils.PluginHelper.buildItemStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.collections4.SetUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;

import fr.gouv.vitam.batch.report.model.entry.EliminationActionObjectGroupObjectVersion;
import fr.gouv.vitam.batch.report.model.entry.EliminationActionObjectGroupReportEntry;
import fr.gouv.vitam.common.collection.CloseableIterator;
import fr.gouv.vitam.common.database.builder.query.VitamFieldsHelper;
import fr.gouv.vitam.common.database.builder.request.multiple.SelectMultiQuery;
import fr.gouv.vitam.common.exception.InvalidParseOperationException;
import fr.gouv.vitam.common.iterables.BulkIterator;
import fr.gouv.vitam.common.json.JsonHandler;
import fr.gouv.vitam.common.logging.VitamLogger;
import fr.gouv.vitam.common.logging.VitamLoggerFactory;
import fr.gouv.vitam.common.model.ItemStatus;
import fr.gouv.vitam.common.model.RequestResponseOK;
import fr.gouv.vitam.common.model.StatusCode;
import fr.gouv.vitam.common.model.objectgroup.ObjectGroupResponse;
import fr.gouv.vitam.common.model.objectgroup.VersionsModel;
import fr.gouv.vitam.metadata.api.exception.MetaDataClientServerException;
import fr.gouv.vitam.metadata.api.exception.MetaDataDocumentSizeException;
import fr.gouv.vitam.metadata.api.exception.MetaDataExecutionException;
import fr.gouv.vitam.metadata.client.MetaDataClient;
import fr.gouv.vitam.metadata.client.MetaDataClientFactory;
import fr.gouv.vitam.processing.common.exception.ProcessingException;
import fr.gouv.vitam.processing.common.parameter.WorkerParameters;
import fr.gouv.vitam.worker.common.HandlerIO;
import fr.gouv.vitam.worker.core.distribution.JsonLineModel;
import fr.gouv.vitam.worker.core.distribution.JsonLineWriter;
import fr.gouv.vitam.worker.core.handler.ActionHandler;
import fr.gouv.vitam.worker.core.plugin.elimination.exception.EliminationException;
import fr.gouv.vitam.worker.core.plugin.elimination.model.EliminationActionObjectGroupStatus;
import fr.gouv.vitam.worker.core.plugin.elimination.report.EliminationActionReportService;
import fr.gouv.vitam.workspace.api.exception.ContentAddressableStorageServerException;


/**
 * Elimination action object group preparation handler.
 */
public class EliminationActionObjectGroupPreparationHandler extends ActionHandler {

    private static final VitamLogger LOGGER =
        VitamLoggerFactory.getInstance(EliminationActionObjectGroupPreparationHandler.class);

    private static final String ELIMINATION_ACTION_OBJECT_GROUP_PREPARATION =
        "ELIMINATION_ACTION_OBJECT_GROUP_PREPARATION";
    static final String OBJECT_GROUPS_TO_DELETE_FILE = "object_groups_to_delete.jsonl";
    static final String OBJECT_GROUPS_TO_DETACH_FILE = "object_groups_to_detach.jsonl";

    /**
     * By default ES does not allow more than 1024 clauses in selects
     * item IN ( "val1",... "val9") accounts for 9 clauses
     */
    private static final int MAX_ELASTIC_SEARCH_IN_REQUEST_SIZE = 1000;

    private static final int DEFAULT_OBJECT_GROUP_BULK_SIZE = MAX_ELASTIC_SEARCH_IN_REQUEST_SIZE;

    private final MetaDataClientFactory metaDataClientFactory;
    private final EliminationActionReportService eliminationActionReportService;
    private final int objectGroupBulkSize;

    /**
     * Default constructor
     */
    public EliminationActionObjectGroupPreparationHandler() {
        this(
            MetaDataClientFactory.getInstance(),
            new EliminationActionReportService(),
            DEFAULT_OBJECT_GROUP_BULK_SIZE);
    }

    /***
     * Test only constructor
     */
    @VisibleForTesting
    EliminationActionObjectGroupPreparationHandler(
        MetaDataClientFactory metaDataClientFactory,
        EliminationActionReportService eliminationActionReportService,
        int objectGroupBulkSize) {
        this.metaDataClientFactory = metaDataClientFactory;
        this.eliminationActionReportService = eliminationActionReportService;
        this.objectGroupBulkSize = objectGroupBulkSize;
    }

    @Override
    public ItemStatus execute(WorkerParameters param, HandlerIO handler)
        throws ProcessingException, ContentAddressableStorageServerException {

        try {

            File objectGroupsToDeleteFile = handler.getNewLocalFile(OBJECT_GROUPS_TO_DELETE_FILE);
            File objectGroupsToDetachFile = handler.getNewLocalFile(OBJECT_GROUPS_TO_DETACH_FILE);

            try (
                OutputStream objectGroupsToDeleteStream = new FileOutputStream(objectGroupsToDeleteFile);
                OutputStream objectGroupsToDetachStream = new FileOutputStream(objectGroupsToDetachFile);
                JsonLineWriter objectGroupsToDeleteWriter = new JsonLineWriter(objectGroupsToDeleteStream);
                JsonLineWriter objectGroupsToDetachWriter = new JsonLineWriter(objectGroupsToDetachStream);
                CloseableIterator<String> iterator =
                    eliminationActionReportService.exportDistinctObjectGroups(param.getContainerName())) {

                BulkIterator<String> bulkIterator = new BulkIterator<>(iterator, objectGroupBulkSize);

                while (bulkIterator.hasNext()) {
                    List<String> objectGroupIds = bulkIterator.next();
                    process(new HashSet<>(objectGroupIds), objectGroupsToDeleteWriter, objectGroupsToDetachWriter,
                        param.getContainerName());
                }

            } catch (IOException | InvalidParseOperationException e) {
                throw new EliminationException(StatusCode.FATAL, "Could not generate object group distribution", e);
            }

            handler.transferFileToWorkspace(OBJECT_GROUPS_TO_DELETE_FILE, objectGroupsToDeleteFile, true, false);
            handler.transferFileToWorkspace(OBJECT_GROUPS_TO_DETACH_FILE, objectGroupsToDetachFile, true, false);

            LOGGER.info("Elimination action object group preparation succeeded");
            return buildItemStatus(ELIMINATION_ACTION_OBJECT_GROUP_PREPARATION, StatusCode.OK, null);

        } catch (EliminationException e) {
            LOGGER.error(
                String.format("Elimination action object group preparation failed with status [%s]", e.getStatusCode()),
                e);
            return buildItemStatus(ELIMINATION_ACTION_OBJECT_GROUP_PREPARATION, e.getStatusCode(), e.getEventDetails());
        }
    }

    private void process(Set<String> objectGroupIds, JsonLineWriter objectGroupsToDeleteWriter,
        JsonLineWriter objectGroupsToDetachWriter,
        String processId)
        throws EliminationException, IOException, InvalidParseOperationException {

        Map<String, ObjectGroupResponse> objectGroups = loadObjectGroups(objectGroupIds);

        Set<String> foundObjectGroupIds = objectGroups.keySet();

        Set<String> notFoundUnitIds = SetUtils.difference(objectGroupIds, foundObjectGroupIds);

        for (String objectGroupId : notFoundUnitIds) {
            LOGGER.warn("Object group " + objectGroupId + " not found. Skipped");
        }

        Set<String> parentUnitIds = new HashSet<>();
        for (ObjectGroupResponse objectGroup : objectGroups.values()) {
            parentUnitIds.addAll(objectGroup.getUp());
        }

        Set<String> existingParentUnits = getExistingParentUnits(parentUnitIds);

        List<EliminationActionObjectGroupReportEntry> eliminationObjectGroupReportEntries = new ArrayList<>();

        for (ObjectGroupResponse objectGroup : objectGroups.values()) {

            Set<String> objectGroupParentUnits = new HashSet<>(objectGroup.getUp());
            Set<String> removedParentUnits = SetUtils.difference(objectGroupParentUnits, existingParentUnits);

            if (removedParentUnits.size() == objectGroupParentUnits.size()) {

                LOGGER.debug("Object group " + objectGroup.getId() + " will be deleted");

                Map<String, String> objectsToDelete = getBinaryObjectIdsWithStrategies(objectGroup);
                List<EliminationActionObjectGroupObjectVersion> objectVersions = getObjectVersions(objectGroup);

                ObjectNode params = JsonHandler.createObjectNode();
                ArrayNode objectToDeleteArrayNode = JsonHandler.createArrayNode();
                objectsToDelete.forEach((id, strategyId) -> {
                    objectToDeleteArrayNode.add(JsonHandler.createObjectNode().put("id", id).put("strategyId", strategyId));
                });
                params.set("objects", objectToDeleteArrayNode);
                params.put("strategyId", objectGroup.getStorage().getStrategyId());
                
                JsonLineModel entry = new JsonLineModel(objectGroup.getId(), null, params);
                objectGroupsToDeleteWriter.addEntry(entry);

                eliminationObjectGroupReportEntries.add(new EliminationActionObjectGroupReportEntry(objectGroup.getId(),
                    objectGroup.getOriginatingAgency(), objectGroup.getOpi(), null,
                    new HashSet<>(objectsToDelete.keySet()), EliminationActionObjectGroupStatus.DELETED.name(), objectVersions, "Outcome - TO BE DEFINED")); // FIXME : Put correct value
            } else {

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Object group " + objectGroup.getId() + " detached from deleted parents " +
                        String.join(", ", removedParentUnits));
                }

                JsonLineModel entry = new JsonLineModel(objectGroup.getId(), null,
                    JsonHandler.toJsonNode(removedParentUnits));
                objectGroupsToDetachWriter.addEntry(entry);

                eliminationObjectGroupReportEntries
                    .add(new EliminationActionObjectGroupReportEntry(objectGroup.getId(),
                        objectGroup.getOriginatingAgency(), objectGroup.getOpi(), removedParentUnits,
                        null, EliminationActionObjectGroupStatus.PARTIAL_DETACHMENT.name(), null, "Outcome - TO BE DEFINED")); // FIXME : Put correct value
            }
        }

        eliminationActionReportService.appendObjectGroupEntries(processId, eliminationObjectGroupReportEntries);
    }

    private Map<String, String> getBinaryObjectIdsWithStrategies(ObjectGroupResponse objectGroup) {

        return ListUtils.emptyIfNull(objectGroup.getQualifiers()).stream()
            .flatMap(qualifier -> ListUtils.emptyIfNull(qualifier.getVersions()).stream())
            .filter(version -> version.getPhysicalId() == null)
            .collect(Collectors.toMap(VersionsModel::getId, version -> version.getStorage().getStrategyId()));
    }

    private List<EliminationActionObjectGroupObjectVersion> getObjectVersions(ObjectGroupResponse objectGroup) {

        return ListUtils.emptyIfNull(objectGroup.getQualifiers()).stream()
            .flatMap(qualifier -> ListUtils.emptyIfNull(qualifier.getVersions()).stream())
            .map(version -> new EliminationActionObjectGroupObjectVersion(
                version.getOpi(), version.getSize()))
            .collect(Collectors.toList());
    }

    private Map<String, ObjectGroupResponse> loadObjectGroups(Set<String> objectGroupIds)
        throws EliminationException {

        try (MetaDataClient client = metaDataClientFactory.getClient()) {

            Map<String, ObjectGroupResponse> objectGroups = new HashMap<>();

            for (List<String> ids : ListUtils
                .partition(new ArrayList<>(objectGroupIds), MAX_ELASTIC_SEARCH_IN_REQUEST_SIZE)) {

                SelectMultiQuery selectMultiQuery = new SelectMultiQuery();
                selectMultiQuery.addRoots(ids.toArray(new String[0]));

                JsonNode jsonNode = client.selectObjectGroups(selectMultiQuery.getFinalSelect());
                RequestResponseOK<JsonNode> requestResponseOK = RequestResponseOK.getFromJsonNode(jsonNode);

                for (JsonNode objectGroupJson : requestResponseOK.getResults()) {

                    ObjectGroupResponse objectGroup =
                        JsonHandler.getFromJsonNode(objectGroupJson, ObjectGroupResponse.class);

                    objectGroups.put(objectGroup.getId(), objectGroup);
                }
            }
            return objectGroups;


        } catch (MetaDataExecutionException | MetaDataDocumentSizeException | MetaDataClientServerException | InvalidParseOperationException e) {
            throw new EliminationException(StatusCode.FATAL, "Could not load object groups", e);
        }
    }

    private Set<String> getExistingParentUnits(Set<String> parentUnitIds) throws EliminationException {

        try (MetaDataClient client = metaDataClientFactory.getClient()) {

            Set<String> foundUnitIds = new HashSet<>();

            for (List<String> ids : ListUtils
                .partition(new ArrayList<>(parentUnitIds), MAX_ELASTIC_SEARCH_IN_REQUEST_SIZE)) {

                SelectMultiQuery selectMultiQuery = new SelectMultiQuery();
                selectMultiQuery.addRoots(ids.toArray(new String[0]));
                selectMultiQuery.addUsedProjection(
                    VitamFieldsHelper.id());

                JsonNode jsonNode = client.selectUnits(selectMultiQuery.getFinalSelect());
                RequestResponseOK<JsonNode> requestResponseOK = RequestResponseOK.getFromJsonNode(jsonNode);

                for (JsonNode unit : requestResponseOK.getResults()) {
                    foundUnitIds.add(unit.get(VitamFieldsHelper.id()).asText());
                }
            }

            return foundUnitIds;

        } catch (MetaDataExecutionException | MetaDataDocumentSizeException | MetaDataClientServerException | InvalidParseOperationException e) {
            throw new EliminationException(StatusCode.FATAL, "Could not load object group parent units", e);
        }
    }

    @Override
    public void checkMandatoryIOParameter(HandlerIO handler) throws ProcessingException {
        // NOP.
    }

    public static String getId() {
        return ELIMINATION_ACTION_OBJECT_GROUP_PREPARATION;
    }
}
