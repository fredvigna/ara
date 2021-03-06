/******************************************************************************
 * Copyright (C) 2020 by the ARA Contributors                                 *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 * 	 http://www.apache.org/licenses/LICENSE-2.0                               *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 *                                                                            *
 ******************************************************************************/

package com.decathlon.ara.service;

import com.decathlon.ara.ci.bean.Build;
import com.decathlon.ara.ci.bean.CycleDef;
import com.decathlon.ara.ci.bean.PlannedIndexation;
import com.decathlon.ara.ci.bean.PlatformRule;
import com.decathlon.ara.ci.service.QualityService;
import com.decathlon.ara.common.NotGonnaHappenException;
import com.decathlon.ara.domain.*;
import com.decathlon.ara.domain.enumeration.ExecutionAcceptance;
import com.decathlon.ara.domain.enumeration.JobStatus;
import com.decathlon.ara.domain.enumeration.QualityStatus;
import com.decathlon.ara.domain.enumeration.Technology;
import com.decathlon.ara.repository.CountryRepository;
import com.decathlon.ara.repository.ExecutionCompletionRequestRepository;
import com.decathlon.ara.repository.ExecutionRepository;
import com.decathlon.ara.repository.TypeRepository;
import com.decathlon.ara.scenario.common.indexer.ScenariosIndexer;
import com.decathlon.ara.scenario.common.strategy.ScenariosIndexerStrategy;
import com.decathlon.ara.service.support.Settings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Slf4j
public class ExecutionFilesProcessorService {

    @NonNull
    private final SettingService settingService;

    @NonNull
    private final ObjectMapper objectMapper;

    @NonNull
    private final ExecutionCompletionRequestRepository executionCompletionRequestRepository;

    @NonNull
    private final ExecutionRepository executionRepository;

    @NonNull
    private final CountryRepository countryRepository;

    @NonNull
    private final TypeRepository typeRepository;

    @NonNull
    private final QualityService qualityService;

    @NonNull
    private final ScenariosIndexerStrategy scenariosIndexerStrategy;

    @NonNull
    private final FileProcessorService fileProcessorService;

    /**
     * Create an execution from the planned indexation
     * @param plannedIndexation contains the folder containing all the execution files and the cycle definition
     * @return the created (indexed) execution
     */
    public Optional<Execution> getExecution(PlannedIndexation plannedIndexation) {
        if (plannedIndexation == null) {
            return Optional.empty();
        }

        File rawExecutionFile = plannedIndexation.getExecutionFolder();
        if (rawExecutionFile == null) {
            return Optional.empty();
        }

        CycleDefinition cycleDefinition = plannedIndexation.getCycleDefinition();
        if (cycleDefinition == null) {
            return Optional.empty();
        }

        Long projectId = cycleDefinition.getProjectId();
        String buildInformationFilePath = settingService.get(projectId, Settings.EXECUTION_INDEXER_FILE_BUILD_INFORMATION_PATH);
        Optional<Build> build = getBuildFromFile(rawExecutionFile, buildInformationFilePath);
        if (!build.isPresent()) {
            log.info("The build information file ({}) in [{}] couldn't be processed", buildInformationFilePath, rawExecutionFile.getAbsolutePath());
            return Optional.empty();
        }

        Optional<Execution> execution = getExecutionFromBuildAndCycleDefinition(build.get(), cycleDefinition);

        String buildUrl = build.get().getUrl();
        Optional<ExecutionCompletionRequest> completionRequest = (StringUtils.isEmpty(buildUrl) ? Optional.empty() : executionCompletionRequestRepository.findById(buildUrl));
        completionRequest.ifPresent(executionCompletionRequestRepository::delete);

        if (!execution.isPresent()) {
            return Optional.empty();
        }

        String cycleDefinitionFilePath = settingService.get(projectId, Settings.EXECUTION_INDEXER_FILE_CYCLE_DEFINITION_PATH);
        Optional<CycleDef> cycleDef = fileProcessorService.getMappedObjectFromFile(rawExecutionFile, cycleDefinitionFilePath, CycleDef.class);
        if (!cycleDef.isPresent()) {
            if (JobStatus.DONE.equals(execution.get().getStatus()) || completionRequest.isPresent()) {
                log.info("Cycle-run's cycle-definition JSON not found in done job (cycle deeply broken): indexing it as failed");
                execution.get().setBlockingValidation(false);
                return execution;
            }
            log.info("Cycle-run's cycle-definition JSON not found (too soon?): not indexing it yet");
            return Optional.empty();
        }
        execution.get().setBlockingValidation(cycleDef.get().isBlockingValidation());
        String qualityThresholds = getQualityThresholdsFromCycleDefinition(cycleDef.get());
        execution.get().setQualityThresholds(qualityThresholds);

        Pair<List<CountryDeployment>, List<Run>> countryDeploymentsAndRuns = getCountryDeploymentsAndRunsPair(rawExecutionFile, cycleDef.get(), projectId, execution.get().getStatus(), buildInformationFilePath);

        Set<CountryDeployment> countryDeployments = new TreeSet<>(countryDeploymentsAndRuns.getFirst());
        execution.get().addCountryDeployments(countryDeployments);

        Set<Run> runs = new TreeSet<>(countryDeploymentsAndRuns.getSecond());
        execution.get().addRuns(runs);

        qualityService.computeQuality(execution.get());

        Boolean executionIsIncomplete = !executionIsComplete(execution.get(), cycleDef.get());
        if (executionIsIncomplete) {
            execution.get().setQualityStatus(QualityStatus.INCOMPLETE);
        }

        return execution;
    }

    /**
     * Create a build object from the file (folder) containing the build information file (if found and processed correctly)
     * @param buildInformationParentDirectory the build information file parent folder
     * @param buildInformationPath the relative path to the build information file
     * @return the build
     */
    private Optional<Build> getBuildFromFile(File buildInformationParentDirectory, String buildInformationPath) {
        Optional<Build> build = fileProcessorService.getMappedObjectFromFile(buildInformationParentDirectory, buildInformationPath, Build.class);
        build.ifPresent(b -> b.setLink(buildInformationParentDirectory.getPath() + File.separator));
        return build;
    }

    /**
     * Create an execution from a build and the cycle definition (branch and cycle)
     * @param build the execution build
     * @param cycleDefinition the cycle definition
     * @return the execution
     */
    private Optional<Execution> getExecutionFromBuildAndCycleDefinition(Build build, CycleDefinition cycleDefinition) {
        Execution execution = new Execution();

        Optional<Execution> previousExecution = executionRepository.findByProjectIdAndJobUrlOrJobLink(
                cycleDefinition.getProjectId(),
                build.getUrl(),
                build.getLink());
        if (previousExecution.isPresent()) {
            if (JobStatus.DONE.equals(previousExecution.get().getStatus())) {
                log.info("Cycle-run is already DONE (requested to be indexed several times?): no further indexing");
                return Optional.empty();
            }
            Long executionId = previousExecution.get().getId();
            execution.setId(executionId);
        }

        final Long versionTimestamp = build.getVersionTimestamp();
        execution.setName(cycleDefinition.getName());
        execution.setBranch(cycleDefinition.getBranch());
        execution.setRelease(build.getRelease());
        execution.setVersion(build.getVersion());
        execution.setBuildDateTime(versionTimestamp == null ? null : new Date(versionTimestamp));
        execution.setTestDateTime(new Date(build.getTimestamp()));
        execution.setJobUrl(build.getUrl());
        execution.setJobLink(build.getLink());
        execution.setAcceptance(ExecutionAcceptance.NEW);
        execution.setCycleDefinition(cycleDefinition);
        execution.setQualityStatus(QualityStatus.INCOMPLETE);

        execution.setResult(build.getResult());
        execution.setDuration(build.getDuration());
        execution.setEstimatedDuration(build.getEstimatedDuration());

        JobStatus jobStatus = getJobStatusFromBuild(build);
        execution.setStatus(jobStatus);

        return Optional.of(execution);
    }

    /**
     * Get the quality thresholds json (as a plain string)
     * @param cycleDef the cycleDef
     * @return the quality threshold
     */
    private String getQualityThresholdsFromCycleDefinition(CycleDef cycleDef){
        try {
            return objectMapper.writeValueAsString(cycleDef.getQualityThresholds());
        } catch (JsonProcessingException e) {
            throw new NotGonnaHappenException("JSON serializing should not have failed when serializing to a String", e);
        }
    }

    /**
     * Convert the build to a job status
     * @param build the build to convert
     * @return a job status:
     * PENDING      if the build is null or does not have an url
     * RUNNING      if the build is building or does not have a result
     * UNAVAILABLE  if the build result is NOT_BUILT
     * DONE         otherwise
     */
    private JobStatus getJobStatusFromBuild(Build build) {
        if (build == null || StringUtils.isEmpty(build.getUrl())) {
            return JobStatus.PENDING;
        }
        if (build.isBuilding() || build.getResult() == null) {
            return JobStatus.RUNNING;
        }
        switch (build.getResult()) {
            case ABORTED:
            case FAILURE:
            case SUCCESS:
            case UNSTABLE:
                return JobStatus.DONE;
            case NOT_BUILT:
                return JobStatus.UNAVAILABLE;
            default:
                throw new NotGonnaHappenException("New Result enum value not supported yet in code: " + build.getResult());
        }
    }

    /**
     * Create a pair of country deployments and runs
     * @param rawExecutionFile the root execution folder
     * @param cycleDef the cycleDef
     * @param projectId the project id
     * @param executionJobStatus the execution job status
     * @param buildInformationPath the relative path to the build information file
     * @return country deployments and runs
     */
    private Pair<List<CountryDeployment>, List<Run>> getCountryDeploymentsAndRunsPair(File rawExecutionFile, CycleDef cycleDef, Long projectId, JobStatus executionJobStatus, String buildInformationPath) {
        List<CountryDeployment> countryDeployments = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        final File[] allExecutionFolderContents = rawExecutionFile.listFiles();
        final File[] countryJobDirectories = Arrays.stream(allExecutionFolderContents).filter(File::isDirectory).toArray(File[]::new);

        if (countryJobDirectories == null || countryJobDirectories.length == 0) {
            log.info("The folder {} doesn't contain any country", rawExecutionFile.getAbsolutePath());
        }

        final List<Country> allCountries = countryRepository.findAllByProjectIdOrderByCode(projectId);
        final List<Type> allTypes = typeRepository.findAllByProjectIdOrderByCode(projectId);

        final Map<String, List<PlatformRule>> platformsRules = cycleDef.getPlatformsRules();
        for (final Entry<String, List<PlatformRule>> entry: platformsRules.entrySet()) {
            final String platformName = entry.getKey();
            final List<PlatformRule> enabledRules = entry.getValue()
                    .stream()
                    .filter(PlatformRule::isEnabled)
                    .collect(Collectors.toList());
            for (final PlatformRule rule : enabledRules) {
                final String countryCode = rule.getCountry().toLowerCase();
                final Optional<Country> country = getCountryFromCodeAndCountries(countryCode, allCountries);

                if (!country.isPresent()) {
                    log.info("The country {} is not known. Please check your database", countryCode);
                    continue;
                }

                final Optional<File> countryJobFolder = Arrays.stream(countryJobDirectories)
                        .filter(file -> countryCode.equals(file.getName().toLowerCase()))
                        .findFirst();

                CountryDeployment countryDeployment;
                File[] allCountryJobFolderContents;
                if (countryJobFolder.isPresent()) {
                    final Optional<Build> countryBuild = getBuildFromFile(countryJobFolder.get(), buildInformationPath);
                    countryDeployment = getCompleteCountryDeployment(country.get(), platformName, countryBuild, executionJobStatus);
                    allCountryJobFolderContents = countryJobFolder.get().listFiles();
                } else {
                    countryDeployment = getUnavailableCountryDeployment(country.get(), platformName);
                    allCountryJobFolderContents = new File[0];
                    log.info("Although the country {} is defined in the cycle definition file, no matching folder was found. Please check the execution zip again", countryCode);
                }

                countryDeployments.add(countryDeployment);

                final String[] typeCodes = getTypeCodes(rule.getTestTypes());
                final File[] typeJobFolders = Arrays.stream(allCountryJobFolderContents).filter(File::isDirectory).toArray(File[]::new);

                for (final String typeCode : typeCodes) {
                    final Optional<Type> type = getTypeFromCodeAndTypes(typeCode, allTypes);

                    if (!type.isPresent()) {
                        log.info("The type {} is unknown. It maybe needs to inserted into the ARA database", typeCode);
                        continue;
                    }

                    final Source source = type.get().getSource();
                    if (source != null) {
                        final Optional<File> typeJobFolder = Arrays.stream(typeJobFolders)
                                .filter(file -> typeCode.toLowerCase().equals(file.getName().toLowerCase()))
                                .findFirst();

                        if (!typeJobFolder.isPresent()) {
                            Run run = getUnavailableRun(country.get(), type.get(), platformName, rule);
                            runs.add(run);
                            log.info("The type {} was not found in the execution zip", typeCode);
                            continue;
                        }

                        final Optional<Build> typeBuild = getBuildFromFile(typeJobFolder.get(), buildInformationPath);
                        Run run = getCompleteRun(country.get(), type.get(), platformName, rule, typeBuild, executionJobStatus);

                        Technology technology = source.getTechnology();
                        Optional<ScenariosIndexer> scenariosIndexer = scenariosIndexerStrategy.getScenariosIndexer(technology);
                        scenariosIndexer.ifPresent(indexer -> {
                            final List<ExecutedScenario> executedScenarios = indexer.getExecutedScenarios(typeJobFolder.get(), run, projectId);
                            run.addExecutedScenarios(new TreeSet<>(executedScenarios));
                        });

                        runs.add(run);
                    }
                }
            }
        }

        return Pair.of(countryDeployments, runs);
    }

    /**
     * Split type codes using a separator ({@link PlatformRule#TEST_TYPES_SEPARATOR})
     * @param rawTypeString the unprocessed string containing all the type codes
     * @return the type codes
     */
    private String[] getTypeCodes(String rawTypeString) {
        if (StringUtils.isBlank(rawTypeString)) {
            return new String[0];
        }
        return rawTypeString.split(PlatformRule.TEST_TYPES_SEPARATOR);
    }

    /**
     * Check whether an execution is complete or not.
     * An execution is complete if and only if all the rules has been handled.
     * @param execution the execution to check
     * @param cycleDef the {@link CycleDef} containing the platform rules
     * @return
     */
    private Boolean executionIsComplete(Execution execution, CycleDef cycleDef) {
        final Map<String, List<PlatformRule>> platformsRules = cycleDef.getPlatformsRules();
        return platformsRules.entrySet()
                .stream()
                .map(entry -> Pair.of(
                        entry.getKey(),
                        entry.getValue().stream()
                                .filter(PlatformRule::isEnabled)
                                .map(rule -> Pair.of(
                                        execution.getCountryDeployments().stream()
                                                .anyMatch(
                                                        cd -> cd.getCountry().getCode().toLowerCase().equals(rule.getCountry().toLowerCase()) &
                                                                cd.getPlatform().toLowerCase().equals(entry.getKey().toLowerCase())
                                                ),
                                        Arrays.stream(getTypeCodes(rule.getTestTypes()))
                                                .map(type -> execution.getRuns().stream()
                                                        .anyMatch(r ->
                                                                r.getCountry().getCode().toLowerCase().equals(rule.getCountry().toLowerCase()) &
                                                                        r.getType().getCode().toLowerCase().equals(type.toLowerCase()) &
                                                                        r.getPlatform().toLowerCase().equals(entry.getKey().toLowerCase())
                                                        )
                                                )
                                        )
                                )
                                .map(pair -> Pair.of(pair.getFirst(), pair.getSecond().allMatch(b -> b)))
                                .map(pair -> pair.getFirst() & pair.getSecond())
                                .allMatch(b -> b)
                ))
                .map(Pair::getSecond)
                .allMatch(b -> b);
    }

    /**
     * Get the country matching the country code, if found
     * @param countryCode the country code
     * @param countries all the countries available
     * @return the matching country, if any
     */
    private Optional<Country> getCountryFromCodeAndCountries(String countryCode, List<Country> countries) {
        return countries.stream()
                .filter(country -> StringUtils.isNotBlank(country.getCode()))
                .filter(country -> countryCode.equals(country.getCode().toLowerCase()))
                .findFirst();
    }

    /**
     * Create a country deployment with the status {@link JobStatus#UNAVAILABLE}
     * @param country the country
     * @param platformName the platform name
     * @return the country deployment
     */
    private CountryDeployment getUnavailableCountryDeployment(Country country, String platformName) {
        CountryDeployment countryDeployment = getCountryDeployment(country, platformName, Optional.empty());
        countryDeployment.setStatus(JobStatus.UNAVAILABLE);
        return countryDeployment;
    }

    /**
     * Create a complete country deployment
     * @param country the country
     * @param platformName the platform name
     * @param countryBuild the country build
     * @param executionJobStatus the execution job status
     * @return the country deployment
     */
    private CountryDeployment getCompleteCountryDeployment(Country country, String platformName, Optional<Build> countryBuild, JobStatus executionJobStatus) {
        CountryDeployment countryDeployment = getCountryDeployment(country, platformName, countryBuild);
        JobStatus countryDeploymentJobStatus = JobStatus.UNAVAILABLE;
        if (countryBuild.isPresent()) {
            countryDeploymentJobStatus = getRunOrCountryDeploymentJobStatus(executionJobStatus, getJobStatusFromBuild(countryBuild.get()));
        }
        countryDeployment.setStatus(countryDeploymentJobStatus);
        return countryDeployment;
    }

    /**
     * Create a country deployment without a status
     * @param country the country
     * @param platformName the platform name
     * @param countryBuild the country build
     * @return the country deployment
     */
    private CountryDeployment getCountryDeployment(Country country, String platformName, Optional<Build> countryBuild) {
        CountryDeployment countryDeployment = new CountryDeployment();
        countryDeployment.setCountry(country);
        countryDeployment.setPlatform(platformName);
        if (countryBuild.isPresent()) {
            countryDeployment.setJobLink(countryBuild.get().getLink());
            countryDeployment.setJobUrl(countryBuild.get().getUrl());
            countryDeployment.setStartDateTime(new Date(countryBuild.get().getTimestamp()));
            countryDeployment.setEstimatedDuration(countryBuild.get().getEstimatedDuration());
            countryDeployment.setDuration(countryBuild.get().getDuration());
            countryDeployment.setResult(countryBuild.get().getResult());
        }
        return countryDeployment;
    }

    /**
     * Get the type matching the type code, if found
     * @param typeCode the type code
     * @param types all the types available
     * @return the matching type, if any
     */
    private Optional<Type> getTypeFromCodeAndTypes(String typeCode, List<Type> types) {
        return types.stream()
                .filter(type -> StringUtils.isNotBlank(type.getCode()))
                .filter(type -> typeCode.toLowerCase().equals(type.getCode().toLowerCase()))
                .findFirst();
    }

    /**
     * Create a run with the status {@link JobStatus#UNAVAILABLE}
     * @param country the country
     * @param type the type
     * @param platformName the platform name
     * @param rule the rule
     * @return a run
     */
    private Run getUnavailableRun(Country country, Type type, String platformName, PlatformRule rule) {
        Run run = getRun(country, type, platformName, rule, Optional.empty());
        run.setStatus(JobStatus.UNAVAILABLE);
        return run;
    }

    /**
     * Create a complete run
     * @param country the country
     * @param type the type
     * @param platformName the platform name
     * @param rule the rule
     * @param typeBuild the type build
     * @param executionJobStatus the execution job status
     * @return a run
     */
    private Run getCompleteRun(Country country, Type type, String platformName, PlatformRule rule, Optional<Build> typeBuild, JobStatus executionJobStatus) {
        Run run = getRun(country, type, platformName, rule, typeBuild);
        JobStatus runJobStatus = JobStatus.UNAVAILABLE;
        if (typeBuild.isPresent()) {
            runJobStatus = getRunOrCountryDeploymentJobStatus(executionJobStatus, getJobStatusFromBuild(typeBuild.get()));
        }
        run.setStatus(runJobStatus);
        return run;
    }

    /**
     * Create a run without a status
     * @param country the country
     * @param type the type
     * @param platformName the platform name
     * @param rule the rule
     * @param typeBuild the type build
     * @return a run
     */
    private Run getRun(Country country, Type type, String platformName, PlatformRule rule, Optional<Build> typeBuild) {
        Run run = new Run();
        run.setCountry(country);
        run.setType(type);
        run.setPlatform(platformName);
        run.setCountryTags(rule.getCountryTags());
        run.setSeverityTags(rule.getSeverityTags());
        run.setIncludeInThresholds(rule.isBlockingValidation());
        if (typeBuild.isPresent()) {
            run.setJobUrl(typeBuild.get().getUrl());
            run.setJobLink(typeBuild.get().getLink());
            run.setStartDateTime(new Date(typeBuild.get().getTimestamp()));
            run.setEstimatedDuration(typeBuild.get().getEstimatedDuration());
            run.setDuration(typeBuild.get().getDuration());
            if (StringUtils.isNotEmpty(typeBuild.get().getComment())) {
                run.setComment(typeBuild.get().getComment());
            }
        }
        return run;
    }

    /**
     * Convert the execution job status into the country deployment or run job status equivalent.
     * If the execution job status is DONE, then the run or country deployment job status gets updated:
     * PENDING -> UNAVAILABLE
     * RUNNING -> DONE
     * otherwise, stays the same
     *
     * @param executionJobStatus the execution job status
     * @param jobStatusToConvert the (run or country deployment) job status to convert
     * @return a new job status
     */
    private JobStatus getRunOrCountryDeploymentJobStatus(JobStatus executionJobStatus, JobStatus jobStatusToConvert) {
        if (JobStatus.DONE.equals(executionJobStatus)) {
            if (JobStatus.PENDING.equals(jobStatusToConvert)) {
                return JobStatus.UNAVAILABLE;
            }

            if (JobStatus.RUNNING.equals(jobStatusToConvert)) {
                return JobStatus.DONE;
            }
        }

        return jobStatusToConvert;
    }
}
