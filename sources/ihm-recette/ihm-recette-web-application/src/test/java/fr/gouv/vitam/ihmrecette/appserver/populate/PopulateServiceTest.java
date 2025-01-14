package fr.gouv.vitam.ihmrecette.appserver.populate;

import fr.gouv.vitam.common.VitamConfiguration;
import fr.gouv.vitam.common.model.unit.DescriptiveMetadataModel;
import io.reactivex.schedulers.TestScheduler;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static fr.gouv.vitam.ihmrecette.appserver.populate.PopulateService.CONTRACT_POPULATE;
import static fr.gouv.vitam.ihmrecette.appserver.populate.PopulateService.POPULATE_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class PopulateServiceTest {
    private MetadataRepository metadataRepository;
    private MasterdataRepository masterdataRepository;
    private LogbookRepository logbookRepository;
    private MetadataStorageService metadataStorageService;
    private UnitGraph unitGraph;

    @Before
    public void setUp() {
        metadataRepository = mock(MetadataRepository.class);
        masterdataRepository = mock(MasterdataRepository.class);
        logbookRepository = mock(LogbookRepository.class);
        metadataStorageService = mock(MetadataStorageService.class);
        unitGraph = mock(UnitGraph.class);
    }

    @Test
    public void should_populate_one_element() {
        // Given
        TestScheduler io = new TestScheduler();
        PopulateService populateService = new PopulateService(metadataRepository, masterdataRepository, logbookRepository, unitGraph, 1, metadataStorageService, io);

        PopulateModel populateModel = new PopulateModel();
        populateModel.setBulkSize(1000);
        populateModel.setNumberOfUnit(1);
        populateModel.setRootId("1234");
        populateModel.setSp("vitam");
        populateModel.setTenant(0);
        populateModel.setWithGots(true);
        populateModel.setWithRules(true);
        populateModel.setObjectSize(1024);
        Map<String, Integer> ruleMap = new HashMap<>();
        ruleMap.put("STR-00059", 100);
        String ruleName = "ACC-000111";
        ruleMap.put(ruleName, 20);
        populateModel.setRuleTemplatePercent(ruleMap);

        UnitModel unitModel = new UnitModel();
        unitModel.setStorageModel(new StorageModel(2, VitamConfiguration.getDefaultStrategy(), Arrays.asList("offer1", "offer2")));

        DescriptiveMetadataModel content = new DescriptiveMetadataModel();
        content.setTitle("1234");

        unitModel.setDescriptiveMetadataModel(content);
        unitModel.setId("1234");

        UnitGotModel gotModel = new UnitGotModel(unitModel);

        given(masterdataRepository.findAgency(anyInt(), anyString())).willReturn(Optional.empty());
        given(masterdataRepository.findRule(anyInt(), anyString())).willReturn(Optional.empty());
        given(unitGraph.createGraph(anyInt(), any())).willReturn(gotModel);

        given(masterdataRepository.findAccessionRegitserSummary(anyInt(), anyString())).willReturn(Optional.empty());

        // When
        populateService.populateVitam(populateModel);

        // Then
        io.triggerActions();

        verify(masterdataRepository).importAccessContract(CONTRACT_POPULATE, populateModel.getTenant());
        verify(masterdataRepository).importRule(ruleName, populateModel.getTenant());
        verify(metadataRepository).store(populateModel.getTenant(), Collections.singletonList(gotModel), populateModel.isStoreInDb(), populateModel.isIndexInEs());
        verify(logbookRepository).storeLogbookLifecycleUnit(populateModel.getTenant(), Collections.singletonList(gotModel));
        verify(logbookRepository).storeLogbookLifeCycleObjectGroup(populateModel.getTenant(), Collections.singletonList(gotModel));
        verify(metadataStorageService).storeToOffers(populateModel, Collections.singletonList(gotModel));
        verify(masterdataRepository).createAccessionRegisterSummary(populateModel.getTenant(), populateModel.getSp(), populateModel.getNumberOfUnit(), populateModel.getNumberOfUnit() * populateModel.getObjectSize());
    }

    @Test
    public void should_populate_multiple_elements() {
        // Given
        TestScheduler io = new TestScheduler();
        PopulateService populateService = new PopulateService(metadataRepository, masterdataRepository, logbookRepository, unitGraph, 1, metadataStorageService, io);
        int numberOfUnit = 5;

        PopulateModel populateModel = new PopulateModel();
        populateModel.setBulkSize(1);
        populateModel.setNumberOfUnit(numberOfUnit);
        populateModel.setRootId("1234");
        populateModel.setSp("vitam");
        populateModel.setTenant(0);
        populateModel.setWithGots(true);
        populateModel.setWithRules(true);
        populateModel.setObjectSize(1024);
        Map<String, Integer> ruleMap = new HashMap<>();
        ruleMap.put("STR-00059", 100);
        String ruleName = "ACC-000111";
        ruleMap.put(ruleName, 20);
        populateModel.setRuleTemplatePercent(ruleMap);

        UnitModel unitModel = new UnitModel();
        unitModel.setStorageModel(new StorageModel(2, VitamConfiguration.getDefaultStrategy(), Arrays.asList("offer1", "offer2")));

        DescriptiveMetadataModel content = new DescriptiveMetadataModel();
        content.setTitle("1234");

        unitModel.setDescriptiveMetadataModel(content);
        unitModel.setId("1234");

        UnitGotModel gotModel = new UnitGotModel(unitModel);

        given(masterdataRepository.findAgency(anyInt(), anyString())).willReturn(Optional.empty());
        given(masterdataRepository.findRule(anyInt(), anyString())).willReturn(Optional.empty());
        given(unitGraph.createGraph(anyInt(), any())).willReturn(gotModel);

        given(masterdataRepository.findAccessionRegitserSummary(anyInt(), anyString())).willReturn(Optional.empty());

        // When
        populateService.populateVitam(populateModel);

        // Then
        io.triggerActions();

        verify(masterdataRepository, times(1)).importAccessContract(anyString(), anyInt());
        verify(masterdataRepository, atLeast(ruleMap.size())).importRule(anyString(), anyInt());
        verify(metadataRepository, times(numberOfUnit)).store(anyInt(), any(), anyBoolean(), anyBoolean());
        verify(logbookRepository, times(numberOfUnit)).storeLogbookLifecycleUnit(anyInt(), any());
        verify(logbookRepository, times(numberOfUnit)).storeLogbookLifeCycleObjectGroup(anyInt(), any());
        verify(metadataStorageService, times(numberOfUnit)).storeToOffers(any(), any());
        verify(masterdataRepository, times(1)).createAccessionRegisterSummary(anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    public void should_delete_file_in_case_of_error() {
        // Given
        TestScheduler io = new TestScheduler();
        PopulateService populateService = new PopulateService(metadataRepository, masterdataRepository, logbookRepository, unitGraph, 1, metadataStorageService, io);
        int numberOfUnit = 5;

        PopulateModel populateModel = new PopulateModel();
        populateModel.setBulkSize(1);
        populateModel.setNumberOfUnit(numberOfUnit);
        populateModel.setRootId("1234");
        populateModel.setSp("vitam");
        populateModel.setTenant(0);
        populateModel.setWithGots(true);
        populateModel.setWithRules(true);
        populateModel.setObjectSize(1024);
        Map<String, Integer> ruleMap = new HashMap<>();
        ruleMap.put("STR-00059", 100);
        String ruleName = "ACC-000111";
        ruleMap.put(ruleName, 20);
        populateModel.setRuleTemplatePercent(ruleMap);

        UnitModel unitModel = new UnitModel();
        unitModel.setStorageModel(new StorageModel(2, VitamConfiguration.getDefaultStrategy(), Arrays.asList("offer1", "offer2")));

        DescriptiveMetadataModel content = new DescriptiveMetadataModel();
        content.setTitle("1234");

        unitModel.setDescriptiveMetadataModel(content);
        unitModel.setId("1234");


        given(masterdataRepository.findAgency(anyInt(), anyString())).willReturn(Optional.empty());
        given(masterdataRepository.findRule(anyInt(), anyString())).willReturn(Optional.empty());
        given(unitGraph.createGraph(anyInt(), any())).willThrow(new IllegalStateException());

        given(masterdataRepository.findAccessionRegitserSummary(anyInt(), anyString())).willReturn(Optional.empty());

        // When
        populateService.populateVitam(populateModel);

        // Then
        io.triggerActions();
        assertThat(POPULATE_FILE).doesNotExist();
    }

    @Test
    public void should_delete_file_at_end() {
        // Given
        TestScheduler io = new TestScheduler();
        PopulateService populateService = new PopulateService(metadataRepository, masterdataRepository, logbookRepository, unitGraph, 1, metadataStorageService, io);
        int numberOfUnit = 5;

        PopulateModel populateModel = new PopulateModel();
        populateModel.setBulkSize(1);
        populateModel.setNumberOfUnit(numberOfUnit);
        populateModel.setRootId("1234");
        populateModel.setSp("vitam");
        populateModel.setTenant(0);
        populateModel.setWithGots(true);
        populateModel.setWithRules(true);
        populateModel.setObjectSize(1024);
        Map<String, Integer> ruleMap = new HashMap<>();
        ruleMap.put("STR-00059", 100);
        String ruleName = "ACC-000111";
        ruleMap.put(ruleName, 20);
        populateModel.setRuleTemplatePercent(ruleMap);

        UnitModel unitModel = new UnitModel();
        unitModel.setStorageModel(new StorageModel(2, VitamConfiguration.getDefaultStrategy(), Arrays.asList("offer1", "offer2")));

        DescriptiveMetadataModel content = new DescriptiveMetadataModel();
        content.setTitle("1234");

        unitModel.setDescriptiveMetadataModel(content);
        unitModel.setId("1234");

        UnitGotModel gotModel = new UnitGotModel(unitModel);

        given(masterdataRepository.findAgency(anyInt(), anyString())).willReturn(Optional.empty());
        given(masterdataRepository.findRule(anyInt(), anyString())).willReturn(Optional.empty());
        given(unitGraph.createGraph(anyInt(), any())).willReturn(gotModel);

        given(masterdataRepository.findAccessionRegitserSummary(anyInt(), anyString())).willReturn(Optional.empty());

        // When
        populateService.populateVitam(populateModel);

        // Then
        io.triggerActions();
        assertThat(POPULATE_FILE).doesNotExist();
    }
}
