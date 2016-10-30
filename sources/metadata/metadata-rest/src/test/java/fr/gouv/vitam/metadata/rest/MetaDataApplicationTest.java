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
package fr.gouv.vitam.metadata.rest;

import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

import java.io.File;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.BindHttpException;
import org.elasticsearch.node.Node;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;
import fr.gouv.vitam.common.PropertiesUtils;
import fr.gouv.vitam.common.exception.VitamApplicationServerException;
import fr.gouv.vitam.common.junit.JunitHelper;
import fr.gouv.vitam.metadata.api.config.MetaDataConfiguration;

public class MetaDataApplicationTest {
    private static final String METADATA_CONF = "metadata.conf";
    private static final String METADATA_AUTH_CONF = "metadata-auth.conf";
    private static MongodExecutable mongodExecutable;
    private final MetaDataApplication application = new MetaDataApplication();
    static MongodProcess mongod;
    private static JunitHelper junitHelper;
    private static int port;

    @ClassRule
    public static TemporaryFolder tempFolder = new TemporaryFolder();
    private static File elasticsearchHome;

    private final static String CLUSTER_NAME = "vitam-cluster";
    private static int TCP_PORT = 9300;
    private static int HTTP_PORT = 9200;
    private static Node node;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        junitHelper = JunitHelper.getInstance();

        // ES
        elasticsearchHome = tempFolder.newFolder();
        for (int i = 0; i < 3; i++) {
            TCP_PORT = junitHelper.findAvailablePort();
            HTTP_PORT = junitHelper.findAvailablePort();

            try {
                final Settings settings = Settings.settingsBuilder()
                    .put("http.enabled", true)
                    .put("discovery.zen.ping.multicast.enabled", false)
                    .put("transport.tcp.port", TCP_PORT)
                    .put("http.port", HTTP_PORT)
                    .put("path.home", elasticsearchHome.getCanonicalPath())
                    .build();

                node = nodeBuilder()
                    .settings(settings)
                    .client(false)
                    .clusterName(CLUSTER_NAME)
                    .node();

                node.start();
            } catch (BindHttpException e) {
                junitHelper.releasePort(TCP_PORT);
                junitHelper.releasePort(HTTP_PORT);
                node = null;
                continue;
            }
        }
        if (node == null) {
            return;
        }

        final MongodStarter starter = MongodStarter.getDefaultInstance();

        port = junitHelper.findAvailablePort();
        mongodExecutable = starter.prepare(new MongodConfigBuilder()
            .version(Version.Main.PRODUCTION)
            .net(new Net(port, Network.localhostIsIPv6()))
            .build());
        mongod = mongodExecutable.start();
    }

    @AfterClass
    public static void tearDownAfterClass() {
        if (node == null) {
            return;
        }
        mongod.stop();
        mongodExecutable.stop();
        junitHelper.releasePort(port);

        if (node != null) {
            node.close();
        }

        junitHelper.releasePort(TCP_PORT);
        junitHelper.releasePort(HTTP_PORT);
    }
    @Before
    public void before() {
        Assume.assumeTrue("Elasticsearch not started but should", node != null);
    }

    @Test(expected = VitamApplicationServerException.class)
    public void givenEmptyArgsWhenConfigureApplicationThenRaiseAnException() throws Exception {
        application.configure("");
    }

    // FIXME P0: fix it (when server v2)
    @Ignore
    @Test(expected = Exception.class)
    public void givenFileNotFoundWhenConfigureApplicationThenRaiseAnException() throws Exception {
        try {
            application.configure("src/test/resources/notFound.conf");
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    public void givenFileExistsWhenConfigureApplicationThenRunServer() throws Exception {
        final File conf = PropertiesUtils.findFile(METADATA_CONF);
        final MetaDataConfiguration config = PropertiesUtils.readYaml(conf, MetaDataConfiguration.class);
        config.setDbPort(port);
        config.getElasticsearchNodes().get(0).setTcpPort(TCP_PORT);
        final File newConf = File.createTempFile("test", METADATA_CONF, conf.getParentFile());
        PropertiesUtils.writeYaml(newConf, config);
        final int serverPort = junitHelper.findAvailablePort();
        application.configure(newConf.getAbsolutePath());
        newConf.delete();
        junitHelper.releasePort(serverPort);
        MetaDataApplication.stop();
    }

    @Test
    public void givenConfigFileWhenConfigureApplicationThenRunServer() throws Exception {
        final File conf = PropertiesUtils.findFile(METADATA_CONF);
        final MetaDataConfiguration config = PropertiesUtils.readYaml(conf, MetaDataConfiguration.class);
        config.setDbPort(port);
        config.getElasticsearchNodes().get(0).setTcpPort(TCP_PORT);
        final File newConf = File.createTempFile("test", METADATA_CONF, conf.getParentFile());
        PropertiesUtils.writeYaml(newConf, config);
        final int serverPort = junitHelper.findAvailablePort();
        application.configure(newConf.getAbsolutePath());
        newConf.delete();
        junitHelper.releasePort(serverPort);
        MetaDataApplication.stop();
    }
    
    @Test
    public void givenPortNegativeWhenConfigureApplicationThenUseDefaultPortToRunServer() throws Exception {
        final File conf = PropertiesUtils.findFile(METADATA_CONF);
        final MetaDataConfiguration config = PropertiesUtils.readYaml(conf, MetaDataConfiguration.class);
        config.setDbPort(port);
        config.getElasticsearchNodes().get(0).setTcpPort(TCP_PORT);
        final File newConf = File.createTempFile("test", METADATA_CONF, conf.getParentFile());
        PropertiesUtils.writeYaml(newConf, config);
        final int serverPort = junitHelper.findAvailablePort();
        application.configure(newConf.getAbsolutePath());
        newConf.delete();
        junitHelper.releasePort(serverPort);
        MetaDataApplication.stop();
    }
}
