package fr.gouv.vitam.storage.offers.tape.rest;

import com.mongodb.client.MongoCollection;
import fr.gouv.vitam.common.PropertiesUtils;
import fr.gouv.vitam.common.database.server.mongodb.MongoDbAccess;
import fr.gouv.vitam.common.database.server.mongodb.SimpleMongoDBAccess;
import fr.gouv.vitam.common.guid.GUIDFactory;
import fr.gouv.vitam.common.mongo.MongoRule;
import fr.gouv.vitam.storage.engine.common.collection.OfferCollections;
import fr.gouv.vitam.storage.engine.common.model.TapeLibraryBuildingOnDiskArchiveStorageLocation;
import fr.gouv.vitam.storage.engine.common.model.TapeLibraryReadyOnDiskArchiveStorageLocation;
import fr.gouv.vitam.storage.engine.common.model.TapeArchiveReferentialEntity;
import fr.gouv.vitam.storage.offers.tape.cas.ArchiveReferentialRepository;
import fr.gouv.vitam.storage.offers.tape.cas.BackupFileStorage;
import fr.gouv.vitam.storage.offers.tape.cas.BucketTopologyHelper;
import fr.gouv.vitam.storage.offers.tape.cas.WriteOrderCreator;
import fr.gouv.vitam.storage.offers.tape.exception.ArchiveReferentialException;
import fr.gouv.vitam.storage.offers.tape.impl.queue.QueueRepositoryImpl;
import fr.gouv.vitam.storage.offers.tape.spec.QueueRepository;
import org.assertj.core.api.Assertions;
import org.bson.Document;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static fr.gouv.vitam.common.database.collections.VitamCollection.getMongoClientOptions;

public class AdminTapeResourceTest {

    public static final String TAPE_TAR_REFERENTIAL_COLLECTION =
        OfferCollections.TAPE_ARCHIVE_REFERENTIAL.getName() + GUIDFactory.newGUID().getId();

    public static final String TAPE_QUEUE_MESSAGE_COLLECTION =
        OfferCollections.TAPE_QUEUE_MESSAGE.getName() + GUIDFactory.newGUID().getId();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @ClassRule
    public static MongoRule mongoRule =
        new MongoRule(getMongoClientOptions(), TAPE_TAR_REFERENTIAL_COLLECTION, TAPE_QUEUE_MESSAGE_COLLECTION);

    private static ArchiveReferentialRepository archiveReferentialRepository;
    private static QueueRepository readWriteQueue;
    private static MongoCollection<Document> tarReferentialCollection;
    private static MongoCollection<Document> queueCollection;

    @BeforeClass
    public static void setUpBeforeClass() {
        MongoDbAccess mongoDbAccess = new SimpleMongoDBAccess(mongoRule.getMongoClient(), MongoRule.VITAM_DB);

        tarReferentialCollection = mongoDbAccess.getMongoDatabase()
            .getCollection(TAPE_TAR_REFERENTIAL_COLLECTION);
        archiveReferentialRepository = new ArchiveReferentialRepository(tarReferentialCollection);

        queueCollection = mongoDbAccess.getMongoDatabase().getCollection(
            TAPE_QUEUE_MESSAGE_COLLECTION);
        readWriteQueue = new QueueRepositoryImpl(queueCollection);

    }

    @After
    public void tearDown() {
        mongoRule.handleAfter();
    }

    @Test
    public void testPutObjectOk() throws IOException, InterruptedException, ArchiveReferentialException {
        WriteOrderCreator writeOrderCreator = new WriteOrderCreator(
            archiveReferentialRepository, readWriteQueue);
        BackupFileStorage backupFileStorage =
            new BackupFileStorage(archiveReferentialRepository, writeOrderCreator, BucketTopologyHelper.BACKUP_BUCKET,
                BucketTopologyHelper.BACKUP_FILE_BUCKET, temporaryFolder.newFolder().getPath());


        AdminTapeResource adminTapeResource = new AdminTapeResource(backupFileStorage);

        InputStream backupStream = PropertiesUtils.getResourceAsStream("backup.zip");

        String objectId = "2019-01-01.backup.mongoc.zip";
        adminTapeResource.putObject(objectId, backupStream);

        Optional<TapeArchiveReferentialEntity> archiveReference =
            archiveReferentialRepository.find(objectId);

        Assertions.assertThat(archiveReference).isPresent();
        Assertions.assertThat(archiveReference.get().getLocation()).isInstanceOf(
            TapeLibraryBuildingOnDiskArchiveStorageLocation.class);

        writeOrderCreator.startListener();

        // Wait until writeOrder will be processed
        Thread.sleep(500);

        Assertions.assertThat(queueCollection.count()).isEqualTo(1);

        archiveReference = archiveReferentialRepository.find(objectId);

        Assertions.assertThat(archiveReference).isPresent();
        Assertions.assertThat(archiveReference.get().getLocation()).isInstanceOf(
            TapeLibraryReadyOnDiskArchiveStorageLocation.class);

    }
}