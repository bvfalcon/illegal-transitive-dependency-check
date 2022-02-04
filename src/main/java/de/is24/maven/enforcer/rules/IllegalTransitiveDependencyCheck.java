package de.is24.maven.enforcer.rules;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule enforcing directly declared maven dependencies only
 *
 * @author André Schubert
 */
public final class IllegalTransitiveDependencyCheck implements EnforcerRule {
  private static final String NO_CACHE_ID_AVAILABLE = null;
  private static final String OUTPUT_FILE_EXTENSION = ".txt";
  private static final String OUTPUT_FILE_PREFIX = "itd-";

  private ArtifactRepository localRepository;

  private List<ArtifactRepository> remoteRepositories;

  private RepositorySystem repoSystem;

  private String outputDirectory;

  private MavenProject project;

  private MavenSession session;

  private DependencyCollectorBuilder dependencyCollectorBuilder;

  private Log logger;

  private boolean reportOnly;

  private boolean listMissingArtifacts;

  private String[] regexIgnoredClasses;

  private boolean useClassesFromLastBuild;

  private boolean suppressTypesFromJavaRuntime;

  private ClassFilter filter;

  @Override
  public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
    logger = helper.getLog();

    if (reportOnly) {
      logger.info("Flag 'reportOnly' is set. Exceptions from rule will only be reported!");
    }

    if (listMissingArtifacts) {
      logger.info("Flag 'listMissingArtifacts' is set. Transitively used artifacts are resolved.");
    }

    if (useClassesFromLastBuild) {
      logger.info("Flag 'useClassesFromLastBuild' is set. Try to use existing output folder.");
    }

    if (suppressTypesFromJavaRuntime) {
      logger.info(
        "Flag 'suppressTypesFromJavaRuntime' is set. Classes available in current Java-runtime will be ignored.");
    }

    initializeProject(helper);

    final Artifact artifact = resolveArtifact();

    // skip analyzes if the artifact has no associated file..
    if (artifact.getFile() == null) {
      logger.info("Nothing to analyze in '" + artifact.getId() + "'.");
      return;
    }

    // initialize the suppression filter
    filter = new ClassFilter(logger, suppressTypesFromJavaRuntime, regexIgnoredClasses);

    final Repository artifactClassesRepository = ArtifactRepositoryAnalyzer.analyzeArtifacts(logger,
      true,
      filter)
      .analyzeArtifacts(Collections.singleton(artifact));

    final Set<Artifact> dependencies = resolveDirectDependencies(artifact);

    final Repository dependenciesClassesRepository = ArtifactRepositoryAnalyzer.analyzeArtifacts(logger,
      false,
      filter)
      .analyzeArtifacts(dependencies);

    if (logger.isDebugEnabled()) {
      logger.debug("Artifact's type dependencies are: " + artifactClassesRepository.getDependencies());
      logger.debug("Classes defined in direct dependencies are: " + dependenciesClassesRepository.getTypes());
    }

    final Set<String> unresolvedTypes = new HashSet<String>(artifactClassesRepository.getDependencies());
    unresolvedTypes.removeAll(artifactClassesRepository.getTypes());
    unresolvedTypes.removeAll(dependenciesClassesRepository.getTypes());

    // traverse transitive dependencies to find the artifact a certain class is loaded from
    if (unresolvedTypes.isEmpty()) {
      logger.info("No illegal transitive dependencies found in '" + artifact.getId() + "'.");
    } else {
      final String message = buildOutput(artifact, unresolvedTypes);

      writeOutputFile(artifact, message);

      if (reportOnly) {
        logger.error(message);
      } else {
        throw new EnforcerRuleException(message);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Set<Artifact> resolveTransitiveDependencies(Artifact artifact) throws EnforcerRuleException {
    final DependencyNode root;
    try {
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setResolveDependencies(true);
        buildingRequest.setProject(project);
        buildingRequest.setLocalRepository(localRepository);
        buildingRequest.setRemoteRepositories( remoteRepositories );
        
        root = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null); 
    } catch (DependencyCollectorBuilderException e) {
      throw new EnforcerRuleException("Unable to build the dependency graph!", e);
    }
    if (logger.isDebugEnabled()) {
      logger.debug("Root node is '" + root + "'.");
    }

    final Set<Artifact> transitiveDependencies = new HashSet<Artifact>();
    traverseDependencyNodes(root, transitiveDependencies);

    final Set<Artifact> directDependencies = resolveDirectDependencies(artifact);
    transitiveDependencies.removeAll(directDependencies);
    if (logger.isDebugEnabled()) {
      logger.debug("Transitive dependencies are '" + transitiveDependencies + "'.");
    }
    return transitiveDependencies;
  }

  private void traverseDependencyNodes(DependencyNode node, Set<Artifact> transitiveDependencies)
    throws EnforcerRuleException {
    final List<DependencyNode> children = node.getChildren();
    if ((children == null) || children.isEmpty()) {
      return;
    }
    for (DependencyNode child : children) {
      final Artifact artifact = child.getArtifact();
      enforceArtifactResolution(artifact);
      if (logger.isDebugEnabled()) {
        logger.debug("Add dependency '" + artifact.getId() + "'");
      }
      transitiveDependencies.add(artifact);
      traverseDependencyNodes(child, transitiveDependencies);
    }
  }

  @SuppressWarnings("unchecked")
  private Set<Artifact> resolveDirectDependencies(Artifact artifact) {
    final Set<Artifact> dependencies = new HashSet<Artifact>(project.getArtifacts());
    final Set<Artifact> directDependencies = new HashSet<Artifact>(project.getDependencyArtifacts());
    dependencies.removeIf(dep -> !directDependencies.contains(dep));
    dependencies.remove(artifact);
    if (logger.isDebugEnabled()) {
      logger.debug("Direct dependencies are '" + dependencies + "'.");
    }
    dependencies.stream().forEach(dep -> enforceArtifactResolution(dep));
    return dependencies;
  }

  @SuppressWarnings("unchecked")
  private void initializeProject(EnforcerRuleHelper helper) throws EnforcerRuleException {
    try {
      project = (MavenProject) helper.evaluate("${project}");
      session = (MavenSession) helper.evaluate("${session}");
      dependencyCollectorBuilder = helper.getComponent(DependencyCollectorBuilder.class);

      localRepository = (ArtifactRepository) helper.evaluate("${localRepository}");
      remoteRepositories = (List<ArtifactRepository>) helper.evaluate("${project.remoteArtifactRepositories}");
      repoSystem = helper.getComponent(RepositorySystem.class);

      outputDirectory = (String) helper.evaluate("${project.build.directory}");

    } catch (ExpressionEvaluationException e) {
      throw new EnforcerRuleException("Unable to evaluate expression!", e);
    }
    catch ( ComponentLookupException e ) {
        throw new EnforcerRuleException("Unable to locate Component!", e);
    }
    logger.debug("Analyze project '" + project + "'.");
  }

  private Artifact resolveArtifact() throws EnforcerRuleException {
    final Artifact artifact = project.getArtifact();
    logger.info("Analyze dependencies of artifact '" + artifact.getId() + "'.");

    // use the current project's target/classes directory as fake artifact..
    if (useClassesFromLastBuild) {
      final File targetClassesDirectory = getTargetClassesDirectory();
      artifact.setFile(targetClassesDirectory);
      return artifact;
    }

    return enforceArtifactResolution(artifact);
  }

  private File getTargetClassesDirectory() {
    final Build build = project.getBuild();
    if (build != null) {
      final String classesOutputDirectory = build.getOutputDirectory();
      if (StringUtils.isNotEmpty(classesOutputDirectory)) {
        final File targetClasses = new File(classesOutputDirectory);
        if (targetClasses.isDirectory() && (targetClasses.list().length > 0)) {
          logger.debug("Found valid target/classes directory '" + targetClasses.getAbsolutePath() + "'.");
          return targetClasses;
        }
      }
    }
    logger.debug("No target/classes directory found.");
    return null;
  }

  private Artifact enforceArtifactResolution(Artifact artifact) {
    logger.debug("Enforce artifact resolution for project '" + project + "'.");
    ArtifactResolutionRequest req = new ArtifactResolutionRequest()
      .setLocalRepository(localRepository).setRemoteRepositories(remoteRepositories)
      .setArtifact(artifact);
    repoSystem.resolve(req);
    return artifact;
  }

  private String buildOutput(Artifact artifact, Set<String> unresolvedTypes) throws EnforcerRuleException {
    final StringBuilder output = new StringBuilder();
    output.append("Found ")
      .append(unresolvedTypes.size())
      .append(" illegal transitive type dependencies in artifact '")
      .append(artifact.getId())
      .append("':\n");

    final List<String> illegalTransitiveDependencies;
    if (listMissingArtifacts) {
      illegalTransitiveDependencies = new ArrayList<String>(
        findArtifactsForUnresolvedTypes(artifact, unresolvedTypes));
    } else {
      illegalTransitiveDependencies = new ArrayList<String>(unresolvedTypes);
    }

    Collections.sort(illegalTransitiveDependencies);

    int k = 1;
    for (String illegalTransitiveDependency : illegalTransitiveDependencies) {
      output.append(k).append(".) ").append(illegalTransitiveDependency).append("\n");
      k++;
    }
    return output.toString();
  }

  private Set<String> findArtifactsForUnresolvedTypes(Artifact artifact, Set<String> unresolvedTypes)
    throws EnforcerRuleException {
    final Set<Artifact> transitiveDependencies = resolveTransitiveDependencies(artifact);
    final Set<String> unresolvedTypesWithArtifact = new HashSet<String>();

    for (Artifact transitiveDependency : transitiveDependencies) {
      // skip further artifacts if all types have been found
      if (unresolvedTypesWithArtifact.size() == unresolvedTypes.size()) {
        break;
      }

      final Repository repository = ArtifactRepositoryAnalyzer.analyzeArtifacts(logger,
        false,
        filter)
        .analyzeArtifacts(Collections.singleton(transitiveDependency));

      final Set<String> repositoryTypes = repository.getTypes();
      for (String unresolvedType : unresolvedTypes) {
        if (repositoryTypes.contains(unresolvedType)) {
          unresolvedTypesWithArtifact.add(unresolvedType + ", [" + transitiveDependency.getId() + "]");
        }
      }
    }
    return unresolvedTypesWithArtifact;
  }

  private void writeOutputFile(Artifact artifact, String output) throws EnforcerRuleException {
    if (outputDirectory == null) {
      logger.warn("Project's output directory has not been set, skip writing!");
      return;
    }

    final String outputFilePath = determineOutputFilePath(artifact);
    final File outputFile = new File(outputFilePath);
    final File targetFolder = outputFile.getParentFile();
    if (!targetFolder.exists() && !targetFolder.mkdirs()) {
      final String error = "Unable to create directory '" + targetFolder + "'!";
      logger.error(error);
      throw new EnforcerRuleException(error);
    }

    FileWriter resultFileWriter = null;
    try {
      resultFileWriter = new FileWriter(outputFile);
      resultFileWriter.write(output);
    } catch (IOException e) {
      throw logAndWrapIOException(e, outputFilePath);
    } finally {
      if (resultFileWriter != null) {
        try {
          resultFileWriter.close();
        } catch (IOException e) {
          throw logAndWrapIOException(e, outputFilePath);
        }
      }
    }
  }

  private EnforcerRuleException logAndWrapIOException(IOException e, String outputFilePath) {
    final String error = "Unable to write output file '" + outputFilePath + "'!";
    logger.error(error, e);
    return new EnforcerRuleException(error, e);
  }

  private String determineOutputFilePath(Artifact artifact) {
    final String separator = outputDirectory.endsWith("/") ? "" : "/";
    final String formattedArtifactId = artifact.getId().replace(':', '-');
    return outputDirectory + separator + OUTPUT_FILE_PREFIX + formattedArtifactId + OUTPUT_FILE_EXTENSION;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  @Override
  public boolean isResultValid(EnforcerRule enforcerRule) {
    return false;
  }

  @Override
  public String getCacheId() {
    return NO_CACHE_ID_AVAILABLE;
  }

  public void setListMissingArtifacts(boolean listMissingArtifacts) {
    this.listMissingArtifacts = listMissingArtifacts;
  }

  public void setReportOnly(boolean reportOnly) {
    this.reportOnly = reportOnly;
  }

  public void setRegexIgnoredClasses(String[] regexIgnoredClasses) {
    this.regexIgnoredClasses = regexIgnoredClasses;
  }

  public void setUseClassesFromLastBuild(boolean useClassesFromLastBuild) {
    this.useClassesFromLastBuild = useClassesFromLastBuild;
  }

  public void setSuppressTypesFromJavaRuntime(boolean suppressTypesFromJavaRuntime) {
    this.suppressTypesFromJavaRuntime = suppressTypesFromJavaRuntime;
  }
}
