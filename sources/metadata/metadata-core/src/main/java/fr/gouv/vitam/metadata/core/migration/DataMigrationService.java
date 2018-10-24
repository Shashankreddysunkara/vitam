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
package fr.gouv.vitam.metadata.core.migration;

import static com.mongodb.client.model.Filters.eq;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import fr.gouv.vitam.common.LocalDateUtil;
import fr.gouv.vitam.common.collection.CloseableIterator;
import fr.gouv.vitam.common.json.JsonHandler;
import fr.gouv.vitam.common.logging.VitamLogger;
import fr.gouv.vitam.common.logging.VitamLoggerFactory;
import fr.gouv.vitam.common.model.VitamConstants;
import fr.gouv.vitam.common.thread.VitamThreadPoolExecutor;
import fr.gouv.vitam.metadata.api.exception.MetaDataNotFoundException;
import fr.gouv.vitam.metadata.core.database.collections.MetadataCollections;
import fr.gouv.vitam.metadata.core.database.collections.MongoDbMetadataRepository;
import fr.gouv.vitam.metadata.core.database.collections.ObjectGroup;
import fr.gouv.vitam.metadata.core.database.collections.Unit;
import fr.gouv.vitam.metadata.core.graph.GraphService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.bson.Document;

/**
 * Data migration service.
 */
public class DataMigrationService implements AutoCloseable {

    Integer concurrencyLevel = Math.max(Runtime.getRuntime().availableProcessors(), 16);
    ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);

    /**
     * Vitam Logger.
     */
    private static final VitamLogger LOGGER = VitamLoggerFactory.getInstance(DataMigrationService.class);

    private final DataMigrationRepository dataMigrationRepository;

    private final AtomicBoolean isRunning = new AtomicBoolean();

    private final GraphService graphService;

    /**
     * Constructor
     */
    public DataMigrationService() {
        this(new DataMigrationRepository(),
            new GraphService(
                new MongoDbMetadataRepository(MetadataCollections.UNIT.getCollection())
            ));
    }

    /**
     * Constructor for testing
     */
    @VisibleForTesting DataMigrationService(DataMigrationRepository dataMigrationRepository,
        GraphService graphService) {
        this.dataMigrationRepository = dataMigrationRepository;
        this.graphService = graphService;
    }

    public boolean isMongoDataUpdateInProgress() {
        return isRunning.get();
    }

    public boolean tryStartMongoDataUpdate() {

        boolean lockAcquired = isRunning.compareAndSet(false, true);
        if (!lockAcquired) {
            // A migration is already running
            return false;
        }

        VitamThreadPoolExecutor.getDefaultExecutor().execute(() -> {
            try {
                LOGGER.info("Starting data migration");
                mongoDataUpdate();
            } catch (Exception e) {
                LOGGER.error("A fatal error occurred during data migration", e);
            } finally {
                isRunning.set(false);
               LOGGER.info("Data migration finished");
                try {
                    close();
                } catch (Exception e) {
                    LOGGER.error("Data migration finished with error. Cannot close Executor :" + e.getMessage());

                }
            }
        });

        return true;
    }

    void mongoDataUpdate() throws InterruptedException {

        processUnits();

        processObjectGroups();
    }

    private void processUnits() throws InterruptedException {
        try (CloseableIterator<List<Unit>> bulkUnitIterator =
            dataMigrationRepository.selectUnitBulkInTopDownHierarchyLevel()) {

            processUnitsByBulk(bulkUnitIterator);
        }
    }

    private void processUnitsByBulk(Iterator<List<Unit>> bulkUnitIterator) throws InterruptedException {

        LOGGER.info("Updating units...");

        StopWatch sw = StopWatch.createStarted();
        AtomicInteger updatedUnits = new AtomicInteger();
        AtomicInteger unitsWithErrors = new AtomicInteger();


        while (bulkUnitIterator.hasNext()) {
            List<Unit> bulkUnits = bulkUnitIterator.next();
            processBulk(executor, bulkUnits, sw, updatedUnits, unitsWithErrors);
        }
    }

    private void processBulk(ExecutorService executor, List<Unit> unitsToUpdate,
        StopWatch sw, AtomicInteger updatedUnitCount, AtomicInteger unitsWithErrorsCount) {

        Set<String> directParentIds = new HashSet<>();
        unitsToUpdate.forEach(unit -> directParentIds.addAll(unit.getCollectionOrEmpty(Unit.UP)));

        Map<String, Unit> directParentById = this.dataMigrationRepository.getUnitGraphByIds(directParentIds);

        List<Unit> updatedUnits = ListUtils.synchronizedList(new ArrayList<>());

        CompletableFuture[] futures =
            unitsToUpdate.stream().map(unit -> CompletableFuture.runAsync(() -> {
                try {
                    processDocument(unit);
                    updatedUnits.add(unit);
                } catch (Exception e) {
                    LOGGER.error("An error occurred during unit migration: " + unit.getId(), e);
                }
            }, executor)).toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();

        this.dataMigrationRepository.bulkReplaceUnits(updatedUnits);

        updatedUnitCount.addAndGet(updatedUnits.size());
        unitsWithErrorsCount.addAndGet(unitsToUpdate.size() - updatedUnits.size());

        LOGGER.info(String.format(
            "Unit migration progress : elapsed time= %d seconds, updated units= %d, units with errors= %d",
            sw.getTime(TimeUnit.SECONDS), updatedUnitCount.get(), unitsWithErrorsCount.get()));
    }

    private void processDocument(Unit unit) throws
        MetaDataNotFoundException {

        buildParentGraph(unit);
        updateUnitSedaModel(unit);
        unit.put("_sedaVersion", VitamConstants.SEDA_CURRENT_VERSION);
        String vitamImplVersion = this.getClass().getPackage().getImplementationVersion();
        if (vitamImplVersion != null) {
            unit.put("_implementationVersion", vitamImplVersion);
        } else {
            unit.put("_implementationVersion", "");
        }
    }

    private void buildParentGraph(Unit unit) throws
        MetaDataNotFoundException {

        Collection<String> directParentIds = unit.getCollectionOrEmpty(Unit.UP);
        graphService.compute(unit, directParentIds);
    }

    private void processObjectGroups() {

        StopWatch sw = StopWatch.createStarted();
        AtomicInteger updatedObjectCount = new AtomicInteger();

        try (CloseableIterator<List<ObjectGroup>> objectGroupListIterator = dataMigrationRepository
            .selectObjectGroupBulk()) {
            objectGroupListIterator.forEachRemaining(
                objectGroupIds -> {
                    processObjectGroupBulk(objectGroupIds);

                    updatedObjectCount.addAndGet(objectGroupIds.size());

                    LOGGER.info(String.format(
                        "Object Group migration progress : elapsed time= %d seconds, updated object groups= %d",
                        sw.getTime(TimeUnit.SECONDS), updatedObjectCount.get()));

                });
        }
    }

    private void processObjectGroupBulk(List<ObjectGroup> objectGroupIds) {

        Set<String> allUps =
            objectGroupIds
                .stream()
                .map(o -> (List<String>) o.get(ObjectGroup.UP, List.class))
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        if (allUps.isEmpty()) {
            // Bug: GOT without units
            throw new RuntimeException(
                "[Migration ERROR] GOT without units :" + JsonHandler.unprettyPrint(objectGroupIds));
        }

        try {
            Map<String, Unit> map = dataMigrationRepository.getUnitAllParentsByIds(allUps);

            CompletableFuture<WriteModel<Document>>[] features = objectGroupIds.stream()
                .map(o -> CompletableFuture.supplyAsync(() -> computeObjectGroupGraph(o, map), executor))
                .toArray(CompletableFuture[]::new);

            CompletableFuture<List<WriteModel<Document>>> result = CompletableFuture.allOf(features).
                thenApply(v ->
                    Stream.of(features)
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));

            List<WriteModel<Document>> updateOneModels = result.get();

            if (!updateOneModels.isEmpty()) {
                dataMigrationRepository.bulkUpdateObjectGroups(updateOneModels);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create update model for ObjectGroup
     *
     * @param objectGroup
     * @return UpdateOneModel for ObjectGroup
     */
    private UpdateOneModel<Document> computeObjectGroupGraph(ObjectGroup objectGroup, Map<String, Unit> units) {
        Set<String> gotUnitParents = new HashSet<>();
        String gotId = objectGroup.get(ObjectGroup.ID, String.class);
        List<String> up = objectGroup.get(ObjectGroup.UP, List.class);


        if (null != up && !up.isEmpty()) {

            up.forEach(o -> {
                Unit au = units.get(o);
                List<String> auParents = au.get(Unit.UNITUPS, List.class);
                if (CollectionUtils.isNotEmpty(auParents)) {
                    gotUnitParents.addAll(auParents);
                }
            });

            gotUnitParents.addAll(up);
        }


        final Document data = new Document("$set", new Document(Unit.UNITUPS, gotUnitParents)
            .append(ObjectGroup.GRAPH_LAST_PERSISTED_DATE,
                LocalDateUtil.getFormattedDateForMongo(LocalDateUtil.now())));

        return new UpdateOneModel<>(eq(ObjectGroup.ID, gotId), data, new UpdateOptions().upsert(false));
    }


    private void updateUnitSedaModel(Unit unit) {
        SedaConverterTool.convertUnitToSeda21(unit);
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
        executor.awaitTermination(10L, TimeUnit.MINUTES);
    }
}